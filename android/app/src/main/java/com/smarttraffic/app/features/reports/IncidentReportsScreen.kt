package com.smarttraffic.app.features.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.reports.FileIncidentReportStore
import com.smarttraffic.app.domain.analysis.IncidentReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentReportsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val store = remember { FileIncidentReportStore(context) }
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("Traffic violation") }
    var expanded by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Normal") }
    var submitted by remember { mutableStateOf(false) }
    var reports by remember { mutableStateOf<List<IncidentReport>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        reports = runCatching { withContext(Dispatchers.IO) { store.list() } }.getOrElse {
            errorMessage = it.message
            emptyList()
        }
    }

    val incidentTypes = listOf("Traffic violation", "Collision", "Road hazard", "Congestion", "Other")
    fun typeLabel(value: String): String = if (AppSettings.language == AppLanguage.ARABIC) when (value) {
        "Traffic violation" -> "مخالفة مرورية"
        "Collision" -> "تصادم"
        "Road hazard" -> "خطر على الطريق"
        "Congestion" -> "ازدحام"
        else -> "أخرى"
    } else value

    Column(Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Filled.AddAlert, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.weight(1f)) {
                Text(tr("incidentReports"), style = MaterialTheme.typography.headlineMedium)
                Text(tr("reportHint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("newReport"), style = MaterialTheme.typography.titleLarge)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = typeLabel(type),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(tr("incidentType")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        incidentTypes.forEach { option ->
                            DropdownMenuItem(text = { Text(typeLabel(option)) }, onClick = { type = option; expanded = false })
                        }
                    }
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("location")) }, leadingIcon = { Icon(Icons.Filled.LocationOn, null) }, singleLine = true)
                OutlinedTextField(value = plate, onValueChange = { plate = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("plateNumber")) }, singleLine = true)
                Text(tr("priority"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Low", "Normal", "High").forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = priority == item, onClick = { priority = item })
                            Text(tr(item.lowercase()))
                        }
                    }
                }
                OutlinedTextField(value = details, onValueChange = { details = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("details")) }, minLines = 4)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        scope.launch {
                            val report = IncidentReport(
                                id = UUID.randomUUID().toString(),
                                type = type,
                                location = location.trim(),
                                plate = plate.trim().takeIf { it.isNotEmpty() },
                                priority = priority,
                                details = details.trim(),
                                createdAtMs = System.currentTimeMillis(),
                            )
                            runCatching {
                                withContext(Dispatchers.IO) { store.save(report) }
                            }.onSuccess { persisted ->
                                reports = listOf(persisted) + reports
                                submitted = true
                                errorMessage = null
                                location = ""
                                plate = ""
                                details = ""
                            }.onFailure {
                                submitted = false
                                errorMessage = it.message ?: "Unable to save incident report"
                            }
                        }
                    },
                    enabled = location.isNotBlank() && details.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AddAlert, null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("submitReport"))
                }
            }
        }

        if (submitted) {
            Card(shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(tr("reportRecorded"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("pendingReview"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (reports.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (AppSettings.language == AppLanguage.ARABIC) "${reports.size} بلاغ محفوظ" else "${reports.size} saved reports", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.clear() } }.onSuccess {
                            reports = emptyList()
                            submitted = false
                        }.onFailure { errorMessage = it.message ?: "Unable to clear reports" }
                    }
                }) {
                    Icon(Icons.Filled.DeleteSweep, null)
                    Text(if (AppSettings.language == AppLanguage.ARABIC) "مسح" else "Clear")
                }
            }
            reports.forEach { report ->
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(typeLabel(report.type), style = MaterialTheme.typography.titleLarge)
                        ReportMetric(if (AppSettings.language == AppLanguage.ARABIC) "الموقع" else "Location", report.location)
                        ReportMetric(if (AppSettings.language == AppLanguage.ARABIC) "الأولوية" else "Priority", report.priority)
                        ReportMetric(if (AppSettings.language == AppLanguage.ARABIC) "اللوحة" else "Plate", report.plate ?: "—")
                        ReportMetric(if (AppSettings.language == AppLanguage.ARABIC) "الوقت" else "Created", report.createdAtMs.toString())
                        Text(report.details, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportMetric(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
