package com.smarttraffic.app.features.evidence

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.evidence.FileEvidenceStore
import com.smarttraffic.app.domain.analysis.EvidenceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EvidenceScreen(paddingValues: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val ar = AppSettings.language == AppLanguage.ARABIC
    val store = remember { FileEvidenceStore(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<EvidenceRecord>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        records = runCatching { store.list() }.getOrElse {
            errorMessage = it.message ?: if (ar) "تعذر قراءة الأدلة" else "Unable to read evidence"
            emptyList()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("evidence"), style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            if (ar) "سجلات الأدلة تربط المخالفة بالمصدر والإطار والطابع الزمني والسرعة والمعايرة والنموذج، وتعرض artifacts المحفوظة بعد التحقق من SHA-256." else
                "Evidence records link each event to its source, frame reference, timestamp, speed, calibration and model, with persisted artifacts rendered only after SHA-256 verification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        errorMessage?.let { message ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }

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
                        runCatching { store.clear(); records = emptyList() }.onFailure {
                            errorMessage = it.message ?: if (ar) "تعذر المسح" else "Unable to clear evidence"
                        }
                    }
                }) {
                    Icon(Icons.Filled.DeleteSweep, null)
                    Text(if (ar) "مسح" else "Clear")
                }
            }

            records.forEach { record ->
                EvidenceCard(record, ar, store)
            }
        }
    }
}

@Composable
private fun EvidenceCard(record: EvidenceRecord, ar: Boolean, store: FileEvidenceStore) {
    var frameBitmap by remember(record.id, record.frameSha256) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var vehicleBitmap by remember(record.id, record.vehicleCropSha256) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var artifactError by remember(record.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(record.id, record.frameSha256, record.vehicleCropSha256, record.plateCropSha256) {
        withContext(Dispatchers.IO) {
            runCatching {
                store.readArtifact(record, FileEvidenceStore.ArtifactKind.FRAME)?.let {
                    frameBitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                }
                store.readArtifact(record, FileEvidenceStore.ArtifactKind.VEHICLE)?.let {
                    vehicleBitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                }
            }.onFailure {
                artifactError = it.message ?: if (ar) "تعذر قراءة artifact" else "Unable to read artifact"
            }
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(record.eventType, style = MaterialTheme.typography.titleLarge)
            artifactError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                frameBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (ar) "إطار الدليل" else "Evidence frame",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).height(150.dp),
                    )
                }
                vehicleBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (ar) "قصاصة المركبة" else "Vehicle crop",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).height(150.dp),
                    )
                }
            }
            Metric(if (ar) "المسار" else "Track", record.trackId.toString())
            Metric(if (ar) "السرعة" else "Speed", "%.1f km/h".format(record.measuredSpeedKmh))
            Metric(if (ar) "الحد" else "Limit", "%.1f km/h".format(record.thresholdKmh))
            Metric(if (ar) "الثقة" else "Confidence", "%.0f%%".format(record.confidence * 100f))
            Metric(if (ar) "الزمن" else "Timestamp", "${record.timestampMs} ms")
            Metric(if (ar) "الإطار" else "Frame", record.frameIndex.toString())
            Metric(if (ar) "اللوحة" else "Plate", record.plateText ?: "—")
            Metric(if (ar) "المعايرة" else "Calibration", record.calibrationId ?: "—")
            Metric(if (ar) "النموذج" else "Model", record.detectorModel)
            Metric(if (ar) "SHA الإطار" else "Frame SHA", record.frameSha256 ?: "metadata-only")
            Metric(if (ar) "SHA المركبة" else "Vehicle SHA", record.vehicleCropSha256 ?: "metadata-only")
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
