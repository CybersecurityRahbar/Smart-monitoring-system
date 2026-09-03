package com.smarttraffic.app.domain.analysis

import kotlin.math.min

/** Real frame-to-result coordinator. */
class AnalysisPipelineRunner(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypointEstimator: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
    private val previewObserver: AnalysisPreviewObserver? = null,
) {
    suspend fun run(source: FrameSource, config: AnalysisConfig): AnalysisResult {
        require(config.trackerInputMinimumConfidence in 0f..1f) { "trackerInputMinimumConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence in 0f..1f) { "minimumDetectionConfidence must be within [0,1]" }
        require(config.minimumDetectionConfidence >= config.trackerInputMinimumConfidence) {
            "minimumDetectionConfidence must not be below trackerInputMinimumConfidence"
        }
        require(config.minimumTrackDurationMs >= 0L) { "minimumTrackDurationMs must be >= 0" }
        require(config.minimumSpeedSamples >= 4) { "minimumSpeedSamples must be >= 4" }
        require(config.maxPlausibleSpeedKmh > 0.0) { "maxPlausibleSpeedKmh must be positive" }
        require(config.maxCalibrationReprojectionErrorPixels > 0.0) { "maxCalibrationReprojectionErrorPixels must be positive" }
        require(config.maxCalibrationReprojectionErrorTargetUnits > 0.0) { "maxCalibrationReprojectionErrorTargetUnits must be positive" }
        require(config.minimumCalibrationInlierRatio in 0.0..1.0) { "minimumCalibrationInlierRatio must be within [0,1]" }
        require(!config.useVehicleKeypoints || keypointEstimator != null) {
            "Vehicle keypoints are enabled but no VehicleKeypointEstimator backend is installed"
        }
        require(!config.enablePlateRecognition || plateRecognizer != null) {
            "Plate recognition is enabled but no PlateRecognizer backend is installed"
        }
        require(!config.useDynamicKeypointHomography || config.useVehicleKeypoints) {
            "Dynamic keypoint homography requires VehicleKeypoints"
        }
        require(!config.useDynamicKeypointHomography || keypointEstimator != null) {
            "Dynamic keypoint homography is enabled but no VehicleKeypointEstimator backend is installed"
        }
        require(!config.useOpticalFlowRefinement) {
            "Optical-flow refinement is not installed in the current runtime"
        }
        require(!config.useSegmentationRefinement) {
            "Segmentation refinement is not installed in the current runtime"
        }
        require(!config.useReIdentification) {
            "Learned appearance Re-ID is not installed; deterministic appearance association is available separately"
        }

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
        var lastActiveTracks = 0
        var previousFrameIndex: Long? = null
        var calibrationChecked = false
        var calibrationReady = false
        val analysisStartNs = System.nanoTime()

        try {
            while (true) {
                val frame = source.nextFrame() ?: break
                frameCount++

                previousFrameIndex?.let { previous ->
                    require(frame.index > previous) {
                        "Frame indices must increase strictly: previous=$previous current=${frame.index}"
                    }
                    if (frame.index > previous + 1L) {
                        droppedFrames += frame.index - previous - 1L
                    }
                }
                previousFrameIndex = frame.index

                if (!calibrationChecked) {
                    calibrationReady = config.useGroundPlane && calibrationAccepted(
                        config = config,
                        sourceWidth = frame.width,
                        sourceHeight = frame.height,
                    )
                    calibrationChecked = true
                }

                val inferenceStartNs = System.nanoTime()
                val rawDetections = try {
                    detector.detect(frame.payload, frame.timestampMs, frame.index)
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "Object detector failed at frame=${frame.index}, timestampMs=${frame.timestampMs}",
                        error,
                    )
                }
                val inferenceLatencyMs = (System.nanoTime() - inferenceStartNs) / 1_000_000.0
                require(inferenceLatencyMs.isFinite() && inferenceLatencyMs >= 0.0) {
                    "Measured detector latency is invalid at frame=${frame.index}"
                }
                inferenceSamples += inferenceLatencyMs

                val trackingDetections = rawDetections.filter {
                    it.confidence.isFinite() && it.confidence in config.trackerInputMinimumConfidence..1f
                }
                trackingDetectionCount += trackingDetections.size.toLong()
                val reportableDetections = trackingDetections.filter { it.confidence >= config.minimumDetectionConfidence }
                allDetections += reportableDetections

                val tracks = tracker.update(trackingDetections, frame.index, frame.timestampMs)
                lastActiveTracks = tracks.size
                peakActiveTracks = maxOf(peakActiveTracks, tracks.size)
                for (track in tracks) {
                    val observation = track.observations.lastOrNull { it.frameIndex == frame.index }
                    if (observation == null) {
                        trackingAssociationMisses++
                        continue
                    }

                    val keypoints = if (config.useVehicleKeypoints) {
                        keypointEstimator!!.estimate(frame.payload, observation.detection)
                    } else emptyList()

                    val contact = selectContactPoint(observation.detection, keypoints)
                    val ground = if (calibrationReady) {
                        runCatching {
                            HomographyProjector(config.calibration!!.homography).project(contact.first, contact.second)
                        }.getOrElse { error ->
                            throw IllegalStateException(
                                "Ground-plane projection failed at frame=${frame.index}, track=${track.id}",
                                error,
                            )
                        }
                    } else null

                    val enriched = observation.copy(groundPoint = ground, keypoints = keypoints)
                    val buffer = trackBuffers.getOrPut(track.id) { MutableTrackBuffer(track.id, track.className) }
                    buffer.wasOccluded = buffer.wasOccluded || track.wasOccluded
                    buffer.confidenceSamples += track.trackConfidence.toDouble()
                    buffer.observations += enriched

                    if (config.enablePlateRecognition) {
                        plateRecognizer!!.recognize(frame.payload, observation.detection)?.let { reading ->
                            plateReadings += reading.copy(
                                trackId = reading.trackId ?: track.id,
                                timestampMs = frame.timestampMs,
                            )
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
                            )
                        }
                        val liveSpeedAllowed = physicalSpeedAllowed(source, config, calibrationReady)
                        val liveSpeeds = if (liveSpeedAllowed) {
                            liveTracks.mapNotNull { liveTrack ->
                                RobustSpeedEstimator(
                                    minimumSamples = config.minimumSpeedSamples,
                                    minimumDurationMs = config.minimumTrackDurationMs,
                                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                                ).estimate(liveTrack.observations)?.let { liveTrack.id to it }
                            }.toMap()
                        } else emptyMap()
                        previewObserver.onFrame(
                            AnalysisPreviewFrame(
                                frame = frame,
                                bitmap = bitmap,
                                detections = reportableDetections,
                                tracks = liveTracks,
                                speedEstimates = liveSpeeds,
                                calibrated = liveSpeedAllowed,
                            ),
                        )
                    }
                }
            }
        } finally {
            source.close()
        }

        val completedTracks = trackBuffers.values.map { buffer ->
            val averageConfidence = if (buffer.confidenceSamples.isEmpty()) 0.0 else buffer.confidenceSamples.average()
            Track(
                id = buffer.id,
                className = buffer.className,
                observations = buffer.observations.sortedWith(compareBy<TrackObservation> { it.timestampMs }.thenBy { it.frameIndex }),
                trackConfidence = averageConfidence.toFloat().coerceIn(0f, 1f),
                wasOccluded = buffer.wasOccluded,
            )
        }

        val physicalSpeedAllowed = physicalSpeedAllowed(source, config, calibrationReady)
        val speedEstimates = if (physicalSpeedAllowed) {
            completedTracks.mapNotNull { track ->
                RobustSpeedEstimator(
                    minimumSamples = config.minimumSpeedSamples,
                    minimumDurationMs = config.minimumTrackDurationMs,
                    maxPlausibleSpeedKmh = config.maxPlausibleSpeedKmh,
                ).estimate(track.observations)?.let { speed -> track.id to speed }
            }.toMap()
        } else emptyMap()

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
        val processingFps = frameCount.takeIf { it > 0L && elapsedSeconds > 0.0 }?.let { it / elapsedSeconds }
        val e2ePerFrameMs = frameCount.takeIf { it > 0L }?.let { elapsedMs / it }
        val sortedInference = inferenceSamples.sorted()
        val inferenceMedian = percentile(sortedInference, 0.50)
        val inferenceP95 = percentile(sortedInference, 0.95)

        val rejectedSpeedEstimates = if (physicalSpeedAllowed) {
            (completedTracks.size - speedEstimates.size).toLong().coerceAtLeast(0L)
        } else completedTracks.size.toLong()

        return AnalysisResult(
            source = source.source,
            detections = allDetections,
            tracks = completedTracks,
            speedEstimates = speedEstimates,
            plateReadings = PlateConsensus.resolve(plateReadings),
            trafficEvents = trafficEvents,
            metrics = AnalysisMetrics(
                decodeFps = source.source.frameRate,
                timestampPrecision = source.source.timestampPrecision,
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
                activeTracks = lastActiveTracks,
                peakActiveTracks = peakActiveTracks,
                completedTracks = completedTracks.size.toLong(),
                speedEstimates = speedEstimates.size.toLong(),
                rejectedSpeedEstimates = rejectedSpeedEstimates,
                plateReads = plateReadings.size.toLong(),
                trafficEvents = trafficEvents.size.toLong(),
                homographyReprojectionError = config.calibration?.reprojectionErrorPixels
                    ?: config.calibration?.reprojectionErrorTargetUnits,
            ),
        )
    }

    private fun physicalSpeedAllowed(source: FrameSource, config: AnalysisConfig, calibrationReady: Boolean): Boolean {
        if (!calibrationReady) return false
        return !config.requireExactTimestampsForPhysicalSpeed ||
            source.source.timestampPrecision == FrameTimestampPrecision.EXACT_SOURCE_CLOCK
    }

    private fun calibrationAccepted(config: AnalysisConfig, sourceWidth: Int, sourceHeight: Int): Boolean {
        val calibration = config.calibration ?: return !config.requireValidatedCalibration
        val validation = CalibrationValidator.validate(
            profile = calibration,
            expectedWidth = sourceWidth,
            expectedHeight = sourceHeight,
            maxReprojectionErrorPixels = if (config.requireValidatedCalibration) config.maxCalibrationReprojectionErrorPixels else Double.POSITIVE_INFINITY,
            maxReprojectionErrorTargetUnits = if (config.requireValidatedCalibration) config.maxCalibrationReprojectionErrorTargetUnits else Double.POSITIVE_INFINITY,
            minimumInlierRatio = if (config.requireValidatedCalibration) config.minimumCalibrationInlierRatio else 0.0,
        )
        return validation.accepted
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
        val learned = keypoints
            .filter { it.confidence >= 0.50f && it.x.isFinite() && it.y.isFinite() }
            .firstOrNull {
                it.name.lowercase() in setOf("ground_contact", "contact", "footprint", "rear_contact", "front_contact")
            }
        return if (learned != null) learned.x to learned.y
        else ((detection.left + detection.right) / 2.0) to detection.bottom.toDouble()
    }

    private data class MutableTrackBuffer(
        val id: Long,
        val className: String,
        val observations: MutableList<TrackObservation> = mutableListOf(),
        val confidenceSamples: MutableList<Double> = mutableListOf(),
        var wasOccluded: Boolean = false,
    )
}
