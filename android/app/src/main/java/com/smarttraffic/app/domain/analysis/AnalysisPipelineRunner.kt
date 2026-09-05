package com.smarttraffic.app.domain.analysis

import java.util.ArrayDeque
import kotlin.math.min

/** Real frame-to-result coordinator. The caller owns the FrameSource lifecycle. */
class AnalysisPipelineRunner(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypointEstimator: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
    private val previewObserver: AnalysisPreviewObserver? = null,
    private val groundProjector: GroundProjector = KotlinGroundProjector,
    private val speedEstimator: SpeedEstimatorBackend = KotlinSpeedEstimatorBackend,
) {
    suspend fun run(source: FrameSource, config: AnalysisConfig): AnalysisResult {
        require(config.trackerInputMinimumConfidence in 0f..1f) { "trackerInputMinimumConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence in 0f..1f) { "minimumDetectionConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence >= config.trackerInputMinimumConfidence) { "minimumDetectionConfidence must not be below trackerInputMinimumConfidence" }
        require(config.minimumTrackDurationMs >= 0L) { "minimumTrackDurationMs must be >= 0" }
        require(config.minimumSpeedSamples >= 4) { "minimumSpeedSamples must be >= 4" }
        require(config.maxPlausibleSpeedKmh > 0.0) { "maxPlausibleSpeedKmh must be positive" }
        require(config.maxCalibrationReprojectionErrorPixels > 0.0) { "maxCalibrationReprojectionErrorPixels must be positive" }
        require(config.maxCalibrationReprojectionErrorTargetUnits > 0.0) { "maxCalibrationReprojectionErrorTargetUnits must be positive" }
        require(config.minimumCalibrationInlierRatio in 0.0..1.0) { "minimumCalibrationInlierRatio must be within [0,1]" }
        require(config.maxRetainedDetections >= 1) { "maxRetainedDetections must be >= 1" }
        require(config.maxTrackHistoryObservations >= 32) { "maxTrackHistoryObservations must be >= 32" }
        require(config.latencySampleWindow >= 64) { "latencySampleWindow must be >= 64" }
        require(config.maxPlateReadings >= 1) { "maxPlateReadings must be >= 1" }
        require(config.minimumTrackConfidenceForSpeed in 0f..1f) { "minimumTrackConfidenceForSpeed must be within [0,1]" }
        require(config.maximumSpeedObservationGapMs > 0L) { "maximumSpeedObservationGapMs must be > 0" }
        require(!config.useVehicleKeypoints || keypointEstimator != null) { "Vehicle keypoints are enabled but no VehicleKeypointEstimator backend is installed" }
        require(!config.enablePlateRecognition || plateRecognizer != null) { "Plate recognition is enabled but no PlateRecognizer backend is installed" }
        require(!config.useDynamicKeypointHomography || config.useVehicleKeypoints) { "Dynamic keypoint homography requires VehicleKeypoints" }
        require(!config.useDynamicKeypointHomography || keypointEstimator != null) { "Dynamic keypoint homography is enabled but no VehicleKeypointEstimator backend is installed" }
        require(!config.useOpticalFlowRefinement) { "Optical-flow refinement is not installed in the current runtime" }
        require(!config.useSegmentationRefinement) { "Segmentation refinement is not installed in the current runtime" }
        require(!config.useReIdentification) { "Learned appearance Re-ID is not installed; deterministic appearance association is available separately" }

        tracker.reset()
        val retainedDetections = ArrayDeque<Detection>(config.maxRetainedDetections)
        val trackBuffers = linkedMapOf<Long, MutableTrackBuffer>()
        val plateReadings = ArrayDeque<PlateReading>(config.maxPlateReadings)
        val inferenceSamples = ArrayDeque<Double>(config.latencySampleWindow)
        var frameCount = 0L
        var totalReportableDetections = 0L
        var trackingDetectionCount = 0L
        var droppedFrames = 0L
        var trackingAssociationMisses = 0L
        var peakActiveTracks = 0
        var lastActiveTracks = 0
        var previousFrameIndex: Long? = null
        var previousTimestampMs: Long? = null
        var calibrationChecked = false
        var calibrationReady = false
        var sourceReadTimeNs = 0L
        val analysisStartNs = System.nanoTime()

        while (true) {
            val sourceReadStartNs = System.nanoTime()
            val frame = source.nextFrame()
            sourceReadTimeNs += (System.nanoTime() - sourceReadStartNs).coerceAtLeast(0L)
            if (frame == null) break
            frameCount++

            previousFrameIndex?.let { previous ->
                require(frame.index > previous) { "Frame indices must increase strictly: previous=$previous current=${frame.index}" }
                if (frame.index > previous + 1L) droppedFrames += frame.index - previous - 1L
            }
            previousTimestampMs?.let { previous ->
                require(frame.timestampMs >= previous) { "Frame timestamps must be monotonic: previous=$previous current=${frame.timestampMs}" }
            }
            previousFrameIndex = frame.index
            previousTimestampMs = frame.timestampMs

            if (!calibrationChecked) {
                calibrationReady = config.useGroundPlane && calibrationAccepted(config, frame.width, frame.height)
                calibrationChecked = true
            }

            val inferenceStartNs = System.nanoTime()
            val rawDetections = try {
                detector.detect(frame.payload, frame.timestampMs, frame.index)
            } catch (error: Throwable) {
                throw IllegalStateException("Object detector failed at frame=${frame.index}, timestampMs=${frame.timestampMs}", error)
            }
            val inferenceLatencyMs = (System.nanoTime() - inferenceStartNs) / 1_000_000.0
            require(inferenceLatencyMs.isFinite() && inferenceLatencyMs >= 0.0) { "Measured detector latency is invalid at frame=${frame.index}" }
            inferenceSamples.addLast(inferenceLatencyMs)
            while (inferenceSamples.size > config.latencySampleWindow) inferenceSamples.removeFirst()

            val trackingDetections = rawDetections.filter { it.confidence.isFinite() && it.confidence in config.trackerInputMinimumConfidence..1f }
            trackingDetectionCount += trackingDetections.size.toLong()
            val reportableDetections = trackingDetections.filter { it.confidence >= config.minimumDetectionConfidence }
            totalReportableDetections += reportableDetections.size.toLong()
            reportableDetections.forEach { detection ->
                retainedDetections.addLast(detection)
                if (retainedDetections.size > config.maxRetainedDetections) retainedDetections.removeFirst()
            }

            val tracks = tracker.update(trackingDetections, frame.index, frame.timestampMs)
            lastActiveTracks = tracks.size
            peakActiveTracks = maxOf(peakActiveTracks, tracks.size)
            for (track in tracks) {
                val observation = track.observations.lastOrNull { it.frameIndex == frame.index } ?: run {
                    trackingAssociationMisses++
                    continue
                }

                val keypoints = if (config.useVehicleKeypoints) keypointEstimator!!.estimate(frame.payload, observation.detection) else emptyList()
                val contact = selectContactPoint(observation.detection, keypoints)
                val ground = if (calibrationReady) {
                    val calibration = requireNotNull(config.calibration) { "Calibration was accepted without a calibration profile" }
                    runCatching { groundProjector.project(calibration, contact.first, contact.second) }
                        .getOrElse { error -> throw IllegalStateException("Ground-plane projection failed at frame=${frame.index}, track=${track.id}", error) }
                } else null

                val enriched = observation.copy(groundPoint = ground, keypoints = keypoints)
                val buffer = trackBuffers.getOrPut(track.id) { MutableTrackBuffer(track.id, track.className, config.maxTrackHistoryObservations) }
                buffer.wasOccluded = buffer.wasOccluded || track.wasOccluded
                buffer.hits = maxOf(buffer.hits, track.hits)
                buffer.misses = maxOf(buffer.misses, track.misses)
                buffer.ageFrames = maxOf(buffer.ageFrames, track.ageFrames)
                buffer.lastTimestampMs = maxOf(buffer.lastTimestampMs, track.lastTimestampMs)
                buffer.state = track.state
                buffer.confidenceSamples.addLast(track.trackConfidence.toDouble())
                while (buffer.confidenceSamples.size > config.maxTrackHistoryObservations) buffer.confidenceSamples.removeFirst()
                buffer.observations.addLast(enriched)
                while (buffer.observations.size > config.maxTrackHistoryObservations) buffer.observations.removeFirst()

                if (config.enablePlateRecognition) {
                    plateRecognizer!!.recognize(frame.payload, observation.detection)?.let { reading ->
                        plateReadings.addLast(reading.copy(trackId = reading.trackId ?: track.id, timestampMs = frame.timestampMs))
                        if (plateReadings.size > config.maxPlateReadings) plateReadings.removeFirst()
                    }
                }
            }

            if (previewObserver != null) {
                val bitmap = frame.payload as? android.graphics.Bitmap
                if (bitmap != null) {
                    val liveTracks = tracks.mapNotNull { track ->
                        val buffer = trackBuffers[track.id] ?: return@mapNotNull null
                        Track(
                            id = buffer.id,
                            className = buffer.className,
                            observations = buffer.observations.toList(),
                            trackConfidence = buffer.confidenceSamples.average().toFloat().coerceIn(0f, 1f),
                            wasOccluded = buffer.wasOccluded,
                            state = track.state,
                            hits = track.hits,
                            misses = track.misses,
                            ageFrames = track.ageFrames,
                            lastTimestampMs = track.lastTimestampMs,
                        )
                    }
                    val liveSpeedAllowed = physicalSpeedAllowed(source, config, calibrationReady)
                    val liveSpeeds = if (liveSpeedAllowed) {
                        liveTracks.mapNotNull { liveTrack ->
                            if (speedRejectionReason(liveTrack, source, config, calibrationReady) != null) return@mapNotNull null
                            speedEstimator.estimate(liveTrack.observations, config.minimumSpeedSamples, config.minimumTrackDurationMs, config.maxPlausibleSpeedKmh)
                                ?.let { liveTrack.id to it }
                        }.toMap()
                    } else emptyMap()
                    previewObserver.onFrame(AnalysisPreviewFrame(frame, bitmap, reportableDetections, liveTracks, liveSpeeds, liveSpeedAllowed))
                }
            }
        }

        val completedTracks = trackBuffers.values.map { buffer ->
            val averageConfidence = if (buffer.confidenceSamples.isEmpty()) 0.0 else buffer.confidenceSamples.average()
            Track(
                id = buffer.id,
                className = buffer.className,
                observations = buffer.observations.toList().sortedWith(compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex }),
                trackConfidence = averageConfidence.toFloat().coerceIn(0f, 1f),
                wasOccluded = buffer.wasOccluded,
                state = buffer.state,
                hits = buffer.hits,
                misses = buffer.misses,
                ageFrames = buffer.ageFrames,
                lastTimestampMs = buffer.lastTimestampMs,
            )
        }

        val physicalSpeedAllowed = physicalSpeedAllowed(source, config, calibrationReady)
        val speedEstimates = linkedMapOf<Long, SpeedEstimate>()
        val speedRejections = linkedMapOf<Long, SpeedRejectionReason>()
        for (track in completedTracks) {
            val rejection = speedRejectionReason(track, source, config, calibrationReady)
            if (rejection != null) {
                speedRejections[track.id] = rejection
                continue
            }
            if (!physicalSpeedAllowed) {
                speedRejections[track.id] = if (!calibrationReady) SpeedRejectionReason.CALIBRATION_INVALID else SpeedRejectionReason.TIMESTAMP_INVALID
                continue
            }
            val estimate = speedEstimator.estimate(track.observations, config.minimumSpeedSamples, config.minimumTrackDurationMs, config.maxPlausibleSpeedKmh)
            if (estimate != null) speedEstimates[track.id] = estimate
            else speedRejections[track.id] = SpeedRejectionReason.ROBUST_ESTIMATOR_REJECTION
        }

        val trafficEvents = if (config.enableRules) {
            TrafficRuleEngine.evaluate(
                tracks = completedTracks,
                speedEstimates = speedEstimates,
                config = config.trafficRules.copy(enabled = true),
                detectorModel = config.detectorModel,
                tracker = config.tracker,
                calibration = config.calibration,
            )
        } else emptyList()

        val elapsedMs = (System.nanoTime() - analysisStartNs) / 1_000_000.0
        val elapsedSeconds = elapsedMs / 1000.0
        val decodedSeconds = sourceReadTimeNs / 1_000_000_000.0
        val measuredDecodeFps = frameCount.takeIf { it > 0L && decodedSeconds > 0.0 }?.let { it / decodedSeconds }
        val processingFps = frameCount.takeIf { it > 0L && elapsedSeconds > 0.0 }?.let { it / elapsedSeconds }
        val e2ePerFrameMs = frameCount.takeIf { it > 0L }?.let { elapsedMs / it }
        val sortedInference = inferenceSamples.toList().sorted()
        val inferenceMedian = percentile(sortedInference, 0.50)
        val inferenceP95 = percentile(sortedInference, 0.95)
        val totalDroppedFrames = droppedFrames + source.droppedFrameCount

        return AnalysisResult(
            source = source.source,
            detections = retainedDetections.toList(),
            tracks = completedTracks,
            speedEstimates = speedEstimates,
            speedRejectionReasons = speedRejections,
            plateReadings = PlateConsensus.resolve(plateReadings.toList()),
            trafficEvents = trafficEvents,
            metrics = AnalysisMetrics(
                decodeFps = measuredDecodeFps,
                sourceNominalFps = source.source.frameRate,
                timestampPrecision = source.source.timestampPrecision,
                inferenceLatencyMs = inferenceMedian,
                inferenceMedianLatencyMs = inferenceMedian,
                inferenceP95LatencyMs = inferenceP95,
                endToEndLatencyMs = e2ePerFrameMs,
                totalProcessingTimeMs = elapsedMs,
                processingFps = processingFps,
                droppedFrames = totalDroppedFrames,
                framesProcessed = frameCount,
                trackingDetections = trackingDetectionCount,
                detections = totalReportableDetections,
                inferenceFailures = 0,
                trackingAssociationMisses = trackingAssociationMisses,
                activeTracks = lastActiveTracks,
                peakActiveTracks = peakActiveTracks,
                completedTracks = completedTracks.size.toLong(),
                speedEstimates = speedEstimates.size.toLong(),
                rejectedSpeedEstimates = speedRejections.size.toLong(),
                plateReads = plateReadings.size.toLong(),
                trafficEvents = trafficEvents.size.toLong(),
                homographyReprojectionError = config.calibration?.reprojectionErrorPixels ?: config.calibration?.reprojectionErrorTargetUnits,
                speedEstimatorBackend = speedEstimator.name,
            ),
        )
    }

    private fun speedRejectionReason(
        track: Track,
        source: FrameSource,
        config: AnalysisConfig,
        calibrationReady: Boolean,
    ): SpeedRejectionReason? {
        if (!calibrationReady) return SpeedRejectionReason.CALIBRATION_INVALID
        if (config.requireExactTimestampsForPhysicalSpeed && source.source.timestampPrecision != FrameTimestampPrecision.EXACT_SOURCE_CLOCK) {
            return SpeedRejectionReason.TIMESTAMP_INVALID
        }
        if (track.state != TrackState.CONFIRMED) return SpeedRejectionReason.TRACK_QUALITY_LOW
        if (track.trackConfidence < config.minimumTrackConfidenceForSpeed) return SpeedRejectionReason.TRACK_QUALITY_LOW
        val usable = track.observations.filter { it.groundPoint != null }.sortedBy { it.timestampMs }
        if (usable.size < config.minimumSpeedSamples) return SpeedRejectionReason.INSUFFICIENT_OBSERVATIONS
        val duration = usable.last().timestampMs - usable.first().timestampMs
        if (duration < config.minimumTrackDurationMs) return SpeedRejectionReason.INSUFFICIENT_DURATION
        if (usable.any { it.groundPoint?.xMeters?.isFinite() != true || it.groundPoint.yMeters.isFinite() != true }) {
            return SpeedRejectionReason.GROUND_GEOMETRY_INCOMPLETE
        }
        val maxGap = usable.zipWithNext().maxOfOrNull { it.second.timestampMs - it.first.timestampMs } ?: Long.MAX_VALUE
        if (maxGap > config.maximumSpeedObservationGapMs) return SpeedRejectionReason.DISCONTINUOUS_TRACK
        return null
    }

    private fun physicalSpeedAllowed(source: FrameSource, config: AnalysisConfig, calibrationReady: Boolean): Boolean =
        calibrationReady && (!config.requireExactTimestampsForPhysicalSpeed || source.source.timestampPrecision == FrameTimestampPrecision.EXACT_SOURCE_CLOCK)

    private fun calibrationAccepted(config: AnalysisConfig, sourceWidth: Int, sourceHeight: Int): Boolean {
        val calibration = config.calibration ?: return false
        if (!config.requireValidatedCalibration) return true
        val validation = CalibrationValidator.validate(
            calibration,
            maxReprojectionErrorPixels = config.maxCalibrationReprojectionErrorPixels,
            maxReprojectionErrorTargetUnits = config.maxCalibrationReprojectionErrorTargetUnits,
            minimumInlierRatio = config.minimumCalibrationInlierRatio,
        )
        return validation.accepted && calibration.imageWidth == sourceWidth && calibration.imageHeight == sourceHeight
    }

    private fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex
        val lower = position.toInt()
        val upper = min(sorted.lastIndex, lower + 1)
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun selectContactPoint(detection: Detection, keypoints: List<VehicleKeypoint>): Pair<Double, Double> {
        val learned = keypoints.filter { it.confidence >= 0.50f && it.x.isFinite() && it.y.isFinite() }
            .firstOrNull { it.name.lowercase() in setOf("ground_contact", "contact", "footprint", "rear_contact", "front_contact") }
        return if (learned != null) learned.x to learned.y else ((detection.left + detection.right) / 2.0) to detection.bottom.toDouble()
    }

    private data class MutableTrackBuffer(
        val id: Long,
        val className: String,
        val maxObservations: Int,
        val observations: ArrayDeque<TrackObservation> = ArrayDeque(maxObservations),
        val confidenceSamples: ArrayDeque<Double> = ArrayDeque(maxObservations),
        var hits: Int = 0,
        var misses: Int = 0,
        var ageFrames: Int = 0,
        var lastTimestampMs: Long = 0L,
        var wasOccluded: Boolean = false,
        var state: TrackState = TrackState.CONFIRMED,
    )
}
