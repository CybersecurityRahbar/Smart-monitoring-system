package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One canonical point in the metric vehicle template. */
data class VehicleMetricTemplatePoint(
    val name: String,
    val xMeters: Double,
    val yMeters: Double,
) {
    init {
        require(name.isNotBlank())
        require(xMeters.isFinite() && yMeters.isFinite())
    }
}

/**
 * Explicit 36-point metric template contract. The 2026 paper describes a 36-point sedan template,
 * but its figure is not published as a machine-readable coordinate table. Runtime therefore
 * requires the exact coordinates to be supplied and explicitly marked verified by the project.
 */
data class VehicleMetricTemplate36(
    val points: List<VehicleMetricTemplatePoint>,
    val planarFacets: List<List<String>>,
    val verified: Boolean,
    val source: String,
) {
    private val byName = points.associateBy { it.name }

    init {
        require(points.size == 36) { "A 36-point template must contain exactly 36 points" }
        require(byName.size == 36) { "Template point names must be unique" }
        require(planarFacets.isNotEmpty()) { "At least one planar facet is required" }
        require(source.isNotBlank())
        planarFacets.forEach { facet ->
            require(facet.distinct().size == facet.size) { "Facet names must be unique" }
            require(facet.size >= 4) { "Each facet needs at least 4 points" }
            require(facet.all { byName.containsKey(it) }) { "Facet references an unknown template point" }
            require(!areCollinear(facet.map { byName.getValue(it) })) { "Facet points are collinear" }
        }
    }

    fun point(name: String): VehicleMetricTemplatePoint? = byName[name]

    private fun areCollinear(facet: List<VehicleMetricTemplatePoint>): Boolean {
        if (facet.size < 3) return true
        val a = facet.first()
        var maxArea = 0.0
        for (i in 1 until facet.lastIndex) {
            for (j in i + 1 until facet.size) {
                val b = facet[i]
                val c = facet[j]
                maxArea = max(maxArea, abs(
                    (b.xMeters - a.xMeters) * (c.yMeters - a.yMeters) -
                        (b.yMeters - a.yMeters) * (c.xMeters - a.xMeters),
                ))
            }
        }
        return maxArea < 1e-9
    }
}

/**
 * Sparse 36-keypoint metric-speed backend following the paper's per-frame homography idea.
 *
 * For each consecutive observation pair, H_k maps the previous image keypoints to the canonical
 * metric facet. The same H_k is applied to the matched keypoints in the next image, producing
 * metric displacements that are divided by the authoritative frame interval. Robust aggregation
 * across semantic points rejects individual keypoint failures.
 */
object VehicleKeypointMetricSpeedEstimator {
    private const val MIN_KEYPOINT_CONFIDENCE = 0.25
    private const val HOMOGRAPHY_THRESHOLD_METERS = 0.08
    private const val MAX_INTERVAL_SECONDS = 0.60
    private const val KMH = 3.6

    fun estimate(
        track: Track,
        template: VehicleMetricTemplate36,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
        maximumObservationGapMs: Long,
    ): SpeedEstimate? {
        if (!template.verified || track.state != TrackState.CONFIRMED) return null
        if (track.observations.size < minimumSamples) return null

        val observations = track.observations
            .sortedWith(compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex })
        val durationMs = observations.last().timestampMs - observations.first().timestampMs
        if (durationMs < minimumDurationMs) return null

        val intervalSpeeds = mutableListOf<Double>()
        val intervalConfidences = mutableListOf<Double>()
        for (index in 1 until observations.size) {
            val previous = observations[index - 1]
            val current = observations[index]
            val deltaMs = current.timestampMs - previous.timestampMs
            val dt = deltaMs / 1000.0
            if (!dt.isFinite() || dt <= 0.0 || dt > MAX_INTERVAL_SECONDS || deltaMs > maximumObservationGapMs) continue

            val previousByName = previous.keypoints
                .filter { it.confidence >= MIN_KEYPOINT_CONFIDENCE }
                .associateBy { it.name }
            val currentByName = current.keypoints
                .filter { it.confidence >= MIN_KEYPOINT_CONFIDENCE }
                .associateBy { it.name }

            var bestSpeed: Double? = null
            var bestConfidence = 0.0
            for (facet in template.planarFacets) {
                val correspondences = facet.mapNotNull { name ->
                    val image = previousByName[name] ?: return@mapNotNull null
                    val metric = template.point(name) ?: return@mapNotNull null
                    VehicleTemplateCorrespondence(
                        keypointName = name,
                        imageX = image.x,
                        imageY = image.y,
                        templateX = metric.xMeters,
                        templateY = metric.yMeters,
                        confidence = image.confidence.toDouble(),
                    )
                }
                if (correspondences.size < 4) continue

                val fit = VehicleKeypointHomography.estimate(
                    correspondences = correspondences,
                    reprojectionThreshold = HOMOGRAPHY_THRESHOLD_METERS,
                    maxIterations = 80,
                    minimumInliers = 4,
                ) ?: continue

                val speeds = facet.mapNotNull { name ->
                    val a = previousByName[name] ?: return@mapNotNull null
                    val b = currentByName[name] ?: return@mapNotNull null
                    val pa = fit.project(a.x, a.y)
                    val pb = fit.project(b.x, b.y)
                    if (!pa.x.isFinite() || !pa.y.isFinite() || !pb.x.isFinite() || !pb.y.isFinite()) return@mapNotNull null
                    val speed = hypot(pb.x - pa.x, pb.y - pa.y) / dt
                    speed.takeIf { it.isFinite() && it >= 0.0 && it * KMH <= maxPlausibleSpeedKmh * 1.25 }
                }
                if (speeds.size < 3) continue

                val speed = median(speeds)
                val inlierRatio = fit.inlierMask.count { it }.toDouble() / fit.inlierMask.size.toDouble()
                val reprojectionConfidence = (1.0 / (1.0 + fit.medianReprojectionError / 0.04)).coerceIn(0.0, 1.0)
                val confidence = (inlierRatio * reprojectionConfidence * (speeds.size.toDouble() / facet.size)).coerceIn(0.0, 1.0)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestSpeed = speed
                }
            }

            bestSpeed?.let {
                intervalSpeeds += it
                intervalConfidences += bestConfidence
            }
        }

        if (intervalSpeeds.size < max(4, minimumSamples / 2)) return null
        val sorted = intervalSpeeds.sorted()
        val trim = if (sorted.size >= 10) (sorted.size * 0.10).toInt() else 0
        val stable = if (trim > 0 && sorted.size - 2 * trim >= 4) sorted.subList(trim, sorted.size - trim) else sorted
        val metersPerSecond = median(stable)
        val kmh = metersPerSecond * KMH
        if (!kmh.isFinite() || kmh < 0.0 || kmh > maxPlausibleSpeedKmh) return null

        val mad = median(stable.map { abs(it - metersPerSecond) })
        return SpeedEstimate(
            metersPerSecond = metersPerSecond,
            kilometersPerHour = kmh,
            confidence = median(intervalConfidences).toFloat().coerceIn(0f, 1f),
            sampleCount = intervalSpeeds.size,
            durationMs = durationMs,
            directionDegrees = imageMotionDirection(observations),
            errorKmh = (1.4826 * mad * KMH).coerceAtLeast(0.0),
            estimatorLabel = "36-keypoint dynamic homography (sparse semantic points)",
            mode = SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE,
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5 else sorted[middle]
    }

    private fun imageMotionDirection(observations: List<TrackObservation>): Double? {
        val values = observations.zipWithNext().mapNotNull { (a, b) ->
            val ax = a.keypoints.map { it.x }.average()
            val ay = a.keypoints.map { it.y }.average()
            val bx = b.keypoints.map { it.x }.average()
            val by = b.keypoints.map { it.y }.average()
            val dx = bx - ax
            val dy = by - ay
            if (hypot(dx, dy) < 1e-6) null else Math.toDegrees(atan2(dy, dx))
        }
        return values.takeIf { it.isNotEmpty() }?.let(::median)
    }
}
