package com.smarttraffic.app.domain.analysis

/** Timestamp provenance for physical measurements. */
enum class FrameTimestampPrecision {
    EXACT_SOURCE_CLOCK,
    REQUESTED_SAMPLE_TIME,
    UNKNOWN,
}

data class MediaSource(
    val id: String,
    val uri: String,
    val frameRate: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val timestampPrecision: FrameTimestampPrecision = FrameTimestampPrecision.UNKNOWN,
)

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameIndex: Long,
    val timestampMs: Long,
    /** Optional deterministic appearance cue computed from the vehicle crop. */
    val appearanceSignature: FloatArray? = null,
)

data class GroundPoint(
    val xMeters: Double,
    val yMeters: Double,
    val sourcePixelX: Double,
    val sourcePixelY: Double,
    val reprojectionErrorMeters: Double? = null,
)

data class VehicleKeypoint(val name: String, val x: Double, val y: Double, val confidence: Float)

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
    val wasOccluded: Boolean = false,
)

data class SpeedEstimate(
    val metersPerSecond: Double,
    val kilometersPerHour: Double,
    val confidence: Float,
    val sampleCount: Int,
    val durationMs: Long,
    val velocityXMps: Double? = null,
    val velocityYMps: Double? = null,
    val directionDegrees: Double? = null,
    val positionResidualMeters: Double? = null,
    val errorKmh: Double? = null,
)

data class PlateReading(
    val text: String,
    val confidence: Float,
    val frameIndex: Long,
    val trackId: Long? = null,
    val timestampMs: Long = frameIndex,
)

data class CalibrationProfile(
    val id: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val homography: List<Double>,
    val intrinsicMatrix: List<Double>? = null,
    val distortionCoefficients: List<Double>? = null,
    val reprojectionErrorPixels: Double? = null,
    val reprojectionErrorTargetUnits: Double? = null,
    val version: Int = 1,
    val homographyInlierCount: Int? = null,
    val homographyInlierRatio: Double? = null,
)

data class AnalysisConfig(
    val detectorModel: String = "yolo26n",
    val tracker: String = "bytetrack",
    val trackerInputMinimumConfidence: Float = 0.10f,
    val minimumDetectionConfidence: Float = 0.25f,
    val minimumTrackDurationMs: Long = 500L,
    val minimumSpeedSamples: Int = 8,
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
    val showRadarOverlay: Boolean = true,
    /** Maximum number of reportable detections retained in the final result object. */
    val maxRetainedDetections: Int = 10_000,
    /** Maximum per-track observations retained for measurement/evidence processing. */
    val maxTrackHistoryObservations: Int = 900,
    /** Number of most-recent latency samples retained for robust percentile estimates. */
    val latencySampleWindow: Int = 2_048,
)

data class AnalysisMetrics(
    val decodeFps: Double? = null,
    val timestampPrecision: FrameTimestampPrecision = FrameTimestampPrecision.UNKNOWN,
    val inferenceLatencyMs: Double? = null,
    val inferenceMedianLatencyMs: Double? = null,
    val inferenceP95LatencyMs: Double? = null,
    val endToEndLatencyMs: Double? = null,
    val totalProcessingTimeMs: Double? = null,
    val processingFps: Double? = null,
    val droppedFrames: Long = 0,
    val framesProcessed: Long = 0,
    val trackingDetections: Long = 0,
    val detections: Long = 0,
    val inferenceFailures: Long = 0,
    val trackingAssociationMisses: Long = 0,
    val activeTracks: Int = 0,
    val peakActiveTracks: Int = 0,
    val completedTracks: Long = 0,
    val speedEstimates: Long = 0,
    val rejectedSpeedEstimates: Long = 0,
    val plateReads: Long = 0,
    val trafficEvents: Long = 0,
    val homographyReprojectionError: Double? = null,
    val speedEstimatorBackend: String = "Kotlin reference",
)

data class AnalysisResult(
    val source: MediaSource,
    val detections: List<Detection> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val speedEstimates: Map<Long, SpeedEstimate> = emptyMap(),
    val plateReadings: List<PlateReading> = emptyList(),
    val trafficEvents: List<TrafficEvent> = emptyList(),
    val metrics: AnalysisMetrics = AnalysisMetrics(),
)
