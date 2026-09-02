package com.smarttraffic.app.domain.analysis

/** Stable identity of the media/camera source entering the analysis engine. */
data class MediaSource(
    val id: String,
    val uri: String,
    val frameRate: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
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
)

data class GroundPoint(
    val xMeters: Double,
    val yMeters: Double,
    val sourcePixelX: Double,
    val sourcePixelY: Double,
    val reprojectionErrorMeters: Double? = null,
)

data class VehicleKeypoint(
    val name: String,
    val x: Double,
    val y: Double,
    val confidence: Float,
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
    val wasOccluded: Boolean = false,
)

data class SpeedEstimate(
    val metersPerSecond: Double,
    val kilometersPerHour: Double,
    val confidence: Float,
    val sampleCount: Int,
    val durationMs: Long,
    val positionResidualMeters: Double? = null,
    val errorKmh: Double? = null,
)

data class PlateReading(
    val text: String,
    val confidence: Float,
    val frameIndex: Long,
    val trackId: Long? = null,
)

data class CalibrationProfile(
    val id: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val homography: List<Double>,
    val intrinsicMatrix: List<Double>? = null,
    val distortionCoefficients: List<Double>? = null,
    val reprojectionErrorPixels: Double? = null,
    val version: Int = 1,
)

data class AnalysisConfig(
    val detectorModel: String = "yolo26n",
    val tracker: String = "botsort",
    val minimumDetectionConfidence: Float = 0.70f,
    val minimumTrackDurationMs: Long = 500L,
    val minimumSpeedSamples: Int = 8,
    val useGroundPlane: Boolean = true,
    val useVehicleKeypoints: Boolean = false,
    val useDynamicKeypointHomography: Boolean = false,
    val useOpticalFlowRefinement: Boolean = false,
    val useSegmentationRefinement: Boolean = false,
    val useReIdentification: Boolean = true,
    val enablePlateRecognition: Boolean = true,
    val enableRules: Boolean = true,
    val enableEvidence: Boolean = true,
    val showRadarOverlay: Boolean = true,
)

data class AnalysisMetrics(
    val decodeFps: Double? = null,
    val inferenceLatencyMs: Double? = null,
    val endToEndLatencyMs: Double? = null,
    val droppedFrames: Long = 0,
    val detections: Long = 0,
    val activeTracks: Int = 0,
    val completedTracks: Long = 0,
    val speedEstimates: Long = 0,
    val plateReads: Long = 0,
    val homographyReprojectionError: Double? = null,
)

data class AnalysisResult(
    val source: MediaSource,
    val detections: List<Detection> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val speedEstimates: Map<Long, SpeedEstimate> = emptyMap(),
    val plateReadings: List<PlateReading> = emptyList(),
    val metrics: AnalysisMetrics = AnalysisMetrics(),
)
