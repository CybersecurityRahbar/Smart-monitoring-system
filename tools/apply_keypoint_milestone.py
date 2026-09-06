from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected patch anchor not found: {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


renderer = ROOT / "android/app/src/main/java/com/smarttraffic/app/features/analysis/AnalysisVideoPlayback.kt"
replace_once(renderer, '''            val trackColor = Color(0xFF39FF14)
''', '''            val trackColor = Color(0xFF39FF14)
            val renderTracks = preview.renderTracks
''')
replace_once(renderer, '''                visualGate(preview.tracks, sourceWidth.toDouble(), sourceHeight.toDouble())?.let { gate ->
''', '''                visualGate(renderTracks, sourceWidth.toDouble(), sourceHeight.toDouble())?.let { gate ->
''')
replace_once(renderer, '''            val timelineOriginMs = preview.tracks
                .asSequence()
                .flatMap { it.observations.asSequence() }
                .map { it.timestampMs }
                .minOrNull() ?: preview.frame.timestampMs
            val targetTimestampMs = safeAddTimestamp(timelineOriginMs, positionMs)

            preview.tracks.forEach { track ->
''', '''            // The recorded source timeline is authoritative. A vehicle entering the frame late
            // must never redefine t=0 for the overlay.
            val timelineOriginMs = preview.timelineStartTimestampMs ?: preview.frame.timestampMs
            val targetTimestampMs = safeAddTimestamp(timelineOriginMs, positionMs)

            renderTracks.forEach { track ->
''')
replace_once(renderer, '''        if (gapMs > 800L) return null
''', '''        // Rendering can bridge small decode/preview gaps, but never extrapolate indefinitely.
        if (gapMs > 350L) return null
''')
replace_once(renderer, '''    val horizon = dtSeconds.coerceIn(0.0, 0.80)
    val damping = (1.0 - 0.22 * horizon / 0.80).coerceIn(0.72, 1.0)
''', '''    val horizon = dtSeconds.coerceIn(0.0, 0.35)
    val damping = (1.0 - 0.22 * horizon / 0.35).coerceIn(0.72, 1.0)
''')

models = ROOT / "android/app/src/main/java/com/smarttraffic/app/domain/analysis/AnalysisModels.kt"
replace_once(models, '''    val useDynamicKeypointHomography: Boolean = false,
''', '''    val useDynamicKeypointHomography: Boolean = false,
    /** Explicit metric 36-keypoint template required for the dynamic keypoint speed path. */
    val vehicleMetricTemplate: VehicleMetricTemplate36? = null,
''')
replace_once(models, '''    val errorKmh: Double? = null,
    val mode: SpeedEstimateMode = SpeedEstimateMode.CALIBRATED_GROUND_PLANE,
''', '''    val errorKmh: Double? = null,
    /** Backend provenance for diagnostics; this must not be interpreted as certification. */
    val estimatorLabel: String? = null,
    val mode: SpeedEstimateMode = SpeedEstimateMode.CALIBRATED_GROUND_PLANE,
''')

estimator = ROOT / "android/app/src/main/java/com/smarttraffic/app/domain/analysis/VehicleKeypointMetricSpeedEstimator.kt"
if not estimator.exists():
    estimator.write_text(r'''package com.smarttraffic.app.domain.analysis

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
''', encoding="utf-8")

runner = ROOT / "android/app/src/main/java/com/smarttraffic/app/domain/analysis/AnalysisPipelineRunner.kt"
replace_once(runner, '''        require(!config.useDynamicKeypointHomography || keypointEstimator != null) { "Dynamic keypoint homography is enabled but no VehicleKeypointEstimator backend is installed" }
''', '''        require(!config.useDynamicKeypointHomography || keypointEstimator != null) { "Dynamic keypoint homography is enabled but no VehicleKeypointEstimator backend is installed" }
        require(!config.useDynamicKeypointHomography || config.vehicleMetricTemplate != null) { "Dynamic keypoint homography is enabled but no metric 36-keypoint template is configured" }
''')
old_live = '''                    } else if (config.enableCalibrationFreeSpeedEstimate) {
                        liveTracks.mapNotNull { liveTrack ->
                            if (speedRejectionReason(liveTrack, source, config, calibrationReady, requirePhysical = false) != null) return@mapNotNull null
                            CalibrationFreeSpeedEstimator.estimate(
                                track = liveTrack,
                                minimumSamples = config.minimumSpeedSamples,
                                minimumDurationMs = config.minimumTrackDurationMs,
                                maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                                maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                            )?.let { liveTrack.id to it }
                        }.toMap()
                    } else emptyMap()
'''
new_live = '''                    } else {
                        liveTracks.mapNotNull { liveTrack ->
                            if (speedRejectionReason(liveTrack, source, config, calibrationReady, requirePhysical = false) != null) return@mapNotNull null
                            val dynamic = if (config.useDynamicKeypointHomography) {
                                VehicleKeypointMetricSpeedEstimator.estimate(
                                    track = liveTrack,
                                    template = requireNotNull(config.vehicleMetricTemplate),
                                    minimumSamples = config.minimumSpeedSamples,
                                    minimumDurationMs = config.minimumTrackDurationMs,
                                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                                    maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                                )
                            } else null
                            val estimate = dynamic ?: if (config.enableCalibrationFreeSpeedEstimate) {
                                CalibrationFreeSpeedEstimator.estimate(
                                    track = liveTrack,
                                    minimumSamples = config.minimumSpeedSamples,
                                    minimumDurationMs = config.minimumTrackDurationMs,
                                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                                    maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                                )
                            } else null
                            estimate?.let { liveTrack.id to it }
                        }.toMap()
                    }
'''
replace_once(runner, old_live, new_live)
old_final = '''            val estimate = if (physicalSpeedAllowed) {
                speedGate?.takeIf { it.calibrated }?.let { SpeedGateEstimator.estimate(track, it) }
                    ?: speedEstimator.estimate(track.observations, config.minimumSpeedSamples, config.minimumTrackDurationMs, config.maxPlausibleSpeedKmh)
            } else if (config.enableCalibrationFreeSpeedEstimate && physicalRejection == null) {
                CalibrationFreeSpeedEstimator.estimate(
                    track = track,
                    minimumSamples = config.minimumSpeedSamples,
                    minimumDurationMs = config.minimumTrackDurationMs,
                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                    maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                )
            } else null
'''
new_final = '''            val estimate = if (physicalSpeedAllowed) {
                speedGate?.takeIf { it.calibrated }?.let { SpeedGateEstimator.estimate(track, it) }
                    ?: speedEstimator.estimate(track.observations, config.minimumSpeedSamples, config.minimumTrackDurationMs, config.maxPlausibleSpeedKmh)
            } else if (physicalRejection == null) {
                val dynamic = if (config.useDynamicKeypointHomography) {
                    VehicleKeypointMetricSpeedEstimator.estimate(
                        track = track,
                        template = requireNotNull(config.vehicleMetricTemplate),
                        minimumSamples = config.minimumSpeedSamples,
                        minimumDurationMs = config.minimumTrackDurationMs,
                        maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                        maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                    )
                } else null
                dynamic ?: if (config.enableCalibrationFreeSpeedEstimate) {
                    CalibrationFreeSpeedEstimator.estimate(
                        track = track,
                        minimumSamples = config.minimumSpeedSamples,
                        minimumDurationMs = config.minimumTrackDurationMs,
                        maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                        maximumObservationGapMs = config.maximumSpeedObservationGapMs,
                    )
                } else null
            } else null
'''
replace_once(runner, old_final, new_final)

# Add a deterministic integration unit test only once.
test = ROOT / "android/app/src/test/java/com/smarttraffic/app/domain/analysis/VehicleKeypointMetricSpeedEstimatorTest.kt"
if not test.exists():
    test.write_text(r'''package com.smarttraffic.app.domain.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleKeypointMetricSpeedEstimatorTest {
    private val template = VehicleMetricTemplate36(
        points = List(36) { i ->
            VehicleMetricTemplatePoint("kp$i", (i % 6).toDouble(), (i / 6).toDouble())
        },
        planarFacets = listOf((0 until 12).map { "kp$it" }),
        verified = true,
        source = "synthetic unit-test template",
    )

    @Test
    fun constantMetricMotionProduces36Kmh() {
        val observations = (0 until 10).map { frame ->
            TrackObservation(
                frameIndex = frame.toLong(),
                timestampMs = frame * 100L,
                detection = Detection(2, "car", 0.95f, 0f, 0f, 100f, 100f, frame.toLong(), frame * 100L),
                keypoints = List(36) { i ->
                    VehicleKeypoint("kp$i", (i % 6).toDouble() + frame, (i / 6).toDouble(), 0.99f)
                },
            )
        }
        val track = Track(1L, "car", observations, 0.95f, state = TrackState.CONFIRMED, hits = 10)

        val estimate = VehicleKeypointMetricSpeedEstimator.estimate(
            track = track,
            template = template,
            minimumSamples = 6,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )

        assertTrue(estimate != null)
        assertEquals(36.0, estimate!!.kilometersPerHour, 0.5)
    }

    @Test
    fun unverifiedTemplateFailsClosed() {
        val unverified = template.copy(verified = false)
        val estimate = VehicleKeypointMetricSpeedEstimator.estimate(
            track = Track(1L, "car", emptyList(), 0.9f),
            template = unverified,
            minimumSamples = 4,
            minimumDurationMs = 0L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )
        assertNull(estimate)
    }
}
''', encoding="utf-8")

print("keypoint milestone patch applied")
