package com.smarttraffic.app.features.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import kotlinx.coroutines.launch

@Composable
fun EvidenceScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val ar = AppSettings.language == AppLanguage.ARABIC
    val store = remember { FileEvidenceStore(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<EvidenceRecord>>(emptyList()) }

    LaunchedEffect(Unit) { records = store.list() }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("evidence"), style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            if (ar) "سجلات الأدلة تربط المخالفة بالمصدر والإطار والطابع الزمني والسرعة والمعايرة والنموذج." else
                "Evidence records link each confirmed event to its source, frame reference, timestamp, speed, calibration and model.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (records.isEmpty()) {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (ar) "لا توجد سجلات أدلة بعد" else "No evidence records yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (ar) "فعّل حفظ الأدلة في تشغيل التحليل بعد تفعيل قواعد المرور لإنشاء السجلات." else
                            "Enable evidence persistence in an analysis run after enabling traffic rules to create records.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (ar) "${records.size} سجل" else "${records.size} records", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    scope.launch {
                        store.clear()
                        records = emptyList()
                    }
                }) {
                    Icon(Icons.Filled.DeleteSweep, null)
                    Text(if (ar) "مسح" else "Clear")
                }
            }

            records.forEach { record -> EvidenceCard(record, ar) }
        }
    }
}

@Composable
private fun EvidenceCard(record: EvidenceRecord, ar: Boolean) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(record.eventType, style = MaterialTheme.typography.titleLarge)
            Metric(if (ar) "المسار" else "Track", record.trackId.toString())
            Metric(if (ar) "السرعة" else "Speed", "%.1f km/h".format(record.measuredSpeedKmh))
            Metric(if (ar) "الحد" else "Limit", "%.1f km/h".format(record.thresholdKmh))
            Metric(if (ar) "الثقة" else "Confidence", "%.0f%%".format(record.confidence * 100f))
            Metric(if (ar) "الزمن" else "Timestamp", "${record.timestampMs} ms")
            Metric(if (ar) "الإطار" else "Frame", record.frameIndex.toString())
            Metric(if (ar) "اللوحة" else "Plate", record.plateText ?: "—")
            Metric(if (ar) "المعايرة" else "Calibration", record.calibrationId ?: "—")
            Metric(if (ar) "النموذج" else "Model", record.detectorModel)
            Text(record.sourceUri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
