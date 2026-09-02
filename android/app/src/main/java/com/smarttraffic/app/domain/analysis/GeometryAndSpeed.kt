package com.smarttraffic.app.domain.analysis

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Homography-backed image -> metric-road projection. The 9 coefficients are row-major. */
class HomographyProjector(private val coefficients: List<Double>) {
    init {
        require(coefficients.size == 9) { "Homography must contain exactly 9 coefficients" }
        require(coefficients.any { kotlin.math.abs(it) > 1e-12 }) { "Homography cannot be all zeros" }
    }

    fun project(pixelX: Double, pixelY: Double): GroundPoint {
        val w = coefficients[6] * pixelX + coefficients[7] * pixelY + coefficients[8]
        require(kotlin.math.abs(w) > 1e-12) { "Homography maps point to infinity" }
        val x = (coefficients[0] * pixelX + coefficients[1] * pixelY + coefficients[2]) / w
        val y = (coefficients[3] * pixelX + coefficients[4] * pixelY + coefficients[5]) / w
        return GroundPoint(x, y, pixelX, pixelY)
    }

    fun distance(a: GroundPoint, b: GroundPoint): Double =
        hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
}

/**
 * Robust speed estimator using a sliding set of metric positions.
 * It estimates local velocity from pairwise slopes, rejects extreme MAD outliers,
 * then returns a weighted median-like central estimate with quality gates.
 */
class RobustSpeedEstimator(
    private val minimumSamples: Int = 8,
    private val minimumDurationMs: Long = 500L,
    private val maxPairGapMs: Long = 1200L,
) {
    fun estimate(observations: List<TrackObservation>): SpeedEstimate? {
        val points = observations
            .filter { it.groundPoint != null && it.timestampMs >= 0L }
            .sortedBy { it.timestampMs }

        if (points.size < minimumSamples) return null
        val duration = points.last().timestampMs - points.first().timestampMs
        if (duration < minimumDurationMs) return null

        val velocities = ArrayList<Double>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dtMs = b.timestampMs - a.timestampMs
            if (dtMs <= 0L || dtMs > maxPairGapMs) continue
            val ga = a.groundPoint ?: continue
            val gb = b.groundPoint ?: continue
            velocities += hypot(gb.xMeters - ga.xMeters, gb.yMeters - ga.yMeters) / (dtMs / 1000.0)
        }

        if (velocities.size < max(3, minimumSamples / 2)) return null
        val median = median(velocities)
        val deviations = velocities.map { kotlin.math.abs(it - median) }
        val mad = median(deviations)
        val robustScale = max(1e-6, 1.4826 * mad)
        val gate = max(robustScale * 3.5, median * 0.20)
        val inliers = velocities.filter { kotlin.math.abs(it - median) <= gate }
        if (inliers.size < max(3, minimumSamples / 2)) return null

        val speed = median(inliers)
        val dispersion = median(inliers.map { kotlin.math.abs(it - speed) })
        val confidence = confidence(
            sampleCount = inliers.size,
            totalSamples = velocities.size,
            relativeDispersion = if (speed > 1e-6) dispersion / speed else Double.POSITIVE_INFINITY,
        )

        return SpeedEstimate(
            metersPerSecond = speed,
            kilometersPerHour = speed * 3.6,
            confidence = confidence.toFloat(),
            sampleCount = inliers.size,
            durationMs = duration,
            positionResidualMeters = null,
            errorKmh = dispersion * 3.6 * 2.0,
        )
    }

    private fun confidence(sampleCount: Int, totalSamples: Int, relativeDispersion: Double): Double {
        val coverage = (sampleCount.toDouble() / max(1, totalSamples)).coerceIn(0.0, 1.0)
        val stability = (1.0 - min(1.0, relativeDispersion * 4.0)).coerceIn(0.0, 1.0)
        val durationBonus = ((sampleCount - minimumSamples + 1).toDouble() / minimumSamples).coerceIn(0.0, 1.0)
        return (0.45 * coverage + 0.45 * stability + 0.10 * durationBonus).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
