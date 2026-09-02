package com.smarttraffic.app.features.analysis

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litert.Accelerator
import com.smarttraffic.app.data.analysis.LocalImageFrameSource
import com.smarttraffic.app.data.analysis.LocalVideoFrameSource
import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.data.vision.LiteRtObjectDetector
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.ModularAnalysisEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AnalysisRunPhase { IDLE, RUNNING, SUCCESS, ERROR }
enum class AnalysisMediaType { VIDEO, IMAGE }

data class AnalysisRunState(
    val phase: AnalysisRunPhase = AnalysisRunPhase.IDLE,
    val message: String? = null,
    val result: AnalysisResult? = null,
)

/** Runs the real local-video/image pipeline; missing capabilities fail explicitly. */
class LocalAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AnalysisRunState())
    val state: StateFlow<AnalysisRunState> = _state.asStateFlow()
    private var activeJob: Job? = null

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _state.value = AnalysisRunState()
    }

    fun run(uri: Uri, mediaType: AnalysisMediaType, config: AnalysisConfig) {
        if (activeJob?.isActive == true) return
        activeJob = viewModelScope.launch(Dispatchers.Default) {
            _state.value = AnalysisRunState(
                phase = AnalysisRunPhase.RUNNING,
                message = "Loading validated on-device detector and media source…",
            )
            try {
                val app = getApplication<Application>()
                val spec = DetectorModelRegistry.requireSpec(config.detectorModel)
                if (!DetectorModelRegistry.isInstalled(app, spec)) {
                    error("Detector model is not installed: ${spec.assetPath}. Add the validated LiteRT artifact before running real inference.")
                }

                val result = withContext(Dispatchers.Default) {
                    LiteRtObjectDetector(
                        context = app,
                        assetName = spec.assetPath,
                        accelerator = Accelerator.CPU,
                        inputSize = spec.inputSize,
                    ).use { detector ->
                        val frameSource = when (mediaType) {
                            AnalysisMediaType.VIDEO -> LocalVideoFrameSource(app, uri)
                            AnalysisMediaType.IMAGE -> {
                                val bitmap = app.contentResolver.openInputStream(uri).use { stream ->
                                    requireNotNull(stream) { "Unable to open selected image" }
                                    requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode selected image" }
                                }
                                LocalImageFrameSource(bitmap, uri.toString())
                            }
                        }
                        ModularAnalysisEngine(
                            detector = detector,
                            tracker = ByteTrack(),
                        ).analyze(frameSource, config)
                    }
                }

                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.SUCCESS,
                    message = "Real analysis completed. Metrics below come from the executed pipeline.",
                    result = result,
                )
            } catch (t: Throwable) {
                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.ERROR,
                    message = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
        super.onCleared()
    }
}
