package com.smarttraffic.app.features.analysis

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.AnalysisDiagnostics
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.data.analysis.AnalysisRuntimeFactory
import com.smarttraffic.app.data.analysis.LocalImageFrameSource
import com.smarttraffic.app.data.analysis.LocalVideoFrameSource
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.data.vision.DetectorModelRegistry
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

/** Local Lab adapter. The execution lifecycle is owned by the application-scoped AnalysisHost. */
class LocalAnalysisViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val session: UnifiedAnalysisSession =
        application.cast<SmartTrafficApplication>().analysisHost.session
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
        _state.value = AnalysisRunState(
            phase = AnalysisRunPhase.RUNNING,
            message = "Preparing detector and media decoder…",
        )
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<SmartTrafficApplication>()
            val runId = AnalysisDiagnostics.newRun(app)
            var runtime: AnalysisRuntimeFactory.DetectorRuntime? = null
            var source: FrameSource? = null
            var sessionOwnsResources = false
            try {
                val persistedRules = TrafficRulePreferences.load(app)
                val effectiveConfig = config.copy(
                    enableRules = config.enableRules || persistedRules.enabled,
                    trafficRules = persistedRules,
                    enableEvidence = config.enableEvidence || persistedRules.preserveEvidence,
                    // Baseline device validation isolates detector/tracker from optional appearance work.
                    useAppearanceAssociation = false,
                )

                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.MODEL_VALIDATE,
                    modelId = effectiveConfig.detectorModel,
                )
                val spec = DetectorModelRegistry.requireSpec(effectiveConfig.detectorModel)
                require(DetectorModelRegistry.isInstalled(app, spec)) {
                    "Detector model is not installed: ${spec.assetPath}"
                }

                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.MODEL_INITIALIZE,
                    modelId = spec.id,
                    accelerator = "CPU",
                )
                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.RUNNING,
                    message = "Initializing LiteRT CPU detector…",
                )
                runtime = AnalysisRuntimeFactory.createDetector(
                    context = app,
                    modelId = spec.id,
                    useAppearanceAssociation = effectiveConfig.useAppearanceAssociation,
                )

                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.MEDIA_OPEN,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = "type=$mediaType uri=$uri",
                )
                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.RUNNING,
                    message = "Opening selected media…",
                    accelerator = runtime!!.accelerator.name,
                )
                source = when (mediaType) {
                    AnalysisMediaType.VIDEO -> LocalVideoFrameSource(app, uri)
                    AnalysisMediaType.IMAGE -> {
                        val bitmap = app.contentResolver.openInputStream(uri).use { stream ->
                            requireNotNull(stream) { "Unable to open selected image" }
                            requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode selected image" }
                        }
                        LocalImageFrameSource(bitmap, uri.toString())
                    }
                }

                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.PIPELINE_START,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = "${source!!.source.uri} type=$mediaType",
                    frameInfo = "${source!!.source.width}x${source!!.source.height} fps=${source!!.source.frameRate} pts=${source!!.source.timestampPrecision}",
                )

                var lastPreviewNs = Long.MIN_VALUE
                val previewIntervalNs = (1_000_000_000.0 / effectiveConfig.maximumPreviewFps.coerceAtLeast(0.1))
                    .toLong()
                val observer = AnalysisPreviewObserver { previewFrame ->
                    val nowNs = System.nanoTime()
                    if (lastPreviewNs == Long.MIN_VALUE || nowNs - lastPreviewNs >= previewIntervalNs) {
                        lastPreviewNs = nowNs
                        session.publishPreview(previewFrame)
                        _preview.value = previewFrame
                    }
                }
                val engine = ModularAnalysisEngine(
                    detector = runtime!!.detector,
                    tracker = ByteTrack(),
                    previewObserver = observer,
                    groundProjector = KotlinGroundProjector,
                    speedEstimator = KotlinSpeedEstimatorBackend,
                )

                val started = session.start(
                    source = source!!,
                    engine = engine,
                    config = effectiveConfig,
                    accelerator = runtime!!.accelerator.name,
                    runtime = runtime,
                )
                if (!started) {
                    _state.value = AnalysisRunState(
                        phase = AnalysisRunPhase.ERROR,
                        message = "Another analysis session is already active.",
                        accelerator = runtime!!.accelerator.name,
                    )
                    return@launch
                }
                sessionOwnsResources = true
                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.RUNNING,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = source!!.source.uri,
                    frameInfo = "${source!!.source.width}x${source!!.source.height} fps=${source!!.source.frameRate} pts=${source!!.source.timestampPrecision}",
                    completed = false,
                )
                session.awaitCompletion()

                val finalState = session.state.value
                _state.value = when (finalState.phase) {
                    AnalysisSessionPhase.COMPLETED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.COMPLETED, completed = true)
                        AnalysisRunState(AnalysisRunPhase.SUCCESS, finalState.message, finalState.accelerator, finalState.result)
                    }
                    AnalysisSessionPhase.FAILED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.FAILED, completed = false)
                        AnalysisRunState(AnalysisRunPhase.ERROR, finalState.message, finalState.accelerator, finalState.result)
                    }
                    AnalysisSessionPhase.STOPPED -> {
                        AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.STOPPED, completed = true)
                        AnalysisRunState(AnalysisRunPhase.ERROR, finalState.message, finalState.accelerator, finalState.result)
                    }
                    else -> _state.value
                }

                finalState.result?.let { result ->
                    if (effectiveConfig.enableEvidence) persistEvidence(app, result)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.STOPPED, completed = true)
                throw cancelled
            } catch (t: Throwable) {
                AnalysisDiagnostics.mark(
                    context = app,
                    runId = runId,
                    stage = AnalysisDiagnostics.Stage.FAILED,
                    modelId = config.detectorModel,
                    accelerator = runtime?.accelerator?.name,
                    mediaDescription = source?.source?.uri ?: uri.toString(),
                    frameInfo = "${t::class.java.name}: ${t.message}",
                    completed = false,
                )
                _state.value = AnalysisRunState(
                    phase = AnalysisRunPhase.ERROR,
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

    private suspend fun persistEvidence(app: SmartTrafficApplication, result: AnalysisResult) = withContext(Dispatchers.IO) {
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

private inline fun <reified T : android.app.Application> android.app.Application.cast(): T =
    this as? T ?: error("Application must be ${T::class.java.name}")
