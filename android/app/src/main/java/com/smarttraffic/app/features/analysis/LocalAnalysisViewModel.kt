package com.smarttraffic.app.features.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.AnalysisDiagnostics
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.data.analysis.AnalysisRuntimeFactory
import com.smarttraffic.app.data.analysis.ExactPtsVideoFrameSource
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
import com.smarttraffic.app.domain.analysis.EvidenceArtifacts
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
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

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
    private val session: UnifiedAnalysisSession = application.cast<SmartTrafficApplication>().analysisHost.session
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
        if (session.state.value.phase == AnalysisSessionPhase.STARTING || session.state.value.phase == AnalysisSessionPhase.RUNNING) return

        _preview.value = null
        _state.value = AnalysisRunState(phase = AnalysisRunPhase.RUNNING, message = "Preparing detector and media decoder…")
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
                    useAppearanceAssociation = false,
                )

                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.MODEL_VALIDATE, modelId = effectiveConfig.detectorModel)
                val spec = DetectorModelRegistry.requireSpec(effectiveConfig.detectorModel)
                require(DetectorModelRegistry.isInstalled(app, spec)) { "Detector model is not installed: ${spec.assetPath}" }

                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.MODEL_INITIALIZE, modelId = spec.id, accelerator = "CPU")
                _state.value = AnalysisRunState(AnalysisRunPhase.RUNNING, "Initializing LiteRT CPU detector…")
                runtime = AnalysisRuntimeFactory.createDetector(app, spec.id, effectiveConfig.useAppearanceAssociation)

                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.MEDIA_OPEN, modelId = spec.id, accelerator = runtime!!.accelerator.name, mediaDescription = "type=$mediaType uri=$uri")
                _state.value = AnalysisRunState(AnalysisRunPhase.RUNNING, "Opening selected media…", runtime!!.accelerator.name)
                source = when (mediaType) {
                    AnalysisMediaType.VIDEO -> if (effectiveConfig.useGroundPlane) {
                        ExactPtsVideoFrameSource(app, uri)
                    } else {
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

                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.PIPELINE_START,
                    modelId = spec.id,
                    accelerator = runtime!!.accelerator.name,
                    mediaDescription = "${source!!.source.uri} type=$mediaType",
                    frameInfo = "${source!!.source.width}x${source!!.source.height} fps=${source!!.source.frameRate} pts=${source!!.source.timestampPrecision}",
                )

                var lastPreviewNs = Long.MIN_VALUE
                val previewIntervalNs = (1_000_000_000.0 / effectiveConfig.maximumPreviewFps.coerceAtLeast(0.1)).toLong()
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

                val started = session.start(source!!, engine, effectiveConfig, runtime!!.accelerator.name, runtime)
                if (!started) {
                    _state.value = AnalysisRunState(AnalysisRunPhase.ERROR, "Another analysis session is already active.", runtime!!.accelerator.name)
                    return@launch
                }
                sessionOwnsResources = true
                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.RUNNING,
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
                    if (effectiveConfig.enableEvidence) persistEvidence(app, result, mediaType)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                AnalysisDiagnostics.mark(app, runId, AnalysisDiagnostics.Stage.STOPPED, completed = true)
                throw cancelled
            } catch (t: Throwable) {
                AnalysisDiagnostics.mark(
                    app,
                    runId,
                    AnalysisDiagnostics.Stage.FAILED,
                    modelId = config.detectorModel,
                    accelerator = runtime?.accelerator?.name,
                    mediaDescription = source?.source?.uri ?: uri.toString(),
                    frameInfo = "${t::class.java.name}: ${t.message}",
                    completed = false,
                )
                _state.value = AnalysisRunState(AnalysisRunPhase.ERROR, t.message ?: t::class.java.simpleName, runtime?.accelerator?.name)
            } finally {
                if (!sessionOwnsResources) {
                    runCatching { source?.close() }
                    runCatching { runtime?.close() }
                }
            }
        }
    }

    private suspend fun persistEvidence(app: SmartTrafficApplication, result: AnalysisResult, mediaType: AnalysisMediaType) = withContext(Dispatchers.IO) {
        if (result.trafficEvents.isEmpty()) return@withContext
        val store = FileEvidenceStore(app)
        result.trafficEvents.filter { it.evidenceRequested }.forEach { event ->
            val track = result.tracks.firstOrNull { it.id == event.trackId } ?: return@forEach
            val observation = track.observations.minByOrNull { kotlin.math.abs(it.timestampMs - event.timestampMs) } ?: return@forEach
            val plate = result.plateReadings.filter { it.trackId == event.trackId }.minByOrNull { kotlin.math.abs(it.timestampMs - event.timestampMs) }
            val artifacts = captureEvidenceArtifacts(app, mediaType, result.source.uri, event.timestampMs, observation.detection)
            store.save(
                EvidenceRecord(
                    id = event.id,
                    eventId = event.id,
                    sourceId = result.source.id,
                    sourceUri = result.source.uri,
                    frameIndex = observation.frameIndex,
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
                artifacts,
            )
        }
    }

    private fun captureEvidenceArtifacts(
        app: SmartTrafficApplication,
        mediaType: AnalysisMediaType,
        sourceUri: String,
        timestampMs: Long,
        detection: com.smarttraffic.app.domain.analysis.Detection,
    ): EvidenceArtifacts {
        val bitmap = when (mediaType) {
            AnalysisMediaType.IMAGE -> app.contentResolver.openInputStream(Uri.parse(sourceUri)).use { stream ->
                requireNotNull(stream) { "Unable to reopen evidence image" }
                requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode evidence image" }
            }
            AnalysisMediaType.VIDEO -> MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(app, Uri.parse(sourceUri))
                    requireNotNull(retriever.getFrameAtTime(timestampMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)) {
                        "Unable to decode evidence video frame at ${timestampMs}ms"
                    }
                } finally {
                    retriever.release()
                }
            }
        }
        try {
            val frameJpeg = encodeBoundedJpeg(bitmap, 1920, 4 * 1024 * 1024)
            val crop = cropDetection(bitmap, detection)
            try {
                return EvidenceArtifacts(frameJpeg, encodeBoundedJpeg(crop, 640, 768 * 1024))
            } finally {
                if (crop !== bitmap && !crop.isRecycled) crop.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun cropDetection(bitmap: Bitmap, detection: com.smarttraffic.app.domain.analysis.Detection): Bitmap {
        val width = max(1f, detection.right - detection.left)
        val height = max(1f, detection.bottom - detection.top)
        val marginX = width * 0.10f
        val marginY = height * 0.15f
        val left = (detection.left - marginX).toInt().coerceIn(0, bitmap.width - 1)
        val top = (detection.top - marginY).toInt().coerceIn(0, bitmap.height - 1)
        val right = (detection.right + marginX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (detection.bottom + marginY).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun encodeBoundedJpeg(bitmap: Bitmap, maxDimension: Int, maxBytes: Int): ByteArray {
        val scale = min(1.0, maxDimension.toDouble() / max(bitmap.width, bitmap.height).toDouble())
        val width = max(1, (bitmap.width * scale).toInt())
        val height = max(1, (bitmap.height * scale).toInt())
        val working = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            for (quality in intArrayOf(92, 84, 76, 68, 60)) {
                val output = ByteArrayOutputStream()
                check(working.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Unable to encode evidence JPEG" }
                val bytes = output.toByteArray()
                if (bytes.size <= maxBytes) return bytes
            }
            error("Evidence JPEG exceeds bounded size of $maxBytes bytes")
        } finally {
            if (working !== bitmap && !working.isRecycled) working.recycle()
        }
    }
}

private inline fun <reified T : android.app.Application> android.app.Application.cast(): T =
    this as? T ?: error("Application must be ${T::class.java.name}")
