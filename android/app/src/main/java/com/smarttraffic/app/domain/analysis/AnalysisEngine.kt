package com.smarttraffic.app.domain.analysis

/** Replaceable perception backend; implementations may use TFLite/ONNX/NNAPI/Native C++. */
interface ObjectDetector {
    suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection>
}

/** Replaceable multi-object tracker; BoT-SORT/ByteTrack/OC-SORT/Deep OC-SORT can plug in here. */
interface MultiObjectTracker {
    fun update(detections: List<Detection>, frameIndex: Long, timestampMs: Long): List<Track>
    fun reset()
}

/** Optional keypoint backend for learned vehicle geometry/contact-point estimation. */
interface VehicleKeypointEstimator {
    suspend fun estimate(frame: Any, detection: Detection): List<VehicleKeypoint>
}

data class VehicleKeypoint(
    val name: String,
    val x: Double,
    val y: Double,
    val confidence: Float,
)

interface PlateRecognizer {
    suspend fun recognize(frame: Any, vehicle: Detection): PlateReading?
}

interface AnalysisEngine {
    suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult
}

/**
 * Pipeline coordinator. Concrete ML adapters are intentionally injected later so
 * geometry, speed and validation code remains testable without a model runtime.
 */
class ModularAnalysisEngine(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypoints: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
    private val projector: HomographyProjector? = null,
    private val speedEstimator: RobustSpeedEstimator = RobustSpeedEstimator(),
) : AnalysisEngine {

    override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult {
        // Frame decoding/model invocation is intentionally not coupled to this coordinator yet.
        // The implementation becomes live once a FrameSource/decoder adapter is connected.
        tracker.reset()
        return AnalysisResult(source = source)
    }
}
