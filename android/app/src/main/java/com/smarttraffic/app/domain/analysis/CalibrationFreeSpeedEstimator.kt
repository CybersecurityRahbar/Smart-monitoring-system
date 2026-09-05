package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Calibration-free speed estimate for ordinary vehicle tracks.
 *
 * This is intentionally an estimate, not a metrology result: metric scale is
 * inferred from a robust vehicle-width prior, then combined with image motion.
 * No camera focal length, homography, road survey, or fixed px/m constant is
 * assumed. The result carries a separate mode so callers cannot confuse it
 * with validated ground-plane speed.
 */
object CalibrationFreeSpeedEstimator {
    private data class Point(val x: Double, val y: Double, val tMs: Long, val widthPx: Double)
    private data class MotionSample(val dxPx: Double, val dyPx: Double, val speedPxPerSec: Double)

    fun estimate(
        track: Track,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
        maximumObservationGapMs: Long,
    ): SpeedEstimate? {
        if (track.state != TrackState.CONFIRMED) return null
        if (track.trackConfidence < 0.50f) return null
        if (minimumSamples < 4 || minimumDurationMs < 0L || maximumObservationGapMs <= 0L) return null
        if (maxPlausibleSpeedKmh <= 0.0 || !maxPlausibleSpeedKmh.isFinite()) return null

        val points = track.observations
            .asSequence()
            .sortedWith(compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex })
            .mapNotNull { observation ->
                val width = (observation.detection.right - observation.detection.left).toDouble()
                val x = (observation.detection.left + observation.detection.right) * 0.5
                val y = observation.detection.bottom.toDouble()
                if (x.isFinite() && y.isFinite() && width.isFinite() && width >= 4.0 && observation.timestampMs >= 0L) {
                    Point(x, y, observation.timestampMs, width)
                } else null
            }
            .distinctBy { it.tMs }
            .toList()

        if (points.size < minimumSamples) return null
        val durationMs = points.last().tMs - points.first().tMs
        if (durationMs < minimumDurationMs) return null
        if (points.zipWithNext().any { it.second.tMs - it.first.tMs > maximumObservationGapMs }) return null

        val motionSamples = ArrayList<MotionSample>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dtSeconds = (b.tMs - a.tMs) / 1000.0
            if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > maximumObservationGapMs / 1000.0) continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val displacement = hypot(dx, dy)
            if (!displacement.isFinite() || displacement < 0.25) continue
            val speed = displacement / dtSeconds
            if (!speed.isFinite() || speed <= 0.0) continue
            motionSamples += MotionSample(dx, dy, speed)
        }
        if (motionSamples.size < max(3, minimumSamples - 1)) return null

        var axisX: Double
        var axisY: Double
        val cxx = motionSamples.sumOf { it.dxPx * it.dxPx }
        val cyy = motionSamples.sumOf { it.dyPx * it.dyPx }
        val cxy = motionSamples.sumOf { it.dxPx * it.dyPx }
        if (cxx.isFinite() && cyy.isFinite() && cxy.isFinite() && (cxx + cyy) > 1e-9) {
            val theta = 0.5 * atan2(2.0 * cxy, cxx - cyy)
            axisX = kotlin.math.cos(theta)
            axisY = kotlin.math.sin(theta)
        } else {
            val first = motionSamples.first()
            val magnitude = hypot(first.dxPx, first.dyPx)
            axisX = first.dxPx / magnitude
            axisY = first.dyPx / magnitude
        }
        val axisNorm = hypot(axisX, axisY)
        if (!axisNorm.isFinite() || axisNorm < 1e-9) return null
        axisX /= axisNorm
        axisY /= axisNorm

        val signedMotion = motionSamples.sumOf { it.dxPx * axisX + it.dyPx * axisY }
        if (signedMotion < 0.0) {
            axisX = -axisX
            axisY = -axisY
        }

        val aligned = motionSamples.map { sample ->
            abs(sample.dxPx * axisX + sample.dyPx * axisY) / max(1e-9, hypot(sample.dxPx, sample.dyPx))
        }
        val directionConsistency = aligned.sorted().let { percentile(it, 0.5) }
        val projectedSpeedsPxPerSec = motionSamples.map {
            abs(it.dxPx * axisX + it.dyPx * axisY)
        }.filter { it.isFinite() && it > 0.0 }
        if (projectedSpeedsPxPerSec.size < 3) return null

        val pixelSpeed = robustMedian(projectedSpeedsPxPerSec) ?: return null
        if (!pixelSpeed.isFinite() || pixelSpeed <= 0.0) return null

        val widths = points.map { it.widthPx }.filter { it.isFinite() && it >= 4.0 }.sorted()
        if (widths.size < minimumSamples) return null
        val medianWidth = percentile(widths, 0.5)
        if (!medianWidth.isFinite() || medianWidth <= 0.0) return null

        val assumedVehicleWidthM = vehicleWidthPriorMeters(track.className)
        val meterPerPixel = assumedVehicleWidthM / medianWidth
        val speedMps = pixelSpeed * meterPerPixel
        val speedKmh = speedMps * 3.6
        if (!speedKmh.isFinite() || speedKmh <= 0.0 || speedKmh > maxPlausibleSpeedKmh) return null

        val widthMad = robustMad(widths, medianWidth)
        val widthInstability = (widthMad / medianWidth).coerceIn(0.0, 1.0)
        val speedResidual = pairwiseResidual(projectedSpeedsPxPerSec, pixelSpeed)
        val durationConfidence = ((durationMs - minimumDurationMs).toDouble() / max(1.0, minimumDurationMs * 3.0))
            .coerceIn(0.0, 1.0)
        val sampleConfidence = (points.size.toDouble() / 24.0).coerceIn(0.0, 1.0)
        val trackConfidence = track.trackConfidence.toDouble().coerceIn(0.0, 1.0)
        val motionConfidence = directionConsistency.coerceIn(0.0, 1.0)
        val speedStability = exp(-speedResidual.coerceIn(0.0, 2.0)).coerceIn(0.0, 1.0)
        val sizeStability = exp(-4.0 * widthInstability).coerceIn(0.0, 1.0)
        val confidence = (
            0.20 * durationConfidence +
                0.18 * sampleConfidence +
                0.18 * trackConfidence +
                0.18 * motionConfidence +
                0.14 * speedStability +
                0.12 * sizeStability
            ).toFloat().coerceIn(0.10f, 0.95f)

        val priorRelativeUncertainty = vehicleWidthPriorRelativeUncertainty(track.className)
        val imageMotionUncertainty = min(0.45, 0.06 + speedResidual * 0.25)
        val totalRelativeUncertainty = kotlin.math.sqrt(
            priorRelativeUncertainty * priorRelativeUncertainty +
                imageMotionUncertainty * imageMotionUncertainty +
                widthInstability * widthInstability,
        ).coerceIn(0.08, 0.60)
        val errorKmh = max(1.5, speedKmh * totalRelativeUncertainty)
        val directionDegrees = Math.toDegrees(atan2(axisY, axisX))

        return SpeedEstimate(
            metersPerSecond = speedMps,
            kilometersPerHour = speedKmh,
            confidence = confidence,
            sampleCount = points.size,
            durationMs = durationMs,
            velocityXMps = axisX * speedMps,
            velocityYMps = axisY * speedMps,
            directionDegrees = directionDegrees,
            positionResidualMeters = null,
            errorKmh = errorKmh,
            mode = SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE,
        )
    }

    private fun vehicleWidthPriorMeters(className: String): Double = when {
        className.equals("motorcycle", ignoreCase = true) -> 0.85
        className.equals("bus", ignoreCase = true) -> 2.55
        className.equals("truck", ignoreCase = true) -> 2.50
        className.equals("car", ignoreCase = true) -> 1.80
        className.equals("van", ignoreCase = true) -> 2.00
        else -> 1.90
    }

    private fun vehicleWidthPriorRelativeUncertainty(className: String): Double = when {
        className.equals("motorcycle", ignoreCase = true) -> 0.22
        className.equals("bus", ignoreCase = true) -> 0.16
        className.equals("truck", ignoreCase = true) -> 0.18
        className.equals("car", ignoreCase = true) -> 0.17
        className.equals("van", ignoreCase = true) -> 0.18
        else -> 0.22
    }

    private fun robustMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val median = percentile(sorted, 0.5)
        val mad = robustMad(sorted, median)
        val cutoff = max(1e-6, 3.0 * max(mad, median * 0.04))
        val inliers = sorted.filter { abs(it - median) <= cutoff }
        return if (inliers.size >= 3) percentile(inliers, 0.5) else median
    }

    private fun robustMad(values: List<Double>, median: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val deviations = values.map { abs(it - median) }.sorted()
        return percentile(deviations, 0.5)
    }

    private fun pairwiseResidual(values: List<Double>, median: Double): Double {
        if (values.isEmpty() || !median.isFinite() || median <= 0.0) return 1.0
        val mad = robustMad(values, median)
        return (mad / median).coerceIn(0.0, 1.0)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex
        val low = position.toInt()
        val high = min(sorted.lastIndex, low + 1)
        if (low == high) return sorted[low]
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
