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

/** Converts a logical media source into a real timestamped frame stream. */
fun interface FrameSourceFactory {
    suspend fun create(source: MediaSource): FrameSource
}

interface AnalysisEngine {
    suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult
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
    private val frameSourceFactory: FrameSourceFactory? = null,
    private val previewObserver: AnalysisPreviewObserver? = null,
    private val groundProjector: GroundProjector = KotlinGroundProjector,
) : AnalysisEngine {
    private val runner = AnalysisPipelineRunner(
        detector = detector,
        tracker = tracker,
        keypointEstimator = keypoints,
        plateRecognizer = plateRecognizer,
        previewObserver = previewObserver,
        groundProjector = groundProjector,
    )

    override suspend fun analyze(source: MediaSource, config: AnalysisConfig): AnalysisResult {
        val factory = frameSourceFactory
            ?: error("No FrameSourceFactory is configured for MediaSource ${source.id}")
        return runner.run(factory.create(source), config)
    }

    override suspend fun analyze(source: FrameSource, config: AnalysisConfig): AnalysisResult =
        runner.run(source, config)
}
