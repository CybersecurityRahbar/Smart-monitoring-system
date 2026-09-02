package com.smarttraffic.app.domain.analysis

/**
 * Real frame-to-result coordinator. ML implementations are injected, so the
 * same runner can execute on local files or the live ESP32 source.
 */
class AnalysisPipelineRunner(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypointEstimator: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
) {
    suspend fun run(source: FrameSource, config: AnalysisConfig): AnalysisResult {
        tracker.reset()
        val allDetections = mutableListOf<Detection>()
        val trackBuffers = linkedMapOf<Long, MutableTrackBuffer>()
        val plateReadings = mutableListOf<PlateReading>()
        var frameCount = 0L
        var droppedFrames = 0L
        var firstTimestamp = Long.MAX_VALUE
        var lastTimestamp = Long.MIN_VALUE
        val analysisStartNs = System.nanoTime()

        try {
            while (true) {
                val frame = source.nextFrame() ?: break
                frameCount++
                firstTimestamp = minOf(firstTimestamp, frame.timestampMs)
                lastTimestamp = maxOf(lastTimestamp, frame.timestampMs)

                val detections = detector.detect(frame.payload, frame.timestampMs, frame.index)
                    .filter { it.confidence >= config.minimumDetectionConfidence }
                allDetections += detections

                val tracks = tracker.update(detections, frame.index, frame.timestampMs)
                for (track in tracks) {
                    val observation = currentObservation(track, detections, frame.index, frame.timestampMs)
                        ?: continue
                    val keypoints = if (config.useVehicleKeypoints && keypointEstimator != null) {
                        keypointEstimator.estimate(frame.payload, observation.detection)
                    } else emptyList()

                    val contact = selectContactPoint(observation.detection, keypoints)
                    val ground = if (config.useGroundPlane && config.calibration != null) {
                        runCatching {
                            HomographyProjector(config.calibration.homography)
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

                if (tracks.isEmpty() && detections.isNotEmpty()) droppedFrames++
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
                observations = buffer.observations.toList(),
                trackConfidence = averageConfidence.toFloat().coerceIn(0f, 1f),
                wasOccluded = buffer.wasOccluded,
            )
        }

        val speedEstimates = if (config.useGroundPlane && config.calibration != null) {
            completedTracks.mapNotNull { track ->
                estimateSpeed(track, config)?.let { speed -> track.id to speed }
            }.toMap()
        } else emptyMap()

        val elapsedSeconds = (System.nanoTime() - analysisStartNs) / 1_000_000_000.0
        val decodeFps = frameCount.takeIf { it > 0L && elapsedSeconds > 0.0 }
            ?.let { it / elapsedSeconds }
        val homographyError = config.calibration?.reprojectionErrorPixels

        return AnalysisResult(
            source = source.source,
            detections = allDetections,
            tracks = completedTracks,
            speedEstimates = speedEstimates,
            plateReadings = temporalPlateConsensus(plateReadings),
            metrics = AnalysisMetrics(
                decodeFps = decodeFps,
                endToEndLatencyMs = elapsedSeconds * 1000.0,
                droppedFrames = droppedFrames,
                detections = allDetections.size.toLong(),
                activeTracks = 0,
                completedTracks = completedTracks.size.toLong(),
                speedEstimates = speedEstimates.size.toLong(),
                plateReads = plateReadings.size.toLong(),
                homographyReprojectionError = homographyError,
            ),
        )
    }

    private fun estimateSpeed(track: Track, config: AnalysisConfig): SpeedEstimate? =
        RobustSpeedEstimator(
            minimumSamples = config.minimumSpeedSamples,
            minimumDurationMs = config.minimumTrackDurationMs,
        ).estimate(track.observations)

    private fun currentObservation(
        track: Track,
        detections: List<Detection>,
        frameIndex: Long,
        timestampMs: Long,
    ): TrackObservation? {
        track.observations.lastOrNull { it.frameIndex == frameIndex }?.let { return it }
        val detection = detections.maxByOrNull { it.confidence } ?: return null
        return TrackObservation(frameIndex, timestampMs, detection)
    }

    private fun selectContactPoint(
        detection: Detection,
        keypoints: List<VehicleKeypoint>,
    ): Pair<Double, Double> {
        val learned = keypoints
            .filter { it.confidence >= 0.50f }
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
