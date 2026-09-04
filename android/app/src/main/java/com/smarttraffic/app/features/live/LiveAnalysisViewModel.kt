package com.smarttraffic.app.features.live

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.AnalysisDiagnostics
import com.smarttraffic.app.core.DeviceSettings
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.data.analysis.AnalysisRuntimeFactory
import com.smarttraffic.app.data.analysis.MjpegFrameSource
import com.smarttraffic.app.data.nativecore.NativeFirstSpeedEstimator
import com.smarttraffic.app.data.nativecore.NativeGroundProjector
import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.AnalysisPreviewObserver
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.AnalysisSessionPhase
import com.smarttraffic.app.domain.analysis.ModularAnalysisEngine
import com.smarttraffic.app.domain.analysis.UnifiedAnalysisSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live adapter around the application-scoped analysis lifecycle. */
enum class LiveAnalysisPhase { IDLE, STARTING, RUNNING, ERROR, STOPPED }

data class LiveAnalysisState(
    val phase: LiveAnalysisPhase = LiveAnalysisPhase.IDLE,
    val message: String? = null,
    val accelerator: String? = null,
    val droppedFrames: Long = 0L,
    val result: AnalysisResult? = null,
)

class LiveAnalysisViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val session: UnifiedAnalysisSession =
        application as SmartTrafficApplication
            .let { it.analysisHost.session }
    private val _state = MutableStateFlow(LiveAnalysisState())
    val state: StateFlow<LiveAnalysisState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<AnalysisPreviewFrame?>(null)
    val preview: StateFlow<AnalysisPreviewFrame?> = _preview.asStateFlow()

    fun start() {
        val phase = session.state.value.phase
        if (phase == AnalysisSessionPhase.STARTING || phase == AnalysisSessionPhase.RUNNING) return

        _preview.value = null
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<SmartTrafficApplication>()
            val runId = AnalysisDiagnostics.newRun(app)
            var runtime: AnalysisRuntimeFactory.DetectorRuntime? = null
            var source: MjpegFrameSource? = null
            var sessionOwnsResources = false
            try {
                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.MODEL_VALIDATE, modelId = "yolo26n")
                val rules = TrafficRulePreferences.load(app)
                val spec = DetectorModelRegistry.requireSpec("yolo26n")
                require(DetectorModelRegistry.isInstalled(app, spec)) {
                    "Detector model is not installed: ${spec.assetPath}"
                }

                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.MODEL_INITIALIZE,
                    modelId = spec.id,
                    accelerator = "CPU",
                )
                runtime = AnalysisRuntimeFactory.createDetector(
                    context = app,
                    modelId = spec.id,
                    useAppearanceAssociation = true,
                )

                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.MEDIA_OPEN,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = "MJPEG ${DeviceSettings.streamUrl()}",
                )
                source = MjpegFrameSource(
                    url = DeviceSettings.streamUrl(),
                    scope = viewModelScope,
                )
                val observer = AnalysisPreviewObserver { frame ->
                    session.publishPreview(frame)
                    _preview.value = frame
                    _state.value = _state.value.copy(
                        phase = LiveAnalysisPhase.RUNNING,
                        droppedFrames = source?.droppedFrameCount() ?: 0L,
                    )
                }
                val engine = ModularAnalysisEngine(
                    detector = runtime!!.detector,
                    tracker = ByteTrack(),
                    previewObserver = observer,
                    groundProjector = NativeGroundProjector(),
                    speedEstimator = NativeFirstSpeedEstimator(),
                )
                val config = AnalysisConfig(
                    detectorModel = spec.id,
                    tracker = "bytetrack",
                    trackerInputMinimumConfidence = 0.10f,
                    minimumDetectionConfidence = 0.20f,
                    minimumTrackDurationMs = 700L,
                    minimumSpeedSamples = 8,
                    useAppearanceAssociation = true,
                    useGroundPlane = false,
                    enableRules = rules.enabled,
                    trafficRules = rules,
                    showRadarOverlay = true,
                )

                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.PIPELINE_START,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = "MJPEG ${source!!.source.uri}",
                )
                val started = session.start(
                    source = source!!,
                    engine = engine,
                    config = config,
                    accelerator = runtime!!.accelerator.name,
                    runtime = runtime,
                )
                if (!started) {
                    _state.value = LiveAnalysisState(
                        phase = LiveAnalysisPhase.ERROR,
                        message = "Another analysis session is already active.",
                        accelerator = runtime!!.accelerator.name,
                    )
                    return@launch
                }
                sessionOwnsResources = true
                _state.value = LiveAnalysisState(
                    phase = LiveAnalysisPhase.RUNNING,
                    message = "Live detector and tracker are running…",
                    accelerator = runtime!!.accelerator.name,
                )
                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.RUNNING,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = source!!.source.uri,
                    completed = false,
                )
                session.awaitCompletion()

                val finalState = session.state.value
                _state.value = when (finalState.phase) {
                    AnalysisSessionPhase.COMPLETED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.COMPLETED, completed = true)
                        LiveAnalysisState(
                            phase = LiveAnalysisPhase.STOPPED,
                            message = finalState.message,
                            accelerator = finalState.accelerator,
                            droppedFrames = source?.droppedFrameCount() ?: 0L,
                            result = finalState.result,
                        )
                    }
                    AnalysisSessionPhase.FAILED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.FAILED, completed = false)
                        LiveAnalysisState(
                            phase = LiveAnalysisPhase.ERROR,
                            message = finalState.message,
                            accelerator = finalState.accelerator,
                            droppedFrames = source?.droppedFrameCount() ?: 0L,
                            result = finalState.result,
                        )
                    }
                    AnalysisSessionPhase.STOPPED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.STOPPED, completed = true)
                        LiveAnalysisState(
                            phase = LiveAnalysisPhase.STOPPED,
                            message = finalState.message,
                            accelerator = finalState.accelerator,
                            droppedFrames = source?.droppedFrameCount() ?: 0L,
                            result = finalState.result,
                        )
                    }
                    else -> _state.value
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.STOPPED, completed = true)
                throw cancelled
            } catch (t: Throwable) {
                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.FAILED,
                    modelId = "yolo26n",
                    accelerator = runtime?.accelerator?.name,
                    mediaDescription = source?.source?.uri,
                    frameInfo = "${t::class.java.name}: ${t.message}",
                    completed = false,
                )
                _state.value = LiveAnalysisState(
                    phase = LiveAnalysisPhase.ERROR,
                    message = t.message ?: t::class.java.simpleName,
                    accelerator = runtime?.accelerator?.name,
                )
            } finally {
                if (!sessionOwnsResources) {
                    runCatching { source?.close() }
                    runCatching { runtime?.close() }
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.Default) {
            session.stop()
            _state.value = _state.value.copy(
                phase = LiveAnalysisPhase.STOPPED,
                message = "Live analysis stopped",
            )
        }
    }

    fun reset() {
        viewModelScope.launch(Dispatchers.Default) {
            session.stop()
            session.reset()
            _preview.value = null
            _state.value = LiveAnalysisState()
        }
    }
}
