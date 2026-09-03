package com.smarttraffic.app.features.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

/** Public Live state projected from the same UnifiedAnalysisSession used by the Local Lab. */
enum class LiveAnalysisPhase { IDLE, STARTING, RUNNING, ERROR, STOPPED }

data class LiveAnalysisState(
    val phase: LiveAnalysisPhase = LiveAnalysisPhase.IDLE,
    val message: String? = null,
    val accelerator: String? = null,
    val droppedFrames: Long = 0L,
    val result: AnalysisResult? = null,
)

/** Real ESP32 analysis adapter; lifecycle belongs to UnifiedAnalysisSession. */
class LiveAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val session = UnifiedAnalysisSession(viewModelScope)
    private val _state = MutableStateFlow(LiveAnalysisState())
    val state: StateFlow<LiveAnalysisState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<AnalysisPreviewFrame?>(null)
    val preview: StateFlow<AnalysisPreviewFrame?> = _preview.asStateFlow()

    fun start() {
        val phase = session.state.value.phase
        if (phase == AnalysisSessionPhase.STARTING || phase == AnalysisSessionPhase.RUNNING) return

        _preview.value = null
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val rules = TrafficRulePreferences.load(app)
            val spec = DetectorModelRegistry.requireSpec("yolo26n")
            if (!DetectorModelRegistry.isInstalled(app, spec)) {
                _state.value = LiveAnalysisState(
                    LiveAnalysisPhase.ERROR,
                    "Detector model is not installed: ${spec.assetPath}",
                )
                return@launch
            }

            try {
                val runtime = AnalysisRuntimeFactory.createDetector(
                    context = app,
                    modelId = spec.id,
                    useAppearanceAssociation = true,
                )
                val source = MjpegFrameSource(
                    url = DeviceSettings.streamUrl(),
                    scope = viewModelScope,
                )
                val observer = AnalysisPreviewObserver { frame ->
                    session.publishPreview(frame)
                    _preview.value = frame
                    _state.value = _state.value.copy(
                        phase = LiveAnalysisPhase.RUNNING,
                        droppedFrames = source.droppedFrameCount(),
                    )
                }
                val engine = ModularAnalysisEngine(
                    detector = runtime.detector,
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

                val started = session.start(
                    source = source,
                    engine = engine,
                    config = config,
                    accelerator = runtime.accelerator.name,
                    runtime = runtime,
                )
                if (!started) {
                    source.close()
                    runtime.close()
                    return@launch
                }
                _state.value = LiveAnalysisState(
                    phase = LiveAnalysisPhase.STARTING,
                    message = "Starting live detector and tracker…",
                    accelerator = runtime.accelerator.name,
                )
                session.awaitCompletion()

                val finalState = session.state.value
                _state.value = when (finalState.phase) {
                    AnalysisSessionPhase.COMPLETED -> LiveAnalysisState(
                        phase = LiveAnalysisPhase.STOPPED,
                        message = finalState.message,
                        accelerator = finalState.accelerator,
                        droppedFrames = source.droppedFrameCount(),
                        result = finalState.result,
                    )
                    AnalysisSessionPhase.FAILED -> LiveAnalysisState(
                        phase = LiveAnalysisPhase.ERROR,
                        message = finalState.message,
                        accelerator = finalState.accelerator,
                        droppedFrames = source.droppedFrameCount(),
                        result = finalState.result,
                    )
                    else -> _state.value
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.value = LiveAnalysisState(
                    phase = LiveAnalysisPhase.ERROR,
                    message = t.message ?: t::class.java.simpleName,
                )
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
