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
    private val sampleIntervalPolicy: FrameSamplingPolicy = FrameSamplingPolicy(),
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
        var decodeStartNs = System.nanoTime()

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
                val currentTrackIds = tracks.mapTo(hashSetOf()) { it.id }

                if (config.useReIdentification.not() && tracks.any { it.wasOccluded }) {
                    // Re-ID is optional; the tracker remains authoritative for identity.
                    droppedFrames += 0L
                }

                for (track in tracks) {
                    val observation = currentObservation(track, detections, frame.index, frame.timestampMs)
                        ?: continue
                    val keypoints = if (config.useVehicleKeypoints && keypointEstimator != null) {
                        keypointEstimator.estimate(frame.payload, observation.detection)
                    } else {
                        emptyList()
                    }

                    val contact = selectContactPoint(observation.detection, keypoints)
                    val ground = if (config.useGroundPlane && config.calibration != null) {
                        runCatching {
                            HomographyProjector(config.calibration.homography).project(contact.first, contact.second)
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

                if (currentTrackIds.isEmpty() && detections.isNotEmpty()) {
                    // Detections without a maintained identity are intentionally not promoted to speed events.
                    droppedFrames += 0L
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
                observations = buffer.observations.toList(),
                trackConfidence = averageConfidence.toFloat().coerceIn(0f, 1f),
                wasOccluded = buffer.wasOccluded,
            )
        }

        val speedEstimates = if (config.useGroundPlane) {
            completedTracks.mapNotNull { track ->
                estimateSpeed(track, config)?.let(track.id::let to it)
            }.toMap()
        } else emptyMap()

        val elapsedSeconds = (System.nanoTime() - decodeStartNs) / 1_000_000_000.0
        val mediaSeconds = if (firstTimestamp != Long.MAX_VALUE && lastTimestamp >= firstTimestamp) {
            (lastTimestamp - firstTimestamp) / 1000.0
        } else 0.0

        return AnalysisResult(
            source = source.source,
            detections = allDetections,
            tracks = completedTracks,
            speedEstimates = speedEstimates,
            plateReadings = temporalPlateConsensus(plateReadings),
            metrics = AnalysisMetrics(
                decodeFps = frameCount.takeIf { it > 0 && elapsedSeconds > 0.0 }?.div(elapsedSeconds),
                endToEndLatencyMs = (elapsedSeconds * 1000.0).takeIf { it > 0.0 },
                droppedFrames = droppedFrames,
                detections = allDetections.size.toLong(),
                activeTracks = 0,
                completedTracks = completedTracks.size.toLong(),
                speedEstimates = speedEstimates.size.toLong(),
                plateReads = plateReadings.size.toLong(),
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
            .firstOrNull { it.name.lowercase() in setOf("ground_contact", "contact", "footprint", "rear_contact", "front_contact") }
        return if (learned != null) learned.x to learned.y
        else ((detection.left + detection.right) / 2.0) to detection.bottom.toDouble()
    }

    private fun temporalPlateConsensus(readings: List<PlateReading>): List<PlateReading> {
        if (readings.size < 2) return readings
        return readings.groupBy { normalizePlate(it.text) }
            .values
            .map { group -> group.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
    }

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

data class FrameSamplingPolicy(
    val sampleIntervalMs: Long = 100L,
)
