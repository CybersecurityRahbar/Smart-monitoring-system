package com.smarttraffic.app.features.analysis

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisResult

private enum class AnalysisSource { VIDEO, IMAGE }
private enum class AnalysisTestMode { DETECTION_TRACKING, SPEED_CALIBRATION, PLATE_READ }

@Composable
fun LocalAnalysisScreen(
    paddingValues: PaddingValues,
    viewModel: LocalAnalysisViewModel = viewModel(),
) {
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var source by remember { mutableStateOf(AnalysisSource.VIDEO) }
    var testMode by remember { mutableStateOf(AnalysisTestMode.DETECTION_TRACKING) }
    var useCalibratedHomography by remember { mutableStateOf(false) }
    var useVehicleKeypoints by remember { mutableStateOf(false) }
    var useDynamicKeypointHomography by remember { mutableStateOf(false) }
    var useOpticalFlowRefinement by remember { mutableStateOf(false) }
    var useSegmentationRefinement by remember { mutableStateOf(false) }
    var useReIdentification by remember { mutableStateOf(false) }
    var confidenceText by remember { mutableStateOf("25") }
    var referenceFramesText by remember { mutableStateOf("8") }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val modelSpec = remember { DetectorModelRegistry.requireSpec("yolo26n") }
    val modelInstalled = remember { DetectorModelRegistry.isInstalled(context, modelSpec) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
        if (uri != null) viewModel.reset()
    }

    val config = AnalysisConfig(
        detectorModel = modelSpec.id,
        tracker = "bytetrack",
        minimumDetectionConfidence = (confidenceText.toFloatOrNull() ?: 25f).coerceIn(1f, 100f) / 100f,
        minimumSpeedSamples = (referenceFramesText.toIntOrNull() ?: 8).coerceIn(4, 300),
        useGroundPlane = useCalibratedHomography,
        useVehicleKeypoints = useVehicleKeypoints,
        useDynamicKeypointHomography = useDynamicKeypointHomography,
        useOpticalFlowRefinement = useOpticalFlowRefinement,
        useSegmentationRefinement = useSegmentationRefinement,
        useReIdentification = useReIdentification,
        enablePlateRecognition = testMode == AnalysisTestMode.PLATE_READ,
        showRadarOverlay = true,
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(paddingValues).padding(horizontal = 18.dp, vertical = 16.dp),
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

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(tr("mediaSource"), style = MaterialTheme.typography.titleMedium)
                }
                Text(selectedUri?.toString() ?: tr("noMedia"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == AnalysisSource.VIDEO, onClick = { source = AnalysisSource.VIDEO }, label = { Text(labText("Video", "فيديو")) })
                    FilterChip(selected = source == AnalysisSource.IMAGE, onClick = { source = AnalysisSource.IMAGE }, label = { Text(labText("Image", "صورة")) })
                }
                Button(onClick = { picker.launch(arrayOf(if (source == AnalysisSource.VIDEO) "video/*" else "image/*")) }) {
                    Text(tr("chooseFromDevice"))
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(labText("Runtime readiness", "جاهزية التشغيل"), style = MaterialTheme.typography.titleMedium)
                ReadinessRow(labText("Detector model", "نموذج الكشف"), "${modelSpec.id} • ${if (modelInstalled) labText("installed", "مثبت") else labText("NOT installed", "غير مثبت")}")
                ReadinessRow(labText("Tracker", "التتبع"), "ByteTrack • Kalman + global assignment")
                ReadinessRow(labText("Physical speed", "السرعة الفيزيائية"), if (useCalibratedHomography) labText("requires validated calibration", "يتطلب معايرة موثقة") else labText("blocked until validated calibration is supplied", "محجوبة حتى يتم توفير معايرة موثقة"))
                Text(
                    labText(
                        "No model, no calibration and no unavailable backend is silently substituted. A failed capability is reported as an error rather than a fake measurement.",
                        "لا يتم تعويض النموذج أو المعايرة أو أي مكوّن غير متوفر بصمت. المكوّن غير المتوفر يظهر كخطأ بدل إنتاج قياس وهمي.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(labText("Test profile", "ملف الاختبار"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.DETECTION_TRACKING, onClick = { testMode = AnalysisTestMode.DETECTION_TRACKING }, label = { Text(labText("Detect + track", "كشف + تتبع")) })
                    FilterChip(selected = testMode == AnalysisTestMode.SPEED_CALIBRATION, onClick = { testMode = AnalysisTestMode.SPEED_CALIBRATION }, label = { Text(labText("Speed / calibration", "السرعة / المعايرة")) })
                    FilterChip(selected = testMode == AnalysisTestMode.PLATE_READ, onClick = { testMode = AnalysisTestMode.PLATE_READ }, label = { Text(labText("Plate / OCR", "اللوحة / OCR")) })
                }
                OutlinedNumberField(value = referenceFramesText, onValueChange = { referenceFramesText = it.filter(Char::isDigit).take(4) }, label = labText("Minimum speed samples", "الحد الأدنى لعينات السرعة"))
                OutlinedNumberField(value = confidenceText, onValueChange = { confidenceText = it.filter(Char::isDigit).take(3) }, label = labText("Report confidence (%)", "ثقة الكشف المعروضة (%)"))
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(labText("Available vision backends", "مكوّنات الرؤية المتوفرة"), style = MaterialTheme.typography.titleMedium)
                AnalysisToggle(labText("Validated road homography", "Homography موثقة للطريق"), useCalibratedHomography) { useCalibratedHomography = it }
                AnalysisToggle(labText("Vehicle keypoints", "Vehicle Keypoints"), useVehicleKeypoints, enabled = false) {}
                AnalysisToggle(labText("Dynamic keypoint homography", "Dynamic Keypoint Homography"), useDynamicKeypointHomography, enabled = false) {}
                AnalysisToggle(labText("Optical flow refinement", "Optical Flow"), useOpticalFlowRefinement, enabled = false) {}
                AnalysisToggle(labText("Segmentation refinement", "Segmentation"), useSegmentationRefinement, enabled = false) {}
                AnalysisToggle(labText("Appearance Re-ID", "Appearance Re-ID"), useReIdentification, enabled = false) {}
                Text(
                    labText(
                        "Disabled means the backend is not installed yet; there is no hidden no-op implementation.",
                        "التعطيل يعني أن الـbackend لم يُدمج بعد؛ لا توجد نسخة وهمية تعمل في الخلفية.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                val effectiveConfig = when (testMode) {
                    AnalysisTestMode.DETECTION_TRACKING -> config.copy(useGroundPlane = false)
                    AnalysisTestMode.SPEED_CALIBRATION -> config.copy(useGroundPlane = true)
                    AnalysisTestMode.PLATE_READ -> config.copy(enablePlateRecognition = true)
                }
                viewModel.run(uri, if (source == AnalysisSource.VIDEO) AnalysisMediaType.VIDEO else AnalysisMediaType.IMAGE, effectiveConfig)
            },
            enabled = selectedUri != null && modelInstalled && state.phase != AnalysisRunPhase.RUNNING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(labText("Run real analysis", "تشغيل التحليل الفعلي"))
        }

        if (state.message != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(runTitle(state.phase), style = MaterialTheme.typography.titleMedium)
                    Text(state.message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        state.result?.let { result -> AnalysisResultCard(result) }
    }
}

@Composable
private fun AnalysisResultCard(result: AnalysisResult) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(labText("Executed metrics", "القياسات الناتجة من التنفيذ"), style = MaterialTheme.typography.titleMedium)
            val m = result.metrics
            MetricRow(labText("Frames", "الإطارات"), m.framesProcessed.toString())
            MetricRow(labText("Reportable detections", "الاكتشافات المعروضة"), m.detections.toString())
            MetricRow(labText("Tracking detections", "مدخلات التتبع"), m.trackingDetections.toString())
            MetricRow(labText("Tracks", "المسارات"), result.tracks.size.toString())
            MetricRow(labText("Peak active tracks", "أقصى مسارات نشطة"), m.peakActiveTracks.toString())
            MetricRow(labText("Inference median", "وسيط زمن الاستدلال"), formatMs(m.inferenceMedianLatencyMs))
            MetricRow(labText("Inference P95", "P95 زمن الاستدلال"), formatMs(m.inferenceP95LatencyMs))
            MetricRow(labText("Processing FPS", "FPS المعالجة"), m.processingFps?.let { "%.2f".format(it) } ?: "—")
            MetricRow(labText("Dropped frames", "الإطارات المفقودة"), m.droppedFrames.toString())
            MetricRow(labText("Association misses", "فشل ربط التتبع"), m.trackingAssociationMisses.toString())
            MetricRow(labText("Speed estimates", "تقديرات السرعة"), m.speedEstimates.toString())
            MetricRow(labText("Rejected speed tracks", "مسارات السرعة المرفوضة"), m.rejectedSpeedEstimates.toString())
            Text(
                labText(
                    "Speed is intentionally absent when calibration/quality gates fail. An uncertainty value is not ground-truth accuracy.",
                    "يتم حجب السرعة عمدًا عند فشل بوابات المعايرة/الجودة. وقيمة عدم اليقين ليست دقة مقارنة بالحقيقة الأرضية.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadinessRow(title: String, value: String) = MetricRow(title, value)

@Composable
private fun MetricRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AnalysisToggle(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OutlinedNumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatMs(value: Double?): String = value?.let { "%.2f ms".format(it) } ?: "—"

private fun runTitle(phase: AnalysisRunPhase): String = when (phase) {
    AnalysisRunPhase.IDLE -> labText("Ready", "جاهز")
    AnalysisRunPhase.RUNNING -> labText("Running real pipeline", "تشغيل المسار الفعلي")
    AnalysisRunPhase.SUCCESS -> labText("Analysis completed", "اكتمل التحليل")
    AnalysisRunPhase.ERROR -> labText("Analysis failed", "فشل التحليل")
}

private fun labText(en: String, ar: String): String =
    if (AppSettings.language == AppLanguage.ARABIC) ar else en
