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
import com.smarttraffic.app.domain.analysis.ModularAnalysisEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LiveAnalysisPhase { IDLE, STARTING, RUNNING, ERROR, STOPPED }

data class LiveAnalysisState(
    val phase: LiveAnalysisPhase = LiveAnalysisPhase.IDLE,
    val message: String? = null,
    val accelerator: String? = null,
    val droppedFrames: Long = 0L,
    val result: AnalysisResult? = null,
)

/** Real ESP32 MJPEG analysis session; uses the same engine and native math as the Local Lab. */
class LiveAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LiveAnalysisState())
    val state: StateFlow<LiveAnalysisState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<AnalysisPreviewFrame?>(null)
    val preview: StateFlow<AnalysisPreviewFrame?> = _preview.asStateFlow()

    private var activeJob: Job? = null
    private var activeSource: MjpegFrameSource? = null

    fun start() {
        if (activeJob?.isActive == true) return
        _preview.value = null
        activeJob = viewModelScope.launch(Dispatchers.Default) {
            _state.value = LiveAnalysisState(
                phase = LiveAnalysisPhase.STARTING,
                message = "Starting live detector and tracker…",
            )
            val app = getApplication<Application>()
            val rules = TrafficRulePreferences.load(app)
            val spec = DetectorModelRegistry.requireSpec("yolo26n")
            if (!DetectorModelRegistry.isInstalled(app, spec)) {
                _state.value = LiveAnalysisState(LiveAnalysisPhase.ERROR, "Detector model is not installed: ${spec.assetPath}")
                return@launch
            }

            val source = MjpegFrameSource(
                url = DeviceSettings.streamUrl(),
                scope = viewModelScope,
            )
            activeSource = source
            try {
                AnalysisRuntimeFactory.createDetector(
                    context = app,
                    modelId = spec.id,
                    useAppearanceAssociation = true,
                ).use { runtime ->
                    _state.value = LiveAnalysisState(
                        phase = LiveAnalysisPhase.RUNNING,
                        message = "LIVE • ${runtime.accelerator.name} • tracking latest camera frames",
                        accelerator = runtime.accelerator.name,
                    )
                    val observer = AnalysisPreviewObserver { frame ->
                        _preview.value = frame
                        _state.value = _state.value.copy(
                            phase = LiveAnalysisPhase.RUNNING,
                            droppedFrames = source.droppedFrameCount(),
                        )
                    }
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
                    val result = ModularAnalysisEngine(
                        detector = runtime.detector,
                        tracker = ByteTrack(),
                        previewObserver = observer,
                        groundProjector = NativeGroundProjector(),
                        speedEstimator = NativeFirstSpeedEstimator(),
                    ).analyze(source, config)
                    _state.value = _state.value.copy(
                        phase = LiveAnalysisPhase.STOPPED,
                        message = "Live stream ended",
                        droppedFrames = source.droppedFrameCount(),
                        result = result,
                    )
                }
            } catch (t: Throwable) {
                if (activeJob?.isCancelled == true) return@launch
                _state.value = LiveAnalysisState(
                    phase = LiveAnalysisPhase.ERROR,
                    message = t.message ?: t::class.java.simpleName,
                    droppedFrames = source.droppedFrameCount(),
                )
            } finally {
                activeSource = null
            }
        }
    }

    fun stop() {
        activeSource?.let { source ->
            viewModelScope.launch { source.close() }
        }
        activeSource = null
        activeJob?.cancel()
        activeJob = null
        _state.value = _state.value.copy(
            phase = LiveAnalysisPhase.STOPPED,
            message = "Live analysis stopped",
        )
    }

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
        activeSource?.let { viewModelScope.launch { it.close() } }
        activeSource = null
        super.onCleared()
    }
}
