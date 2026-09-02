package com.smarttraffic.app.domain.analysis

/** Replaceable perception backend; implementations may use LiteRT/ONNX/NNAPI/Native C++. */
interface ObjectDetector {
    suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection>
}

/** Replaceable multi-object tracker; modern tracker implementations plug in here. */
interface MultiObjectTracker {
    fun update(detections: List<Detection>, frameIndex: Long, timestampMs: Long): List<Track>
    fun reset()
}

/** Optional keypoint backend for learned vehicle geometry/contact-point estimation. */
interface VehicleKeypointEstimator {
    suspend fun estimate(frame: Any, detection: Detection): List<VehicleKeypoint>
}

interface PlateRecognizer {
    suspend fun recognize(frame: Any, vehicle: Detection): PlateReading?
}

interface AnalysisEngine {
    suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult

    /** Executes the real frame pipeline over a transport-independent source. */
    suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult
}

/**
 * Shared analysis coordinator. Model runtimes are injected; geometry and speed
 * remain deterministic and testable independently of the chosen ML backend.
 */
class ModularAnalysisEngine(
    private val detector: ObjectDetector,
    private val tracker: MultiObjectTracker,
    private val keypoints: VehicleKeypointEstimator? = null,
    private val plateRecognizer: PlateRecognizer? = null,
) : AnalysisEngine {
    private val runner = AnalysisPipelineRunner(
        detector = detector,
        tracker = tracker,
        keypointEstimator = keypoints,
        plateRecognizer = plateRecognizer,
    )

    override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult =
        AnalysisResult(source = source)

    override suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult =
        runner.run(source, config)
}
