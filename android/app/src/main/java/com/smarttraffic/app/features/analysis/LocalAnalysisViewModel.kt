package com.smarttraffic.app.features.analysis

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.data.analysis.AnalysisRuntimeFactory
import com.smarttraffic.app.data.analysis.ExactPtsVideoFrameSource
import com.smarttraffic.app.data.analysis.LocalImageFrameSource
import com.smarttraffic.app.data.analysis.LocalVideoFrameSource
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.AnalysisPreviewObserver
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.AnalysisSessionPhase
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.KotlinGroundProjector
import com.smarttraffic.app.domain.analysis.KotlinSpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.ModularAnalysisEngine
import com.smarttraffic.app.domain.analysis.UnifiedAnalysisSession
import kotlinx.coroutines.Dispatchers
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
    val accelerator: String? = null,
    val result: AnalysisResult? = null,
)

/** Local Lab adapter around the same UnifiedAnalysisSession used by live analysis. */
class LocalAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val session = UnifiedAnalysisSession(viewModelScope)
    private val _state = MutableStateFlow(AnalysisRunState())
    val state: StateFlow<AnalysisRunState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<AnalysisPreviewFrame?>(null)
    val preview: StateFlow<AnalysisPreviewFrame?> = _preview.asStateFlow()

    fun reset() {
        viewModelScope.launch {
            session.stop()
            session.reset()
            _preview.value = null
            _state.value = AnalysisRunState()
        }
    }

    fun run(uri: Uri, mediaType: AnalysisMediaType, config: AnalysisConfig) {
        if (session.state.value.phase == AnalysisSessionPhase.STARTING ||
            session.state.value.phase == AnalysisSessionPhase.RUNNING
        ) return

        _preview.value = null
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val persistedRules = TrafficRulePreferences.load(app)
            val effectiveConfig = config.copy(
                enableRules = config.enableRules || persistedRules.enabled,
                trafficRules = persistedRules,
                enableEvidence = config.enableEvidence || persistedRules.preserveEvidence,
            )

            val spec = runCatching {
                com.smarttraffic.app.data.vision.DetectorModelRegistry.requireSpec(effectiveConfig.detectorModel)
            }.getOrElse { error ->
                _state.value = AnalysisRunState(AnalysisRunPhase.ERROR, error.message)
                return@launch
            }
            if (!com.smarttraffic.app.data.vision.DetectorModelRegistry.isInstalled(app, spec)) {
                _state.value = AnalysisRunState(
                    AnalysisRunPhase.ERROR,
                    "Detector model is not installed: ${spec.assetPath}",
                )
                return@launch
            }

            try {
                val runtime = AnalysisRuntimeFactory.createDetector(
                    context = app,
                    modelId = spec.id,
                    useAppearanceAssociation = effectiveConfig.useAppearanceAssociation,
                )
                val source: FrameSource = when (mediaType) {
                    AnalysisMediaType.VIDEO -> runCatching {
                        ExactPtsVideoFrameSource(app, uri)
                    }.getOrElse {
                        // Explicit fallback: precision remains REQUESTED_SAMPLE_TIME, so the
                        // physical-speed gate will remain closed for this source.
                        LocalVideoFrameSource(app, uri)
                    }
                    AnalysisMediaType.IMAGE -> {
                        val bitmap = app.contentResolver.openInputStream(uri).use { stream ->
                            requireNotNull(stream) { "Unable to open selected image" }
                            requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode selected image" }
                        }
                        LocalImageFrameSource(bitmap, uri.toString())
                    }
                }

                val observer = AnalysisPreviewObserver { previewFrame ->
                    session.publishPreview(previewFrame)
                    _preview.value = previewFrame
                }
                val engine = ModularAnalysisEngine(
                    detector = runtime.detector,
                    tracker = ByteTrack(),
                    previewObserver = observer,
                    // Keep the first on-device validation run independent of Android JNI/native
                    // loading. C++ remains covered by the native parity tests until a real-device
                    // native smoke test has passed.
                    groundProjector = KotlinGroundProjector,
                    speedEstimator = KotlinSpeedEstimatorBackend,
                )

                val started = session.start(
                    source = source,
                    engine = engine,
                    config = effectiveConfig,
                    accelerator = runtime.accelerator.name,
                    runtime = runtime,
                )
                if (!started) {
                    source.close()
                    runtime.close()
                    return@launch
                }
                session.awaitCompletion()

                val finalState = session.state.value
                _state.value = when (finalState.phase) {
                    AnalysisSessionPhase.COMPLETED -> AnalysisRunState(
                        phase = AnalysisRunPhase.SUCCESS,
                        message = finalState.message,
                        accelerator = finalState.accelerator,
                        result = finalState.result,
                    )
                    AnalysisSessionPhase.FAILED -> AnalysisRunState(
                        phase = AnalysisRunPhase.ERROR,
                        message = finalState.message,
                        accelerator = finalState.accelerator,
                        result = finalState.result,
                    )
                    else -> _state.value
                }

                finalState.result?.let { result ->
                    if (effectiveConfig.enableEvidence) persistEvidence(app, result)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.ERROR,
                    message = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    private suspend fun persistEvidence(app: Application, result: AnalysisResult) = withContext(Dispatchers.IO) {
        if (result.trafficEvents.isEmpty()) return@withContext
        val store = FileEvidenceStore(app)
        result.trafficEvents.filter { it.evidenceRequested }.forEach { event ->
            val track = result.tracks.firstOrNull { it.id == event.trackId }
            val nearestObservation = track?.observations?.minByOrNull {
                kotlin.math.abs(it.timestampMs - event.timestampMs)
            }
            val plate = result.plateReadings
                .filter { it.trackId == event.trackId }
                .minByOrNull { kotlin.math.abs(it.timestampMs - event.timestampMs) }
            store.save(
                EvidenceRecord(
                    id = event.id,
                    eventId = event.id,
                    sourceId = result.source.id,
                    sourceUri = result.source.uri,
                    frameIndex = nearestObservation?.frameIndex ?: -1L,
                    timestampMs = event.timestampMs,
                    eventType = event.type,
                    measuredSpeedKmh = event.measuredSpeedKmh,
                    thresholdKmh = event.thresholdKmh,
                    confidence = event.confidence,
                    trackId = event.trackId,
                    plateText = plate?.text,
                    calibrationId = event.calibrationId,
                    calibrationVersion = event.calibrationVersion,
                    detectorModel = event.detectorModel,
                    tracker = event.tracker,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
