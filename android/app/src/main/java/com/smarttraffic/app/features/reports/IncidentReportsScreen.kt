package com.smarttraffic.app.features.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentReportsScreen(paddingValues: PaddingValues) {
    var type by remember { mutableStateOf("Traffic violation") }
    var expanded by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Normal") }
    var submitted by remember { mutableStateOf(false) }

    val incidentTypes = listOf("Traffic violation", "Collision", "Road hazard", "Congestion", "Other")
    fun typeLabel(value: String): String = if (AppSettings.language == AppLanguage.ARABIC) when (value) {
        "Traffic violation" -> "مخالفة مرورية"
        "Collision" -> "تصادم"
        "Road hazard" -> "خطر على الطريق"
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        incidentTypes.forEach { option -> DropdownMenuItem(text = { Text(typeLabel(option)) }, onClick = { type = option; expanded = false }) }
                    }
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("location")) }, leadingIcon = { Icon(Icons.Filled.LocationOn, null) }, singleLine = true)
                OutlinedTextField(value = plate, onValueChange = { plate = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("plateNumber")) }, singleLine = true)
                Text(tr("priority"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Low", "Normal", "High").forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = priority == item, onClick = { priority = item }); Text(tr(item.lowercase())) } }
                }
                OutlinedTextField(value = details, onValueChange = { details = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("details")) }, minLines = 4)
                Button(onClick = { submitted = true }, enabled = location.isNotBlank() && details.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.AddAlert, null); Spacer(Modifier.width(6.dp)); Text(tr("submitReport")) }
            }
        }
        if (submitted) {
            Card(shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column { Text(tr("reportRecorded"), style = MaterialTheme.typography.titleMedium); Text(tr("pendingReview"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
