package com.smarttraffic.app.domain.analysis

/** Accuracy level of frame timestamps; only source-clock PTS can unlock physical speed. */
enum class FrameTimestampPrecision {
    EXACT_SOURCE_CLOCK,
    REQUESTED_SAMPLE_TIME,
    LOCAL_MONOTONIC_ARRIVAL,
    UNKNOWN,
}

enum class TrackState { TENTATIVE, CONFIRMED, LOST, REMOVED }

enum class SpeedRejectionReason {
    CALIBRATION_INVALID,
    TIMESTAMP_INVALID,
    INSUFFICIENT_OBSERVATIONS,
    INSUFFICIENT_DURATION,
    TRACK_QUALITY_LOW,
    DISCONTINUOUS_TRACK,
    GROUND_GEOMETRY_INCOMPLETE,
    PLAUSIBILITY_REJECTION,
    ROBUST_ESTIMATOR_REJECTION,
}

data class GroundPoint(
    val xMeters: Double,
    val yMeters: Double,
    val sourcePixelX: Double,
    val sourcePixelY: Double,
)

data class VehicleKeypoint(
    val name: String,
    val x: Double,
    val y: Double,
    val confidence: Float,
)

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classId: Int,
    val className: String,
)

data class TrackObservation(
    val frameIndex: Long,
    val timestampMs: Long,
    val detection: Detection,
    val groundPoint: GroundPoint? = null,
    val keypoints: List<VehicleKeypoint> = emptyList(),
)

data class Track(
    val id: Long,
    val className: String,
    val observations: List<TrackObservation>,
    val trackConfidence: Float,
    val wasOccluded: Boolean,
    val state: TrackState,
    val hits: Int,
    val misses: Int,
    val ageFrames: Int,
    val lastTimestampMs: Long,
)

data class SpeedEstimate(
    val metersPerSecond: Double,
    val kilometersPerHour: Double,
    val confidence: Float,
    val sampleCount: Int,
    val durationMs: Long,
    val velocityXMps: Double,
    val velocityYMps: Double,
    val directionDegrees: Double?,
    val positionResidualMeters: Double,
    val errorKmh: Double,
)

data class TrafficRuleConfig(
    val enabled: Boolean = true,
    val speedLimitKmh: Double = 80.0,
    val minimumSpeedConfidence: Float = 0.70f,
    val captureOnViolation: Boolean = true,
    val createAlertOnViolation: Boolean = true,
    val preserveEvidence: Boolean = true,
)

data class TrafficEvent(
    val id: String,
    val type: String,
    val timestampMs: Long,
    val trackId: Long,
    val measuredSpeedKmh: Double,
    val thresholdKmh: Double,
    val confidence: Float,
    val calibrationId: String?,
    val calibrationVersion: Int?,
    val detectorModel: String,
    val tracker: String,
    val evidenceRequested: Boolean,
)

data class PlateReading(
    val trackId: Long? = null,
    val timestampMs: Long,
    val text: String,
    val confidence: Float,
)

data class MediaSource(
    val id: String,
    val uri: String,
    val frameRate: Double? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val timestampPrecision: FrameTimestampPrecision = FrameTimestampPrecision.UNKNOWN,
)

data class AnalysisConfig(
    val detectorModel: String = "yolo26n",
    val tracker: String = "bytetrack",
    val trackerInputMinimumConfidence: Float = 0.10f,
    val minimumDetectionConfidence: Float = 0.25f,
    val minimumSpeedSamples: Int = 8,
    val minimumTrackDurationMs: Long = 500L,
    val maxPlausibleSpeedKmh: Double = 250.0,
    val requireValidatedCalibration: Boolean = true,
    val requireExactTimestampsForPhysicalSpeed: Boolean = true,
    val maxCalibrationReprojectionErrorPixels: Double = 2.0,
    val maxCalibrationReprojectionErrorTargetUnits: Double = 0.25,
    val minimumCalibrationInlierRatio: Double = 0.75,
    val calibration: CalibrationProfile? = null,
    val useGroundPlane: Boolean = true,
    val useVehicleKeypoints: Boolean = false,
    val useDynamicKeypointHomography: Boolean = false,
    val useOpticalFlowRefinement: Boolean = false,
    val useSegmentationRefinement: Boolean = false,
    val useReIdentification: Boolean = false,
    val useAppearanceAssociation: Boolean = true,
    val enablePlateRecognition: Boolean = false,
    val enableRules: Boolean = false,
    val trafficRules: TrafficRuleConfig = TrafficRuleConfig(),
    val enableEvidence: Boolean = false,
    val maxRetainedDetections: Int = 10_000,
    val maxTrackHistoryObservations: Int = 900,
    val latencySampleWindow: Int = 2048,
    val maxPlateReadings: Int = 512,
    val minimumTrackConfidenceForSpeed: Float = 0.50f,
    val maximumSpeedObservationGapMs: Long = 600L,
    val maximumPreviewFps: Double = 12.0,
)

data class AnalysisMetrics(
    val decodeFps: Double? = null,
    val sourceNominalFps: Double? = null,
    val timestampPrecision: FrameTimestampPrecision = FrameTimestampPrecision.UNKNOWN,
    val inferenceLatencyMs: Double? = null,
    val inferenceMedianLatencyMs: Double? = null,
    val inferenceP95LatencyMs: Double? = null,
    val endToEndLatencyMs: Double? = null,
    val totalProcessingTimeMs: Double = 0.0,
    val processingFps: Double? = null,
    val droppedFrames: Long = 0L,
    val framesProcessed: Long = 0L,
    val trackingDetections: Long = 0L,
    val detections: Long = 0L,
    val inferenceFailures: Long = 0L,
    val trackingAssociationMisses: Long = 0L,
    val activeTracks: Int = 0,
    val peakActiveTracks: Int = 0,
    val completedTracks: Long = 0L,
    val speedEstimates: Long = 0L,
    val rejectedSpeedEstimates: Long = 0L,
    val plateReads: Long = 0L,
    val trafficEvents: Long = 0L,
    val homographyReprojectionError: Double? = null,
    val speedEstimatorBackend: String? = null,
)

data class AnalysisResult(
    val source: MediaSource,
    val detections: List<Detection>,
    val tracks: List<Track>,
    val speedEstimates: Map<Long, SpeedEstimate>,
    val speedRejectionReasons: Map<Long, SpeedRejectionReason>,
    val plateReadings: List<PlateReading>,
    val trafficEvents: List<TrafficEvent>,
    val metrics: AnalysisMetrics,
)
