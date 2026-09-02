package com.smarttraffic.app.features.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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

@Composable
fun TrafficRulesScreen(paddingValues: PaddingValues) {
    var speeding by remember { mutableStateOf(true) }
    var zoneAlerts by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(paddingValues).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Traffic Rules", style = MaterialTheme.typography.headlineMedium)
        Text("Configure violation policies before live enforcement is connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RuleRow("Speed threshold", "80 km/h", true, speeding) { speeding = it }
        RuleRow("Zone / direction alerts", "Operator-defined zones", true, zoneAlerts) { zoneAlerts = it }
    }
}

@Composable
private fun RuleRow(title: String, subtitle: String, enabled: Boolean, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
