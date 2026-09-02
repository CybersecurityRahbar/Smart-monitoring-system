package com.smarttraffic.app.domain.analysis

/**
 * Real frame-to-result coordinator. Every tracker result must carry its own current
 * observation; the runner deliberately does not fall back to an unrelated detection.
 */
class AnalysisPipelineRunner(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypointEstimator: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
) {
    suspend fun run(source: FrameSource, config: AnalysisConfig): AnalysisResult {
        require(config.trackerInputMinimumConfidence in 0f..1f) { "trackerInputMinimumConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence in 0f..1f) { "minimumDetectionConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence >= config.trackerInputMinimumConfidence) {
            "minimumDetectionConfidence must not be below trackerInputMinimumConfidence"
        }
        require(config.maxPlausibleSpeedKmh > 0.0) { "maxPlausibleSpeedKmh must be positive" }
        require(config.maxCalibrationReprojectionErrorPixels > 0.0) { "maxCalibrationReprojectionErrorPixels must be positive" }
        require(config.minimumCalibrationInlierRatio in 0.0..1.0) { "minimumCalibrationInlierRatio must be within [0,1]" }

        tracker.reset()
        val allDetections = mutableListOf<Detection>()
        val trackBuffers = linkedMapOf<Long, MutableTrackBuffer>()
        val plateReadings = mutableListOf<PlateReading>()
        val inferenceSamples = mutableListOf<Double>()
        var frameCount = 0L
        var trackingDetectionCount = 0L
        var droppedFrames = 0L
        var trackingAssociationMisses = 0L
        var peakActiveTracks = 0
        var previousFrameIndex: Long? = null
        var firstTimestamp = Long.MAX_VALUE
        var lastTimestamp = Long.MIN_VALUE
        val analysisStartNs = System.nanoTime()

        try {
            while (true) {
                val frame = source.nextFrame() ?: break
                frameCount++
                firstTimestamp = minOf(firstTimestamp, frame.timestampMs)
                lastTimestamp = maxOf(lastTimestamp, frame.timestampMs)

                previousFrameIndex?.let { previous ->
                    if (frame.index > previous + 1L) droppedFrames += frame.index - previous - 1L
                }
                previousFrameIndex = frame.index

                val inferenceStartNs = System.nanoTime()
                val rawDetections = detector.detect(frame.payload, frame.timestampMs, frame.index)
                val inferenceLatencyMs = (System.nanoTime() - inferenceStartNs) / 1_000_000.0
                if (inferenceLatencyMs.isFinite() && inferenceLatencyMs >= 0.0) inferenceSamples += inferenceLatencyMs

                val trackingDetections = rawDetections.filter {
                    it.confidence.isFinite() && it.confidence >= config.trackerInputMinimumConfidence
                }
                trackingDetectionCount += trackingDetections.size.toLong()
                val reportableDetections = trackingDetections.filter {
                    it.confidence >= config.minimumDetectionConfidence
                }
                allDetections += reportableDetections

                val tracks = tracker.update(trackingDetections, frame.index, frame.timestampMs)
                peakActiveTracks = maxOf(peakActiveTracks, tracks.size)
                for (track in tracks) {
                    val observation = track.observations.lastOrNull { it.frameIndex == frame.index }
                    if (observation == null) {
                        trackingAssociationMisses++
                        continue
                    }

                    val keypoints = if (config.useVehicleKeypoints && keypointEstimator != null) {
                        keypointEstimator.estimate(frame.payload, observation.detection)
                    } else emptyList()

                    val contact = selectContactPoint(observation.detection, keypoints)
                    val ground = if (config.useGroundPlane && calibrationAccepted(config)) {
                        runCatching {
                            HomographyProjector(config.calibration!!.homography)
                                .project(contact.first, contact.second)
                        }.getOrNull()
                    } else null

                    val enriched = observation.copy(groundPoint = ground, keypoints = keypoints)
                    val buffer = trackBuffers.getOrPut(track.id) {
                        MutableTrackBuffer(track.id, track.className)
                    }
                    buffer.wasOccluded = buffer.wasOccluded || track.wasOccluded
                    buffer.confidenceSamples += track.trackConfidence.toDouble()
                    buffer.observations += enriched

                    if (config.enablePlateRecognition && plateRecognizer != null) {
                        plateRecognizer.recognize(frame.payload, observation.detection)?.let(plateReadings::add)
                    }
                }
            }
        } finally {
            source.close()
        }

        val completedTracks = trackBuffers.values.map { buffer ->
            val averageConfidence = if (buffer.confidenceSamples.isEmpty()) 0.0
            else buffer.confidenceSamples.average()
            Track(
                id = buffer.id,
                className = buffer.className,
                observations = buffer.observations.sortedWith(
                    compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex },
                ),
                trackConfidence = averageConfidence.toFloat().coerceIn(0f, 1f),
                wasOccluded = buffer.wasOccluded,
            )
        }

        val calibrationReady = calibrationAccepted(config)
        val speedEstimates = if (config.useGroundPlane && calibrationReady) {
            completedTracks.mapNotNull { track ->
                RobustSpeedEstimator(
                    minimumSamples = config.minimumSpeedSamples,
                    minimumDurationMs = config.minimumTrackDurationMs,
                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                ).estimate(track.observations)?.let { speed -> track.id to speed }
            }.toMap()
        } else emptyMap()

        val elapsedMs = (System.nanoTime() - analysisStartNs) / 1_000_000.0
        val elapsedSeconds = elapsedMs / 1000.0
        val processingFps = frameCount.takeIf { it > 0L && elapsedSeconds > 0.0 }
            ?.let { it / elapsedSeconds }
        val e2ePerFrameMs = frameCount.takeIf { it > 0L }?.let { elapsedMs / it }

        val sortedInference = inferenceSamples.sorted()
        val inferenceMedian = percentile(sortedInference, 0.50)
        val inferenceP95 = percentile(sortedInference, 0.95)

        return AnalysisResult(
            source = source.source,
            detections = allDetections,
            tracks = completedTracks,
            speedEstimates = speedEstimates,
            plateReadings = temporalPlateConsensus(plateReadings),
            metrics = AnalysisMetrics(
                inferenceLatencyMs = inferenceMedian,
                inferenceMedianLatencyMs = inferenceMedian,
                inferenceP95LatencyMs = inferenceP95,
                endToEndLatencyMs = e2ePerFrameMs,
                totalProcessingTimeMs = elapsedMs,
                processingFps = processingFps,
                droppedFrames = droppedFrames,
                framesProcessed = frameCount,
                trackingDetections = trackingDetectionCount,
                detections = allDetections.size.toLong(),
                inferenceFailures = 0,
                trackingAssociationMisses = trackingAssociationMisses,
                activeTracks = 0,
                peakActiveTracks = peakActiveTracks,
                completedTracks = completedTracks.size.toLong(),
                speedEstimates = speedEstimates.size.toLong(),
                rejectedSpeedEstimates = (completedTracks.size - speedEstimates.size).toLong().coerceAtLeast(0L),
                plateReads = plateReadings.size.toLong(),
                homographyReprojectionError = config.calibration?.reprojectionErrorPixels,
            ),
        )
    }

    private fun calibrationAccepted(config: AnalysisConfig): Boolean {
        val calibration = config.calibration ?: return !config.requireValidatedCalibration
        if (!config.requireValidatedCalibration) return true
        val reprojection = calibration.reprojectionErrorPixels ?: return false
        val inlierRatio = calibration.homographyInlierRatio ?: return false
        return reprojection.isFinite() && reprojection <= config.maxCalibrationReprojectionErrorPixels &&
            inlierRatio.isFinite() && inlierRatio >= config.minimumCalibrationInlierRatio
    }

    private fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex
        val lower = position.toInt()
        val upper = minOf(sorted.lastIndex, lower + 1)
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun selectContactPoint(
        detection: Detection,
        keypoints: List<VehicleKeypoint>,
    ): Pair<Double, Double> {
        val learned = keypoints
            .filter { it.confidence >= 0.50f && it.x.isFinite() && it.y.isFinite() }
            .firstOrNull {
                it.name.lowercase() in setOf(
                    "ground_contact", "contact", "footprint", "rear_contact", "front_contact",
                )
            }
        return if (learned != null) learned.x to learned.y
        else ((detection.left + detection.right) / 2.0) to detection.bottom.toDouble()
    }

    private fun temporalPlateConsensus(readings: List<PlateReading>): List<PlateReading> =
        readings.groupBy { normalizePlate(it.text) }
            .values
            .map { group -> group.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }

    private fun normalizePlate(text: String): String =
        text.uppercase().replace(" ", "").replace("-", "")

    private data class MutableTrackBuffer(
        val id: Long,
        val className: String,
        val observations: MutableList<TrackObservation> = mutableListOf(),
        val confidenceSamples: MutableList<Double> = mutableListOf(),
        var wasOccluded: Boolean = false,
    )
}
