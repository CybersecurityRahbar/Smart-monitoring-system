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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.domain.analysis.AnalysisConfig

private enum class AnalysisSource { VIDEO, IMAGE }
private enum class AnalysisTestMode { FULL_PIPELINE, DETECTION_TRACKING, SPEED_CALIBRATION, PLATE_READ }

@Composable
fun LocalAnalysisScreen(paddingValues: PaddingValues) {
    var selectedMedia by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(AnalysisSource.VIDEO) }
    var testMode by remember { mutableStateOf(AnalysisTestMode.FULL_PIPELINE) }
    var showRadarOverlay by remember { mutableStateOf(true) }
    var useCalibratedHomography by remember { mutableStateOf(true) }
    var useVehicleKeypoints by remember { mutableStateOf(false) }
    var useDynamicKeypointHomography by remember { mutableStateOf(false) }
    var useOpticalFlowRefinement by remember { mutableStateOf(false) }
    var useSegmentationRefinement by remember { mutableStateOf(false) }
    var useReIdentification by remember { mutableStateOf(true) }
    var knownDistanceText by remember { mutableStateOf("10") }
    var referenceFramesText by remember { mutableStateOf("30") }
    var confidenceText by remember { mutableStateOf("70") }
    var testState by remember { mutableStateOf(TestState.IDLE) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedMedia = uri?.toString()
        if (uri != null) testState = TestState.READY
    }

    val analysisConfig = AnalysisConfig(
        minimumDetectionConfidence = (confidenceText.toFloatOrNull() ?: 70f).coerceIn(1f, 100f) / 100f,
        minimumSpeedSamples = (referenceFramesText.toIntOrNull() ?: 30).coerceIn(4, 300),
        useGroundPlane = useCalibratedHomography,
        useVehicleKeypoints = useVehicleKeypoints,
        useDynamicKeypointHomography = useDynamicKeypointHomography,
        useOpticalFlowRefinement = useOpticalFlowRefinement,
        useSegmentationRefinement = useSegmentationRefinement,
        useReIdentification = useReIdentification,
        showRadarOverlay = showRadarOverlay,
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

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(tr("mediaSource"), style = MaterialTheme.typography.titleMedium)
                }
                Text(selectedMedia ?: tr("noMedia"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == AnalysisSource.VIDEO, onClick = { source = AnalysisSource.VIDEO }, label = { Text(labText("Video", "فيديو")) })
                    FilterChip(selected = source == AnalysisSource.IMAGE, onClick = { source = AnalysisSource.IMAGE }, label = { Text(labText("Image", "صورة")) })
                }
                Button(onClick = { mediaPicker.launch(arrayOf(if (source == AnalysisSource.VIDEO) "video/*" else "image/*")) }) {
                    Text(tr("chooseFromDevice"))
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(labText("Test profile", "ملف الاختبار"), style = MaterialTheme.typography.titleMedium)
                Text(
                    labText(
                        "The Lab replays the same analysis engine used by Live Radar. Radar is only the visualization layer; it is not a second speed algorithm.",
                        "المختبر يعيد تشغيل محرك التحليل نفسه المستخدم في الرادار المباشر. الرادار طبقة عرض للنتائج وليس خوارزمية سرعة ثانية.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.FULL_PIPELINE, onClick = { testMode = AnalysisTestMode.FULL_PIPELINE }, label = { Text(labText("Full pipeline", "المسار الكامل")) })
                    FilterChip(selected = testMode == AnalysisTestMode.DETECTION_TRACKING, onClick = { testMode = AnalysisTestMode.DETECTION_TRACKING }, label = { Text(labText("Detect + track", "كشف + تتبع")) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.SPEED_CALIBRATION, onClick = { testMode = AnalysisTestMode.SPEED_CALIBRATION }, label = { Text(labText("Speed / calibration", "السرعة / المعايرة")) })
                    FilterChip(selected = testMode == AnalysisTestMode.PLATE_READ, onClick = { testMode = AnalysisTestMode.PLATE_READ }, label = { Text(labText("Plate / OCR", "اللوحة / OCR")) })
                }

                OutlinedTextField(
                    value = knownDistanceText,
                    onValueChange = { knownDistanceText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                    label = { Text(labText("Known reference distance (m)", "المسافة المرجعية المعروفة (متر)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = referenceFramesText,
                    onValueChange = { referenceFramesText = it.filter(Char::isDigit).take(4) },
                    label = { Text(labText("Analysis sample window", "نافذة عينات التحليل")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confidenceText,
                    onValueChange = { confidenceText = it.filter(Char::isDigit).take(3) },
                    label = { Text(labText("Minimum detection confidence (%)", "الحد الأدنى لثقة الكشف (%)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(labText("Advanced vision stack", "حزمة الرؤية المتقدمة"), style = MaterialTheme.typography.titleMedium)
                Text(
                    labText(
                        "Every switch changes the AnalysisConfig passed to the same engine. Methods are independent so experiments can be compared without changing the Lab workflow.",
                        "كل مفتاح يغير إعدادات AnalysisConfig المرسلة إلى المحرك نفسه. التقنيات مستقلة حتى نستطيع مقارنة التجارب دون تغيير طريقة عمل المختبر.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AnalysisToggle(labText("Calibrated road homography", "Homography معايرة سطح الطريق"), useCalibratedHomography) { useCalibratedHomography = it }
                AnalysisToggle(labText("Vehicle keypoints", "نقاط المركبة Vehicle Keypoints"), useVehicleKeypoints) { useVehicleKeypoints = it }
                AnalysisToggle(labText("Keypoint + dynamic homography research mode", "وضع البحث: Keypoints + Dynamic Homography"), useDynamicKeypointHomography) { useDynamicKeypointHomography = it }
                AnalysisToggle(labText("Optical-flow refinement", "تحسين Optical Flow"), useOpticalFlowRefinement) { useOpticalFlowRefinement = it }
                AnalysisToggle(labText("Segmentation refinement", "تحسين بالتقسيم Segmentation"), useSegmentationRefinement) { useSegmentationRefinement = it }
                AnalysisToggle(labText("Appearance Re-ID for tracking", "إعادة التعرف البصري Re-ID للتتبع"), useReIdentification) { useReIdentification = it }
                AnalysisToggle(labText("Radar overlay / trajectories", "طبقة الرادار / مسارات الحركة"), showRadarOverlay) { showRadarOverlay = it }

                Text(
                    labText(
                        "Research note: the keypoint/dynamic-homography method is experimental and must be reported with measured error; it does not automatically become enforcement-grade.",
                        "ملاحظة بحثية: طريقة Keypoints مع Dynamic Homography تجريبية ويجب عرض خطئها المقاس؛ لا تصبح تلقائيًا طريقة إنفاذ رسمية.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = {
                testState = TestState.RUNNING
                testMessage = labText(
                    "Test configuration compiled. The runtime engine will receive detector=${analysisConfig.detectorModel}, tracker=${analysisConfig.tracker}, keypoints=${analysisConfig.useVehicleKeypoints}, dynamicHomography=${analysisConfig.useDynamicKeypointHomography}, opticalFlow=${analysisConfig.useOpticalFlowRefinement}, segmentation=${analysisConfig.useSegmentationRefinement}, reID=${analysisConfig.useReIdentification}.",
                    "تم تجهيز إعدادات الاختبار. سيستقبل المحرك إعدادات الكشف=${analysisConfig.detectorModel} والتتبع=${analysisConfig.tracker} وKeypoints=${analysisConfig.useVehicleKeypoints} وDynamicHomography=${analysisConfig.useDynamicKeypointHomography} وOpticalFlow=${analysisConfig.useOpticalFlowRefinement} وSegmentation=${analysisConfig.useSegmentationRefinement} وReID=${analysisConfig.useReIdentification}.",
                )
                testState = TestState.WAITING_ENGINE
            },
            enabled = selectedMedia != null && testState != TestState.WAITING_ENGINE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(labText("Start analysis test", "بدء اختبار التحليل"))
        }

        if (testMessage != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(testStatusTitle(testState), style = MaterialTheme.typography.titleMedium)
                    Text(testMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(labText("What the test measures", "ما الذي يقيسه الاختبار"), style = MaterialTheme.typography.titleMedium)
                MeasurementRow(labText("Detection", "الكشف"), labText("boxes • class • confidence • recall/precision/mAP when ground truth exists", "الصناديق • الفئة • الثقة • Precision/Recall/mAP عند توفر الحقيقة الأرضية"))
                MeasurementRow(labText("Tracking", "التتبع"), labText("stable IDs • trajectories • HOTA/IDF1/MOTA • ID switches", "المعرّفات الثابتة • المسارات • HOTA/IDF1/MOTA • تبديلات الهوية"))
                MeasurementRow(labText("Geometry", "الهندسة"), labText("pixel → road meters • homography reprojection error", "بكسل → أمتار على الطريق • خطأ إسقاط Homography"))
                MeasurementRow(labText("Speed", "السرعة"), labText("m/s + km/h • MAE • RMSE • P50/P90/P95 • ±5/10/20%", "م/ث + كم/س • MAE • RMSE • P50/P90/P95 • ±5/10/20%"))
                MeasurementRow(labText("Plate/OCR", "اللوحة/OCR"), labText("plate confidence • exact-match • temporal consensus", "ثقة اللوحة • التطابق الكامل • الإجماع عبر الإطارات"))
                MeasurementRow(labText("Runtime", "الأداء"), labText("decode FPS • inference latency • end-to-end latency • dropped frames", "FPS فك الترميز • زمن الاستدلال • الزمن الكلي • الإطارات المفقودة"))
            }
        }
    }
}

@Composable
private fun AnalysisToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MeasurementRow(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private enum class TestState { IDLE, READY, RUNNING, WAITING_ENGINE }

private fun testStatusTitle(state: TestState): String = when (state) {
    TestState.IDLE -> labText("Idle", "جاهز")
    TestState.READY -> labText("Media ready", "الوسائط جاهزة")
    TestState.RUNNING -> labText("Preparing", "جاري التجهيز")
    TestState.WAITING_ENGINE -> labText("Engine adapter pending", "واجهة محرك التحليل لم تُربط بعد")
}

private fun labText(en: String, ar: String): String = if (com.smarttraffic.app.core.AppSettings.language == com.smarttraffic.app.core.AppLanguage.ARABIC) ar else en
