package com.smarttraffic.app.features.analysis

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.analysis.FileCalibrationStore
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisResult
import com.smarttraffic.app.domain.analysis.CalibrationProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AnalysisSource { VIDEO, IMAGE }
private enum class AnalysisTestMode { DETECTION_TRACKING, SPEED_CALIBRATION, PLATE_READ }

@Composable
fun LocalAnalysisScreen(
    paddingValues: PaddingValues,
    viewModel: LocalAnalysisViewModel = viewModel(),
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var source by remember { mutableStateOf(AnalysisSource.VIDEO) }
    var testMode by remember { mutableStateOf(AnalysisTestMode.DETECTION_TRACKING) }
    var useCalibratedHomography by remember { mutableStateOf(false) }
    var confidenceText by remember { mutableStateOf("25") }
    var referenceFramesText by remember { mutableStateOf("8") }
    var calibrationProfiles by remember { mutableStateOf<List<CalibrationProfile>>(emptyList()) }
    var selectedCalibrationId by remember { mutableStateOf<String?>(null) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val previousAnalysisWarning = (context.applicationContext as? SmartTrafficApplication)?.previousAnalysisWarning
    val modelSpec = remember { DetectorModelRegistry.requireSpec("yolo26n") }
    val modelInstalled = remember { DetectorModelRegistry.isInstalled(context, modelSpec) }
    val selectedCalibration = calibrationProfiles.firstOrNull { it.id == selectedCalibrationId }
    val physicalSpeedStatus = when {
        !useCalibratedHomography -> "blocked until calibration"
        selectedCalibration == null -> "blocked: no calibration selected"
        else -> "requires exact source timestamps plus dimension/quality validation"
    }

    LaunchedEffect(Unit) {
        calibrationProfiles = withContext(Dispatchers.IO) {
            FileCalibrationStore(context).list()
        }
        if (selectedCalibrationId == null) {
            selectedCalibrationId = calibrationProfiles.firstOrNull()?.id
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
        if (uri != null) viewModel.reset()
    }

    val config = AnalysisConfig(
        detectorModel = modelSpec.id,
        tracker = "bytetrack",
        minimumDetectionConfidence = (confidenceText.toFloatOrNull() ?: 25f).coerceIn(1f, 100f) / 100f,
        minimumSpeedSamples = (referenceFramesText.toIntOrNull() ?: 8).coerceIn(4, 300),
        calibration = selectedCalibration,
        useGroundPlane = useCalibratedHomography && selectedCalibration != null,
        showRadarOverlay = true,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(paddingValues)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Analytics, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Column {
                Text(tr("localAnalysisLab"), style = MaterialTheme.typography.headlineMedium)
                Text(tr("analysisDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        previousAnalysisWarning?.let { warning ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Previous analysis diagnostic", style = MaterialTheme.typography.titleMedium)
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "This marker is retained specifically so native/runtime process deaths can be located to the last risky stage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Analysis media", style = MaterialTheme.typography.titleMedium)
                }
                Text(selectedUri?.toString() ?: "No media selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Video analysis processes frames in source order. Processing FPS is measured independently from nominal source FPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == AnalysisSource.VIDEO, onClick = { source = AnalysisSource.VIDEO }, label = { Text("Video") })
                    FilterChip(selected = source == AnalysisSource.IMAGE, onClick = { source = AnalysisSource.IMAGE }, label = { Text("Image") })
                }
                Button(onClick = { picker.launch(arrayOf(if (source == AnalysisSource.VIDEO) "video/*" else "image/*")) }) {
                    Text("Choose from device")
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Runtime readiness", style = MaterialTheme.typography.titleMedium)
                MetricRow("Detector", "${modelSpec.id} • ${if (modelInstalled) "installed" else "MISSING"}")
                MetricRow("Tracker", "ByteTrack + Kalman + global assignment")
                MetricRow("Inference backend", state.accelerator ?: "CPU • LiteRT")
                MetricRow("Live laboratory", if (source == AnalysisSource.VIDEO) "frame-by-frame preview enabled" else "single-frame preview")
                MetricRow("Physical speed", physicalSpeedStatus)
                Text(
                    "The preview is driven by the same detector/tracker pipeline used for final metrics; there is no separate mock animation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Calibration profile", style = MaterialTheme.typography.titleMedium)
                if (calibrationProfiles.isEmpty()) {
                    Text(
                        "No saved calibration profile. Create one in More → Camera Calibration before enabling physical speed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Select a validated profile produced by the calibration lab. The analysis pipeline will still validate its dimensions and quality against the selected media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        calibrationProfiles.forEach { profile ->
                            FilterChip(
                                selected = profile.id == selectedCalibrationId,
                                onClick = { selectedCalibrationId = profile.id },
                                label = { Text("${profile.id} v${profile.version}") },
                            )
                        }
                    }
                    selectedCalibration?.let { profile ->
                        MetricRow("Calibration dimensions", "${profile.imageWidth} × ${profile.imageHeight}")
                        MetricRow("Inlier ratio", "%.3f".format(profile.homographyInlierRatio ?: 0.0))
                        MetricRow("Target reprojection error", "%.3f m".format(profile.reprojectionErrorTargetUnits ?: Double.NaN))
                    }
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Test profile", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.DETECTION_TRACKING, onClick = { testMode = AnalysisTestMode.DETECTION_TRACKING }, label = { Text("Detect + track") })
                    FilterChip(selected = testMode == AnalysisTestMode.SPEED_CALIBRATION, onClick = { testMode = AnalysisTestMode.SPEED_CALIBRATION }, label = { Text("Speed") })
                    FilterChip(selected = testMode == AnalysisTestMode.PLATE_READ, onClick = { testMode = AnalysisTestMode.PLATE_READ }, label = { Text("Plate / OCR") }, enabled = false)
                }
                Text(
                    "Plate / OCR is shown only as an architecture placeholder until a licensed Android-deployable plate detector and OCR backend is installed; it cannot be executed in this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = referenceFramesText,
                    onValueChange = { referenceFramesText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minimum speed samples") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confidenceText,
                    onValueChange = { confidenceText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Detection confidence (%)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Validated road homography", style = MaterialTheme.typography.bodyMedium)
                        Text("Requires a selected saved calibration profile. Source dimensions and quality gates are checked by the real analysis pipeline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = useCalibratedHomography,
                        enabled = selectedCalibration != null,
                        onCheckedChange = { useCalibratedHomography = it },
                    )
                }
            }
        }

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                if (testMode == AnalysisTestMode.PLATE_READ) return@Button
                val effectiveConfig = config.copy(
                    useGroundPlane = testMode == AnalysisTestMode.SPEED_CALIBRATION && useCalibratedHomography,
                    enablePlateRecognition = false,
                )
                viewModel.run(
                    uri,
                    if (source == AnalysisSource.VIDEO) AnalysisMediaType.VIDEO else AnalysisMediaType.IMAGE,
                    effectiveConfig,
                )
            },
            enabled = selectedUri != null && modelInstalled && state.phase != AnalysisRunPhase.RUNNING && testMode != AnalysisTestMode.PLATE_READ,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Run real analysis")
        }

        if (state.phase == AnalysisRunPhase.RUNNING) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Analysis running", style = MaterialTheme.typography.titleMedium)
                    Text("The newest decoded frame and the tracking radar are shown as processing progresses. Playback is never intentionally delayed to match a slower analysis speed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        AnalysisRadarPreview(preview, Modifier.fillMaxWidth())

        if (state.message != null && state.phase != AnalysisRunPhase.RUNNING) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.phase == AnalysisRunPhase.SUCCESS) "Analysis completed" else "Analysis error", style = MaterialTheme.typography.titleMedium)
                    Text(state.message ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        state.result?.let { result -> AnalysisResultCard(result, state.accelerator) }
    }
}

@Composable
private fun AnalysisResultCard(result: AnalysisResult, accelerator: String?) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Executed metrics", style = MaterialTheme.typography.titleMedium)
            val m = result.metrics
            MetricRow("Inference backend", accelerator ?: "—")
            MetricRow("Speed backend", m.speedEstimatorBackend)
            MetricRow("Source FPS", m.decodeFps?.let { "%.2f".format(it) } ?: "—")
            MetricRow("Timestamp precision", m.timestampPrecision.name)
            MetricRow("Frames", m.framesProcessed.toString())
            MetricRow("Detections", m.detections.toString())
            MetricRow("Tracks", result.tracks.size.toString())
            MetricRow("Confirmed tracks", m.confirmedTracks.toString())
            MetricRow("Recovered tracks", m.recoveredTracks.toString())
            MetricRow("Maximum recovery gap", if (m.maximumRecoveryGapMs > 0L) "${m.maximumRecoveryGapMs} ms" else "—")
            MetricRow("Peak active tracks", m.peakActiveTracks.toString())
            MetricRow("Inference median", formatMs(m.inferenceMedianLatencyMs))
            MetricRow("Inference P95", formatMs(m.inferenceP95LatencyMs))
            MetricRow("Processing FPS", m.processingFps?.let { "%.2f".format(it) } ?: "—")
            MetricRow("End-to-end / frame", formatMs(m.endToEndLatencyMs))
            MetricRow("Dropped frame gaps", m.droppedFrames.toString())
            MetricRow("Tracking misses", m.trackingAssociationMisses.toString())
            MetricRow("Speed estimates", m.speedEstimates.toString())
            MetricRow("Rejected speed tracks", m.rejectedSpeedEstimates.toString())
            MetricRow("ID switches", m.idSwitches?.toString() ?: "Not benchmarked")
            MetricRow("Track fragmentations", m.trackFragmentations?.toString() ?: "Not benchmarked")
            Text(
                "A speed value is published only after calibration and trajectory quality gates pass. ID-switch and fragmentation metrics require external ground-truth annotations and are therefore not guessed here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatMs(value: Double?): String = value?.let { "%.2f ms".format(it) } ?: "—"
