package com.smarttraffic.app.features.analysis

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litert.Accelerator
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.data.analysis.LocalImageFrameSource
import com.smarttraffic.app.data.analysis.LocalVideoFrameSource
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.data.vision.AppearanceAugmentingDetector
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.data.vision.LiteRtObjectDetector
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.AnalysisPreviewObserver
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.EvidenceRecord
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

/** Runs the real local-video/image pipeline and streams processed frames to the laboratory preview. */
class LocalAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AnalysisRunState())
    val state: StateFlow<AnalysisRunState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<AnalysisPreviewFrame?>(null)
    val preview: StateFlow<AnalysisPreviewFrame?> = _preview.asStateFlow()

    private var activeJob: Job? = null

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _preview.value = null
        _state.value = AnalysisRunState()
    }

    fun run(uri: Uri, mediaType: AnalysisMediaType, config: AnalysisConfig) {
        if (activeJob?.isActive == true) return
        _preview.value = null
        activeJob = viewModelScope.launch(Dispatchers.Default) {
            _state.value = AnalysisRunState(
                phase = AnalysisRunPhase.RUNNING,
                message = "Running detector, tracker, geometry, rules and live radar…",
            )
            try {
                val app = getApplication<Application>()
                val persistedRules = TrafficRulePreferences.load(app)
                val effectiveConfig = config.copy(
                    enableRules = config.enableRules || persistedRules.enabled,
                    trafficRules = persistedRules,
                    enableEvidence = config.enableEvidence || persistedRules.preserveEvidence,
                )
                val spec = DetectorModelRegistry.requireSpec(effectiveConfig.detectorModel)
                if (!DetectorModelRegistry.isInstalled(app, spec)) {
                    error("Detector model is not installed: ${spec.assetPath}")
                }

                val result = withContext(Dispatchers.Default) {
                    LiteRtObjectDetector(
                        context = app,
                        assetName = spec.assetPath,
                        accelerator = Accelerator.CPU,
                        inputSize = spec.inputSize,
                        expectedOutput = spec.expectedOutput,
                    ).use { rawDetector ->
                        val detector = if (effectiveConfig.useAppearanceAssociation) {
                            AppearanceAugmentingDetector(rawDetector)
                        } else rawDetector
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

                        val observer = AnalysisPreviewObserver { previewFrame ->
                            _preview.value = previewFrame
                        }

                        ModularAnalysisEngine(
                            detector = detector,
                            tracker = ByteTrack(),
                            previewObserver = observer,
                        ).analyze(frameSource, effectiveConfig)
                    }
                }

                if (effectiveConfig.enableEvidence) persistEvidence(app, result)

                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.SUCCESS,
                    message = "Real analysis completed. Results, rules and evidence references came from the same run.",
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

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
        super.onCleared()
    }
}
