#!/usr/bin/env python3
"""Research/benchmark runner for Smart Traffic.

The runner is intentionally modular: detector/pose model, tracker and metric
projection can be changed independently. It is a research tool, not an
uncertified enforcement system.
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from ultralytics import YOLO


VEHICLE_CLASSES = {2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}


def load_json(path: str | None) -> dict[str, Any] | None:
    if not path:
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def project_homography(H: np.ndarray, x: float, y: float) -> tuple[float, float] | None:
    p = H @ np.array([x, y, 1.0], dtype=np.float64)
    if abs(p[2]) < 1e-12:
        return None
    return float(p[0] / p[2]), float(p[1] / p[2])


def robust_speed(samples: list[tuple[float, float, float]], min_samples: int) -> float | None:
    """Return robust m/s from (timestamp_s, x_m, y_m)."""
    if len(samples) < min_samples:
        return None
    values: list[float] = []
    ordered = sorted(samples)
    for a, b in zip(ordered[:-1], ordered[1:]):
        dt = b[0] - a[0]
        if dt <= 0 or dt > 1.5:
            continue
        values.append(math.hypot(b[1] - a[1], b[2] - a[2]) / dt)
    if len(values) < max(3, min_samples // 2):
        return None
    arr = np.asarray(values, dtype=np.float64)
    med = float(np.median(arr))
    mad = float(np.median(np.abs(arr - med)))
    scale = max(1e-6, 1.4826 * mad)
    gate = max(3.5 * scale, 0.2 * max(med, 1e-6))
    inliers = arr[np.abs(arr - med) <= gate]
    if len(inliers) < max(3, min_samples // 2):
        return None
    return float(np.median(inliers))


def dynamic_homography_from_keypoints(
    image_points: dict[str, tuple[float, float]],
    world_points: dict[str, tuple[float, float]],
) -> tuple[np.ndarray, float] | None:
    names = [name for name in image_points if name in world_points]
    if len(names) < 4:
        return None
    src = np.float32([image_points[n] for n in names])
    dst = np.float32([world_points[n] for n in names])
    H, mask = cv2.findHomography(src, dst, cv2.RANSAC, 3.0)
    if H is None or mask is None:
        return None
    projected = cv2.perspectiveTransform(src.reshape(-1, 1, 2), H).reshape(-1, 2)
    residual = np.linalg.norm(projected - dst, axis=1)
    inliers = mask.reshape(-1).astype(bool)
    rmse = float(np.sqrt(np.mean(residual[inliers] ** 2))) if np.any(inliers) else float("inf")
    return H, rmse


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--model", required=True, help="Explicit YOLO model path/name")
    parser.add_argument("--tracker", default="botsort.yaml")
    parser.add_argument("--conf", type=float, default=0.35)
    parser.add_argument("--output", default="runs/traffic")
    parser.add_argument("--calibration", help="JSON containing a 3x3 homography")
    parser.add_argument("--keypoints-model", help="Optional pose/keypoint model")
    parser.add_argument("--keypoint-template", help="JSON: keypoint name -> [x_m, y_m]")
    parser.add_argument("--dynamic-homography", action="store_true")
    parser.add_argument("--min-speed-samples", type=int, default=8)
    parser.add_argument("--save-video", action="store_true")
    args = parser.parse_args()

    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    calibration = load_json(args.calibration)
    template = load_json(args.keypoint_template)
    H_fixed = None
    if calibration and "homography" in calibration:
        H_fixed = np.asarray(calibration["homography"], dtype=np.float64).reshape(3, 3)
    world_template = None
    if template:
        world_template = {k: tuple(map(float, v)) for k, v in template.items()}

    detector = YOLO(args.model)
    pose_model = YOLO(args.keypoints_model) if args.keypoints_model else None
    cap = cv2.VideoCapture(args.source)
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open source: {args.source}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    writer = None
    if args.save_video:
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out / "annotated.mp4"), fourcc, fps if fps > 0 else 25.0, (width, height))

    history: dict[int, deque[tuple[float, float, float]]] = defaultdict(lambda: deque(maxlen=60))
    observations: list[dict[str, Any]] = []
    frame_index = 0

    for result in detector.track(
        source=args.source,
        tracker=args.tracker,
        conf=args.conf,
        stream=True,
        persist=True,
        verbose=False,
    ):
        frame_index += 1
        frame = result.orig_img
        timestamp_s = frame_index / fps if fps > 0 else frame_index / 25.0

        boxes = result.boxes
        if boxes is None or boxes.id is None:
            if writer is not None:
                writer.write(frame)
            continue

        ids = boxes.id.int().cpu().tolist()
        coords = boxes.xyxy.cpu().numpy()
        classes = boxes.cls.int().cpu().tolist()
        scores = boxes.conf.cpu().numpy().tolist()

        frame_keypoints: dict[str, tuple[float, float]] = {}
        if args.dynamic_homography and pose_model is not None:
            pose_result = pose_model(frame, verbose=False)[0]
            if pose_result.keypoints is not None and pose_result.keypoints.xy is not None:
                # The research runner uses the pose model's named/keypoint index
                # mapping supplied by the template JSON. Names are intentionally
                # external because a 36-keypoint vehicle checkpoint is not
                # universal across datasets.
                kxy = pose_result.keypoints.xy[0].cpu().numpy()
                if world_template:
                    for name, index_value in ((k, i) for i, k in enumerate(world_template.keys())):
                        if index_value < len(kxy):
                            frame_keypoints[name] = (float(kxy[index_value, 0]), float(kxy[index_value, 1]))

        for track_id, box, cls_id, score in zip(ids, coords, classes, scores):
            if cls_id not in VEHICLE_CLASSES:
                continue
            left, top, right, bottom = map(float, box)
            contact = ((left + right) * 0.5, bottom)

            H_used = H_fixed
            h_error = None
            if args.dynamic_homography and frame_keypoints and world_template:
                dynamic = dynamic_homography_from_keypoints(frame_keypoints, world_template)
                if dynamic is not None:
                    H_used, h_error = dynamic

            ground = project_homography(H_used, *contact) if H_used is not None else None
            if ground is not None:
                history[track_id].append((timestamp_s, ground[0], ground[1]))
            speed_ms = robust_speed(list(history[track_id]), args.min_speed_samples)

            observations.append(
                {
                    "frame": frame_index,
                    "timestamp_s": timestamp_s,
                    "track_id": track_id,
                    "class": VEHICLE_CLASSES[cls_id],
                    "confidence": float(score),
                    "bbox": [left, top, right, bottom],
                    "contact_point": list(contact),
                    "ground_m": list(ground) if ground is not None else None,
                    "speed_kmh": speed_ms * 3.6 if speed_ms is not None else None,
                    "homography_error": h_error,
                }
            )

            if writer is not None:
                x1, y1, x2, y2 = map(int, box)
                cv2.rectangle(frame, (x1, y1), (x2, y2), (255, 255, 255), 2)
                label = f"ID {track_id} {VEHICLE_CLASSES[cls_id]}"
                if speed_ms is not None:
                    label += f" {speed_ms * 3.6:.1f} km/h"
                cv2.putText(frame, label, (x1, max(20, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)

        if writer is not None:
            writer.write(frame)

    cap.release()
    if writer is not None:
        writer.release()

    with open(out / "tracks.jsonl", "w", encoding="utf-8") as handle:
        for row in observations:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    summary = {
        "source": args.source,
        "model": args.model,
        "tracker": args.tracker,
        "confidence": args.conf,
        "fps": fps,
        "width": width,
        "height": height,
        "frames": frame_index,
        "observations": len(observations),
        "fixed_homography": H_fixed is not None,
        "dynamic_homography": bool(args.dynamic_homography),
        "keypoints_model": args.keypoints_model,
    }
    with open(out / "summary.json", "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
