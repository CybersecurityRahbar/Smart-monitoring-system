package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Homography-backed image -> metric-road projection. The 9 coefficients are row-major. */
class HomographyProjector(private val coefficients: List<Double>) {
    init {
        require(coefficients.size == 9) { "Homography must contain exactly 9 coefficients" }
        require(coefficients.all { it.isFinite() }) { "Homography contains non-finite coefficients" }
        require(coefficients.any { abs(it) > 1e-12 }) { "Homography cannot be all zeros" }
    }

    fun project(pixelX: Double, pixelY: Double): GroundPoint {
        require(pixelX.isFinite() && pixelY.isFinite()) { "Pixel coordinate must be finite" }
        val w = coefficients[6] * pixelX + coefficients[7] * pixelY + coefficients[8]
        require(w.isFinite() && abs(w) > 1e-12) { "Homography maps point to infinity" }
        val x = (coefficients[0] * pixelX + coefficients[1] * pixelY + coefficients[2]) / w
        val y = (coefficients[3] * pixelX + coefficients[4] * pixelY + coefficients[5]) / w
        require(x.isFinite() && y.isFinite()) { "Homography produced non-finite metric coordinates" }
        return GroundPoint(x, y, pixelX, pixelY)
    }

    fun distance(a: GroundPoint, b: GroundPoint): Double =
        hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
}

/**
 * Robust metric-trajectory speed estimator.
 *
 * Instead of differentiating every adjacent position pair, this implementation uses
 * Theil-Sen style pairwise slopes in metric space, rejects outlying pair velocities
 * with MAD, then takes robust median velocity components. This avoids the systematic
 * positive bias that noisy point jitter can introduce when speed is computed as a
 * sequence of non-negative Euclidean step lengths.
 *
 * The returned confidence is a quality score derived from temporal coverage, velocity
 * stability, residual-to-trajectory error and pair coverage. It is not a calibrated
 * probability and must not be presented as one.
 */
class RobustSpeedEstimator(
    private val minimumSamples: Int = 8,
    private val minimumDurationMs: Long = 500L,
    private val maxPairGapMs: Long = 1200L,
    private val maxPlausibleSpeedKmh: Double = 250.0,
) {
    fun estimate(observations: List<TrackObservation>): SpeedEstimate? {
        require(minimumSamples >= 4) { "minimumSamples must be >= 4" }
        require(minimumDurationMs >= 0L) { "minimumDurationMs must be >= 0" }
        require(maxPairGapMs > 0L) { "maxPairGapMs must be > 0" }
        require(maxPlausibleSpeedKmh > 0.0) { "maxPlausibleSpeedKmh must be > 0" }

        val points = observations
            .asSequence()
            .filter { it.groundPoint != null && it.timestampMs >= 0L }
            .sortedBy { it.timestampMs }
            .mapNotNull { observation ->
                val ground = observation.groundPoint ?: return@mapNotNull null
                if (!ground.xMeters.isFinite() || !ground.yMeters.isFinite()) return@mapNotNull null
                TimedPoint(observation.timestampMs, ground.xMeters, ground.yMeters)
            }
            .distinctBy { it.timestampMs to it.x to it.y }
            .toList()

        if (points.size < minimumSamples) return null
        val durationMs = points.last().timestampMs - points.first().timestampMs
        if (durationMs < minimumDurationMs) return null

        val slopes = ArrayList<PairSlope>()
        for (i in 0 until points.lastIndex) {
            for (j in i + 1 until points.size) {
                val dtMs = points[j].timestampMs - points[i].timestampMs
                if (dtMs <= 0L || dtMs > maxPairGapMs) continue
                val dtSeconds = dtMs / 1000.0
                val vx = (points[j].x - points[i].x) / dtSeconds
                val vy = (points[j].y - points[i].y) / dtSeconds
                val speed = hypot(vx, vy)
                if (vx.isFinite() && vy.isFinite() && speed.isFinite() &&
                    speed <= maxPlausibleSpeedKmh / 3.6) {
                    slopes += PairSlope(vx, vy, speed)
                }
            }
        }

        val minimumPairs = max(6, minimumSamples * 2)
        if (slopes.size < minimumPairs) return null

        val speedMedian = median(slopes.map { it.speed })
        val speedMad = median(slopes.map { abs(it.speed - speedMedian) })
        val robustScale = max(1e-6, 1.4826 * speedMad)
        val gate = max(0.20, 3.5 * robustScale)
        val inliers = slopes.filter { abs(it.speed - speedMedian) <= gate }
        if (inliers.size < minimumPairs) return null

        val vx = median(inliers.map { it.vx })
        val vy = median(inliers.map { it.vy })
        val speed = hypot(vx, vy)
        if (!speed.isFinite() || speed > maxPlausibleSpeedKmh / 3.6) return null

        val t0 = points.first().timestampMs
        val x0 = median(points.map { it.x }) - vx * ((median(points.map { it.timestampMs.toDouble() }) - t0) / 1000.0)
        val y0 = median(points.map { it.y }) - vy * ((median(points.map { it.timestampMs.toDouble() }) - t0) / 1000.0)
        val residuals = points.map { point ->
            val dt = (point.timestampMs - t0) / 1000.0
            hypot(point.x - (x0 + vx * dt), point.y - (y0 + vy * dt))
        }
        val medianResidual = median(residuals)
        val p90Residual = percentile(residuals, 0.90)

        val speedResiduals = inliers.map { abs(it.speed - speed) }
        val velocityMad = median(speedResiduals)
        val coverage = (inliers.size.toDouble() / slopes.size).coerceIn(0.0, 1.0)
        val stability = (1.0 - min(1.0, (1.4826 * velocityMad) / max(speed, 0.25))).coerceIn(0.0, 1.0)
        val trajectoryQuality = (1.0 - min(1.0, medianResidual / 2.0)).coerceIn(0.0, 1.0)
        val durationQuality = (durationMs / 2000.0).coerceIn(0.0, 1.0)
        val confidence = (0.30 * coverage + 0.30 * stability + 0.25 * trajectoryQuality + 0.15 * durationQuality)
            .coerceIn(0.0, 1.0)

        val uncertaintyMps = max(
            1.4826 * velocityMad * 2.0,
            p90Residual / max(durationMs / 1000.0, 0.1),
        )

        return SpeedEstimate(
            metersPerSecond = speed,
            kilometersPerHour = speed * 3.6,
            confidence = confidence.toFloat(),
            sampleCount = inliers.size,
            durationMs = durationMs,
            velocityXMps = vx,
            velocityYMps = vy,
            directionDegrees = Math.toDegrees(kotlin.math.atan2(vy, vx)),
            positionResidualMeters = medianResidual,
            errorKmh = uncertaintyMps * 3.6,
        )
    }

    private data class TimedPoint(val timestampMs: Long, val x: Double, val y: Double)
    private data class PairSlope(val vx: Double, val vy: Double, val speed: Double)

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val position = p.coerceIn(0.0, 1.0) * (sorted.lastIndex)
        val lower = position.toInt()
        val upper = min(sorted.lastIndex, lower + 1)
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }
}
