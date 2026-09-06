package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Calibration-free speed estimate for ordinary vehicle tracks.
 *
 * This is deliberately an estimate, not a metrology result. The implementation
 * has no roadway survey, camera calibration, fixed global px/m constant, or
 * learned metric vehicle-keypoint model. Instead it uses a class-dependent
 * vehicle-width prior as a weak metric cue and makes the image-motion estimate
 * as robust as possible with local scale, temporal trimming, dominant-motion
 * projection, cumulative path fitting, and independent estimator agreement.
 *
 * The architecture is intentionally compatible with the 2026 research direction
 * of dynamic, vehicle-specific metric reasoning, but it is NOT a byte-for-byte
 * implementation of the 36-keypoint/keypoint-homography paper. A learned
 * VehicleKeypointEstimator backend is still required for that research-grade
 * path and is therefore kept as an explicit extension point in the pipeline.
 */
object CalibrationFreeSpeedEstimator {
    private const val EDGE_TRIM_FRACTION = 0.10
    private const val MIN_VALID_WIDTH_PX = 4.0
    private const val MIN_INTERVAL_DISPLACEMENT_PX = 0.25
    private const val MAX_REGRESSION_POINTS = 180

    private data class Point(
        val x: Double,
        val y: Double,
        val tMs: Long,
        val widthPx: Double,
        val confidence: Double,
    )

    private data class MotionSample(
        val dxPx: Double,
        val dyPx: Double,
        val dtSeconds: Double,
        val speedPxPerSec: Double,
        val metricSpeedMps: Double,
        val metricDistanceM: Double,
        val confidence: Double,
    )

    private data class MetricSample(
        val tSeconds: Double,
        val distanceM: Double,
    )

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

        val rawPoints = track.observations
            .asSequence()
            .sortedWith(compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex })
            .mapNotNull { observation ->
                val width = (observation.detection.right - observation.detection.left).toDouble()
                val x = (observation.detection.left + observation.detection.right) * 0.5
                val y = observation.detection.bottom.toDouble()
                val confidence = observation.detection.confidence.toDouble().coerceIn(0.0, 1.0)
                if (
                    x.isFinite() && y.isFinite() && width.isFinite() && width >= MIN_VALID_WIDTH_PX &&
                    observation.timestampMs >= 0L
                ) {
                    Point(x, y, observation.timestampMs, width, confidence)
                } else {
                    null
                }
            }
            .distinctBy { it.tMs }
            .toList()

        if (rawPoints.size < minimumSamples) return null

        // The latest 2026 calibration-free research reports that edge-of-track
        // samples are disproportionately noisy. Remove only a small symmetric
        // fraction and retain enough points for the caller's requested sample count.
        val points = trimTrackEdges(rawPoints, minimumSamples)
        if (points.size < minimumSamples) return null

        val durationMs = points.last().tMs - points.first().tMs
        if (durationMs < minimumDurationMs) return null
        if (points.zipWithNext().any { it.second.tMs - it.first.tMs > maximumObservationGapMs }) return null

        val assumedVehicleWidthM = vehicleWidthPriorMeters(track.className)
        if (!assumedVehicleWidthM.isFinite() || assumedVehicleWidthM <= 0.0) return null

        val motionSamples = ArrayList<MotionSample>(points.size - 1)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dtSeconds = (b.tMs - a.tMs) / 1000.0
            if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > maximumObservationGapMs / 1000.0) continue

            val dx = b.x - a.x
            val dy = b.y - a.y
            val displacement = hypot(dx, dy)
            if (!displacement.isFinite() || displacement < MIN_INTERVAL_DISPLACEMENT_PX) continue

            val speedPxPerSec = displacement / dtSeconds
            if (!speedPxPerSec.isFinite() || speedPxPerSec <= 0.0) continue

            // Scale is refreshed locally from the apparent vehicle width in this
            // interval rather than using one width for the whole trajectory.
            val localWidthPx = (a.widthPx + b.widthPx) * 0.5
            if (!localWidthPx.isFinite() || localWidthPx < MIN_VALID_WIDTH_PX) continue
            val metricDistanceM = displacement * (assumedVehicleWidthM / localWidthPx)
            val metricSpeedMps = metricDistanceM / dtSeconds
            if (!metricDistanceM.isFinite() || metricDistanceM <= 0.0 || !metricSpeedMps.isFinite()) continue

            motionSamples += MotionSample(
                dxPx = dx,
                dyPx = dy,
                dtSeconds = dtSeconds,
                speedPxPerSec = speedPxPerSec,
                metricSpeedMps = metricSpeedMps,
                metricDistanceM = metricDistanceM,
                confidence = ((a.confidence + b.confidence) * 0.5).coerceIn(0.0, 1.0),
            )
        }
        if (motionSamples.size < max(3, minimumSamples - 1)) return null

        val (axisX, axisY) = dominantMotionAxis(motionSamples)
        val projectionRatios = motionSamples.map { sample ->
            abs(sample.dxPx * axisX + sample.dyPx * axisY) /
                max(1e-9, hypot(sample.dxPx, sample.dyPx))
        }
        val directionConsistency = robustMedian(projectionRatios) ?: return null
        if (!directionConsistency.isFinite() || directionConsistency < 0.55) return null

        val projectedMetricSpeeds = motionSamples.mapIndexedNotNull { index, sample ->
            val projectedRatio = projectionRatios[index].coerceIn(0.0, 1.0)
            val projectedSpeed = sample.metricSpeedMps * projectedRatio
            if (projectedSpeed.isFinite() && projectedSpeed > 0.0) projectedSpeed else null
        }
        if (projectedMetricSpeeds.size < 3) return null

        val instantaneousSpeedMps = robustWeightedMedian(
            projectedMetricSpeeds,
            motionSamples.take(projectedMetricSpeeds.size).map { it.confidence },
        ) ?: return null
        if (!instantaneousSpeedMps.isFinite() || instantaneousSpeedMps <= 0.0) return null

        val metricTrajectory = cumulativeMetricTrajectory(
            points = points,
            motions = motionSamples,
            axisX = axisX,
            axisY = axisY,
        )
        if (metricTrajectory.size < 4) return null

        val regressionSamples = downsample(metricTrajectory, MAX_REGRESSION_POINTS)
        val trajectorySpeedMps = theilSenSlope(regressionSamples) ?: return null
        if (!trajectorySpeedMps.isFinite() || trajectorySpeedMps <= 0.0) return null

        val agreementResidual =
            abs(trajectorySpeedMps - instantaneousSpeedMps) / max(1e-6, max(trajectorySpeedMps, instantaneousSpeedMps))
        val speedMps = robustWeightedMedian(
            listOf(instantaneousSpeedMps, trajectorySpeedMps),
            listOf(
                (0.55 + 0.45 * directionConsistency).coerceIn(0.0, 1.0),
                (0.60 + 0.40 * directionConsistency).coerceIn(0.0, 1.0),
            ),
        ) ?: return null

        val speedKmh = speedMps * 3.6
        if (!speedKmh.isFinite() || speedKmh <= 0.0 || speedKmh > maxPlausibleSpeedKmh) return null

        val widths = points.map { it.widthPx }.filter { it.isFinite() && it >= MIN_VALID_WIDTH_PX }.sorted()
        if (widths.size < minimumSamples) return null
        val medianWidth = percentile(widths, 0.5)
        if (!medianWidth.isFinite() || medianWidth <= 0.0) return null

        val widthMad = robustMad(widths, medianWidth)
        val widthInstability = (widthMad / medianWidth).coerceIn(0.0, 1.0)
        val instantaneousResidual = pairwiseResidual(projectedMetricSpeeds, instantaneousSpeedMps)
        val trajectoryResidual = trajectoryFitResidual(regressionSamples, trajectorySpeedMps)
        val temporalResidual = ((instantaneousResidual + trajectoryResidual) * 0.5).coerceIn(0.0, 1.0)

        val durationConfidence = (
            (durationMs - minimumDurationMs).toDouble() /
                max(1.0, minimumSpeedDurationForConfidence(minimumDurationMs))
            ).coerceIn(0.0, 1.0)
        val sampleConfidence = (points.size.toDouble() / 30.0).coerceIn(0.0, 1.0)
        val trackConfidence = track.trackConfidence.toDouble().coerceIn(0.0, 1.0)
        val qualityConfidence = points.map { it.confidence }.average().coerceIn(0.0, 1.0)
        val speedStability = exp(-3.0 * temporalResidual).coerceIn(0.0, 1.0)
        val agreementConfidence = exp(-4.0 * agreementResidual.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val sizeStability = exp(-4.0 * widthInstability).coerceIn(0.0, 1.0)
        val confidence = (
            0.14 * durationConfidence +
                0.13 * sampleConfidence +
                0.16 * trackConfidence +
                0.13 * qualityConfidence +
                0.14 * directionConsistency +
                0.12 * speedStability +
                0.10 * agreementConfidence +
                0.08 * sizeStability
            ).toFloat().coerceIn(0.10f, 0.90f)

        val priorRelativeUncertainty = vehicleWidthPriorRelativeUncertainty(track.className)
        val motionUncertainty = min(0.45, 0.05 + temporalResidual * 0.25 + (1.0 - directionConsistency) * 0.20)
        val agreementUncertainty = min(0.30, agreementResidual * 0.40)
        val totalRelativeUncertainty = sqrt(
            priorRelativeUncertainty * priorRelativeUncertainty +
                motionUncertainty * motionUncertainty +
                widthInstability * widthInstability +
                agreementUncertainty * agreementUncertainty,
        ).coerceIn(0.08, 0.65)
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

    private fun trimTrackEdges(points: List<Point>, minimumSamples: Int): List<Point> {
        if (points.size < 12) return points
        val maxTrimBySampleBudget = max(0, (points.size - minimumSamples) / 2)
        val requestedTrim = (points.size * EDGE_TRIM_FRACTION).toInt()
        val trim = min(requestedTrim, maxTrimBySampleBudget)
        return if (trim <= 0 || points.size - trim * 2 < minimumSamples) points else points.subList(trim, points.size - trim)
    }

    private fun dominantMotionAxis(samples: List<MotionSample>): Pair<Double, Double> {
        val cxx = samples.sumOf { it.dxPx * it.dxPx }
        val cyy = samples.sumOf { it.dyPx * it.dyPx }
        val cxy = samples.sumOf { it.dxPx * it.dyPx }
        var axisX: Double
        var axisY: Double
        if (cxx.isFinite() && cyy.isFinite() && cxy.isFinite() && cxx + cyy > 1e-9) {
            val theta = 0.5 * atan2(2.0 * cxy, cxx - cyy)
            axisX = kotlin.math.cos(theta)
            axisY = kotlin.math.sin(theta)
        } else {
            val first = samples.first()
            val magnitude = hypot(first.dxPx, first.dyPx)
            axisX = first.dxPx / max(magnitude, 1e-9)
            axisY = first.dyPx / max(magnitude, 1e-9)
        }

        val axisNorm = hypot(axisX, axisY)
        axisX /= max(axisNorm, 1e-9)
        axisY /= max(axisNorm, 1e-9)

        val signedMotion = samples.sumOf { it.dxPx * axisX + it.dyPx * axisY }
        if (signedMotion < 0.0) {
            axisX = -axisX
            axisY = -axisY
        }
        return axisX to axisY
    }

    private fun cumulativeMetricTrajectory(
        points: List<Point>,
        motions: List<MotionSample>,
        axisX: Double,
        axisY: Double,
    ): List<MetricSample> {
        if (points.size < 2 || motions.isEmpty()) return emptyList()
        val result = ArrayList<MetricSample>(motions.size + 1)
        var cumulativeDistance = 0.0
        result += MetricSample(0.0, 0.0)
        for (i in motions.indices) {
            val motion = motions[i]
            val projectedRatio = abs(motion.dxPx * axisX + motion.dyPx * axisY) /
                max(1e-9, hypot(motion.dxPx, motion.dyPx))
            val projectedDistance = motion.metricDistanceM * projectedRatio.coerceIn(0.0, 1.0)
            if (!projectedDistance.isFinite() || projectedDistance <= 0.0) continue
            cumulativeDistance += projectedDistance
            val tSeconds = (points[min(i + 1, points.lastIndex)].tMs - points.first().tMs) / 1000.0
            if (tSeconds.isFinite() && tSeconds >= 0.0 && cumulativeDistance.isFinite()) {
                result += MetricSample(tSeconds, cumulativeDistance)
            }
        }
        return result
    }

    private fun downsample(samples: List<MetricSample>, maximum: Int): List<MetricSample> {
        if (samples.size <= maximum) return samples
        val step = (samples.size - 1).toDouble() / (maximum - 1).toDouble()
        return (0 until maximum).map { index -> samples[(index * step).roundToInt().coerceIn(0, samples.lastIndex)] }
    }

    private fun theilSenSlope(samples: List<MetricSample>): Double? {
        if (samples.size < 4) return null
        val slopes = ArrayList<Double>()
        for (i in 0 until samples.lastIndex) {
            for (j in i + 1..samples.lastIndex) {
                val dt = samples[j].tSeconds - samples[i].tSeconds
                if (dt <= 1e-6) continue
                val slope = (samples[j].distanceM - samples[i].distanceM) / dt
                if (slope.isFinite() && slope > 0.0) slopes += slope
            }
        }
        return robustMedian(slopes)
    }

    private fun trajectoryFitResidual(samples: List<MetricSample>, slope: Double): Double {
        if (samples.size < 3 || !slope.isFinite() || slope <= 0.0) return 1.0
        val first = samples.first()
        val residuals = samples.map { sample ->
            val expected = first.distanceM + slope * (sample.tSeconds - first.tSeconds)
            abs(sample.distanceM - expected) / max(0.5, abs(expected))
        }
        return percentile(residuals.sorted(), 0.5).coerceIn(0.0, 1.0)
    }

    private fun minimumSpeedDurationForConfidence(minimumDurationMs: Long): Double = max(1.0, minimumDurationMs.toDouble() * 3.0)

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

    private fun robustWeightedMedian(values: List<Double>, weights: List<Double>): Double? {
        if (values.isEmpty() || values.size != weights.size) return null
        val ranked = values.zip(weights).filter { it.first.isFinite() && it.first > 0.0 && it.second.isFinite() && it.second > 0.0 }
            .sortedBy { it.first }
        if (ranked.isEmpty()) return null
        val totalWeight = ranked.sumOf { it.second }
        if (!totalWeight.isFinite() || totalWeight <= 0.0) return null
        var cumulative = 0.0
        ranked.forEach { (value, weight) ->
            cumulative += weight
            if (cumulative >= totalWeight * 0.5) return value
        }
        return ranked.last().first
    }

    private fun robustMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.filter { it.isFinite() && it > 0.0 }.sorted()
        if (sorted.isEmpty()) return null
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

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
