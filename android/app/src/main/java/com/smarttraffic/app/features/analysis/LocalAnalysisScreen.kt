package com.smarttraffic.app.features.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

private enum class AnalysisSource { VIDEO, IMAGE }
private enum class AnalysisTestMode { FULL_PIPELINE, DETECTION_TRACKING, SPEED_CALIBRATION, PLATE_READ }

@Composable
fun LocalAnalysisScreen(paddingValues: PaddingValues) {
    var selectedMedia by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(AnalysisSource.VIDEO) }
    var testMode by remember { mutableStateOf(AnalysisTestMode.FULL_PIPELINE) }
    var useRadarOverlay by remember { mutableStateOf(true) }
    var knownDistanceText by remember { mutableStateOf("10") }
    var referenceFramesText by remember { mutableStateOf("30") }
    var confidenceText by remember { mutableStateOf("70") }
    var testState by remember { mutableStateOf(TestState.IDLE) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedMedia = uri?.toString()
        if (uri != null) testState = TestState.READY
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(paddingValues)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Analytics, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            Column {
                Text(tr("localAnalysisLab"), style = MaterialTheme.typography.headlineMedium)
                Text(tr("analysisDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(22.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(tr("mediaSource"), style = MaterialTheme.typography.titleMedium)
                }
                Text(selectedMedia ?: tr("noMedia"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == AnalysisSource.VIDEO, onClick = { source = AnalysisSource.VIDEO }, label = { Text("Video") })
                    FilterChip(selected = source == AnalysisSource.IMAGE, onClick = { source = AnalysisSource.IMAGE }, label = { Text("Image") })
                }
                Button(onClick = { mediaPicker.launch(arrayOf(if (source == AnalysisSource.VIDEO) "video/*" else "image/*")) }) {
                    Text(tr("chooseFromDevice"))
                }
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Test plan", style = MaterialTheme.typography.titleMedium)
                Text("اختبار محلي قابل للتكرار قبل ربط ESP32-CAM. الرادار هنا ليس خوارزمية منفصلة؛ هو طبقة عرض لنتائج نفس محرك الكشف والتتبع والسرعة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.FULL_PIPELINE, onClick = { testMode = AnalysisTestMode.FULL_PIPELINE }, label = { Text("Full") })
                    FilterChip(selected = testMode == AnalysisTestMode.DETECTION_TRACKING, onClick = { testMode = AnalysisTestMode.DETECTION_TRACKING }, label = { Text("Detect + Track") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = testMode == AnalysisTestMode.SPEED_CALIBRATION, onClick = { testMode = AnalysisTestMode.SPEED_CALIBRATION }, label = { Text("Speed / calibration") })
                    FilterChip(selected = testMode == AnalysisTestMode.PLATE_READ, onClick = { testMode = AnalysisTestMode.PLATE_READ }, label = { Text("Plate / OCR") })
                }

                OutlinedTextField(
                    value = knownDistanceText,
                    onValueChange = { knownDistanceText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Known reference distance (m)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = referenceFramesText,
                    onValueChange = { referenceFramesText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Reference frame window") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confidenceText,
                    onValueChange = { confidenceText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Minimum confidence (%)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FilterChip(
                    selected = useRadarOverlay,
                    onClick = { useRadarOverlay = !useRadarOverlay },
                    label = { Text("Show radar overlay / trajectories") },
                )

                Button(
                    onClick = {
                        testState = TestState.RUNNING
                        testMessage = "تم تجهيز جلسة الاختبار: سيتم تمرير الوسائط عبر الكشف والتتبع والقياس والمعايرة وفق الملف المحدد. لم يتم تشغيل محرك الرؤية الفعلي بعد."
                        testState = TestState.WAITING_ENGINE
                    },
                    enabled = selectedMedia != null && testState != TestState.WAITING_ENGINE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    Text("Start analysis test")
                }
            }
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
                Text("Measurements produced by the eventual engine", style = MaterialTheme.typography.titleMedium)
                Text("Detection: box + class + confidence", style = MaterialTheme.typography.bodySmall)
                Text("Tracking: stable ID + trajectory + track confidence", style = MaterialTheme.typography.bodySmall)
                Text("Geometry: pixel point → calibrated road coordinates (m)", style = MaterialTheme.typography.bodySmall)
                Text("Motion: distance/time → filtered speed (km/h) + uncertainty", style = MaterialTheme.typography.bodySmall)
                Text("Plate: plate crop → rectification → OCR → normalized plate + confidence", style = MaterialTheme.typography.bodySmall)
                Text("Validation: MAE / median error / percentile error / ID switches / dropped tracks", style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("analysisPipeline"), style = MaterialTheme.typography.titleMedium)
                Text(tr("analysisPipelineDetail"))
                Text(tr("analysisEngineConnectionDetail"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private enum class TestState { IDLE, READY, RUNNING, WAITING_ENGINE }

private fun testStatusTitle(state: TestState): String = when (state) {
    TestState.IDLE -> "Idle"
    TestState.READY -> "Ready"
    TestState.RUNNING -> "Preparing"
    TestState.WAITING_ENGINE -> "Analysis engine not connected yet"
}
