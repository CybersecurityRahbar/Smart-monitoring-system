"""Robust metric-trajectory speed estimation shared by offline experiments.

This intentionally mirrors the Android estimator: pairwise metric slopes, MAD gating,
robust median velocity components, trajectory residuals and a quality score. The quality
score is not a calibrated probability and must be validated against independent ground truth.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class SpeedEstimate:
    meters_per_second: float
    kilometers_per_hour: float
    confidence: float
    sample_count: int
    duration_ms: int
    velocity_x_mps: float
    velocity_y_mps: float
    direction_degrees: float
    median_residual_m: float
    uncertainty_kmh: float


def _median(values: np.ndarray) -> float:
    if values.size == 0:
        raise ValueError("median requires at least one value")
    return float(np.median(values))


def estimate_speed(
    samples: list[tuple[float, float, float]],
    *,
    min_samples: int = 8,
    min_duration_ms: int = 500,
    max_pair_gap_ms: int = 1200,
    max_plausible_speed_kmh: float = 250.0,
) -> SpeedEstimate | None:
    if min_samples < 4:
        raise ValueError("min_samples must be >= 4")
    if min_duration_ms < 0 or max_pair_gap_ms <= 0 or max_plausible_speed_kmh <= 0:
        raise ValueError("invalid speed estimator configuration")

    ordered = sorted(
        (float(t), float(x), float(y))
        for t, x, y in samples
        if all(math.isfinite(v) for v in (t, x, y))
    )
    if len(ordered) < min_samples:
        return None

    duration_ms = int(round((ordered[-1][0] - ordered[0][0]) * 1000.0))
    if duration_ms < min_duration_ms:
        return None

    slopes: list[tuple[float, float, float]] = []
    for a, b in itertools.combinations(ordered, 2):
        dt_ms = (b[0] - a[0]) * 1000.0
        if dt_ms <= 0 or dt_ms > max_pair_gap_ms:
            continue
        dt_s = dt_ms / 1000.0
        vx = (b[1] - a[1]) / dt_s
        vy = (b[2] - a[2]) / dt_s
        speed = math.hypot(vx, vy)
        if (
            math.isfinite(vx)
            and math.isfinite(vy)
            and math.isfinite(speed)
            and speed <= max_plausible_speed_kmh / 3.6
        ):
            slopes.append((vx, vy, speed))

    minimum_pairs = max(6, min_samples * 2)
    if len(slopes) < minimum_pairs:
        return None

    speeds = np.asarray([row[2] for row in slopes], dtype=np.float64)
    speed_median = _median(speeds)
    speed_mad = _median(np.abs(speeds - speed_median))
    robust_scale = max(1e-6, 1.4826 * speed_mad)
    gate = max(0.20, 3.5 * robust_scale)
    mask = np.abs(speeds - speed_median) <= gate
    if int(mask.sum()) < minimum_pairs:
        return None

    inliers = np.asarray([slopes[i] for i in range(len(slopes)) if bool(mask[i])], dtype=np.float64)
    vx = _median(inliers[:, 0])
    vy = _median(inliers[:, 1])
    speed = math.hypot(vx, vy)
    if not math.isfinite(speed) or speed > max_plausible_speed_kmh / 3.6:
        return None

    times = np.asarray([row[0] for row in ordered], dtype=np.float64)
    xs = np.asarray([row[1] for row in ordered], dtype=np.float64)
    ys = np.asarray([row[2] for row in ordered], dtype=np.float64)
    t0 = times[0]
    median_t = _median(times)
    x0 = _median(xs) - vx * (median_t - t0)
    y0 = _median(ys) - vy * (median_t - t0)
    predicted_x = x0 + vx * (times - t0)
    predicted_y = y0 + vy * (times - t0)
    residuals = np.hypot(xs - predicted_x, ys - predicted_y)
    median_residual = _median(residuals)
    p90_residual = float(np.quantile(residuals, 0.90))

    velocity_residuals = np.abs(inliers[:, 2] - speed)
    velocity_mad = _median(velocity_residuals)
    coverage = float(len(inliers) / len(slopes))
    stability = max(0.0, min(1.0, 1.0 - (1.4826 * velocity_mad) / max(speed, 0.25)))
    trajectory_quality = max(0.0, min(1.0, 1.0 - median_residual / 2.0))
    duration_quality = max(0.0, min(1.0, duration_ms / 2000.0))
    confidence = max(
        0.0,
        min(1.0, 0.30 * coverage + 0.30 * stability + 0.25 * trajectory_quality + 0.15 * duration_quality),
    )
    uncertainty_mps = max(1.4826 * velocity_mad * 2.0, p90_residual / max(duration_ms / 1000.0, 0.1))

    return SpeedEstimate(
        meters_per_second=speed,
        kilometers_per_hour=speed * 3.6,
        confidence=confidence,
        sample_count=len(inliers),
        duration_ms=duration_ms,
        velocity_x_mps=vx,
        velocity_y_mps=vy,
        direction_degrees=math.degrees(math.atan2(vy, vx)),
        median_residual_m=median_residual,
        uncertainty_kmh=uncertainty_mps * 3.6,
    )
