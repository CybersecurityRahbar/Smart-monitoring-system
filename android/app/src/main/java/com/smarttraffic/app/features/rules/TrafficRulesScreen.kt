package com.smarttraffic.app.features.rules

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

private object RulePrefs {
    private const val NAME = "traffic_rules"
    private const val LIMIT = "speed_limit"
    private const val WARNING = "warning_speed"
    private const val CAPTURE = "capture_speed"
    private const val CONFIDENCE = "min_confidence"
    private const val ENABLED = "enabled"
    private const val ACTION_CAPTURE = "action_capture"
    private const val ACTION_ALERT = "action_alert"
    private const val ACTION_PRESERVE = "action_preserve"

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Values(
            limit = p.getInt(LIMIT, 80).toString(), warning = p.getInt(WARNING, 70).toString(),
            capture = p.getInt(CAPTURE, 80).toString(), confidence = p.getInt(CONFIDENCE, 70).toString(),
            enabled = p.getBoolean(ENABLED, true), captureAction = p.getBoolean(ACTION_CAPTURE, true),
            alertAction = p.getBoolean(ACTION_ALERT, true), preserveAction = p.getBoolean(ACTION_PRESERVE, true),
        )
    }

    fun save(context: Context, values: Values) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(LIMIT, values.limit.toIntOrNull() ?: 80).putInt(WARNING, values.warning.toIntOrNull() ?: 70)
            .putInt(CAPTURE, values.capture.toIntOrNull() ?: 80).putInt(CONFIDENCE, values.confidence.toIntOrNull() ?: 70)
            .putBoolean(ENABLED, values.enabled).putBoolean(ACTION_CAPTURE, values.captureAction)
            .putBoolean(ACTION_ALERT, values.alertAction).putBoolean(ACTION_PRESERVE, values.preserveAction).apply()
    }

    fun reset(context: Context) = save(context, Values("80", "70", "80", "70", true, true, true, true))
}

private data class Values(
    val limit: String, val warning: String, val capture: String, val confidence: String,
    val enabled: Boolean, val captureAction: Boolean, val alertAction: Boolean, val preserveAction: Boolean,
)

@Composable
fun TrafficRulesScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val initial = remember { RulePrefs.load(context) }
    var speedLimit by remember { mutableStateOf(initial.limit) }
    var warningSpeed by remember { mutableStateOf(initial.warning) }
    var captureSpeed by remember { mutableStateOf(initial.capture) }
    var confidence by remember { mutableStateOf(initial.confidence) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var captureAction by remember { mutableStateOf(initial.captureAction) }
    var alertAction by remember { mutableStateOf(initial.alertAction) }
    var preserveAction by remember { mutableStateOf(initial.preserveAction) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tr("rules"), style = MaterialTheme.typography.headlineMedium)
                Text(tr("rulesDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Text(tr("speedPolicy"), style = MaterialTheme.typography.titleLarge)
                }
                RuleField(tr("speedLimit"), speedLimit, { speedLimit = it }, tr("kmh"))
                RuleField(tr("warningThreshold"), warningSpeed, { warningSpeed = it }, tr("kmh"))
                RuleField(tr("captureThreshold"), captureSpeed, { captureSpeed = it }, tr("kmh"))
                RuleField(tr("minimumConfidence"), confidence, { confidence = it }, "%")
                Text(tr("rulesValuesDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(tr("enforcementActions"), style = MaterialTheme.typography.titleMedium)
                ActionRow(tr("captureOnViolation"), captureAction) { captureAction = it }
                ActionRow(tr("createAlertOnViolation"), alertAction) { alertAction = it }
                ActionRow(tr("preserveEvidence"), preserveAction) { preserveAction = it }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { RulePrefs.save(context, Values(speedLimit, warningSpeed, captureSpeed, confidence, enabled, captureAction, alertAction, preserveAction)); saved = true }, modifier = Modifier.weight(1f)) { Text(tr("saveRules")) }
            TextButton(onClick = { RulePrefs.reset(context); speedLimit = "80"; warningSpeed = "70"; captureSpeed = "80"; confidence = "70"; enabled = true; captureAction = true; alertAction = true; preserveAction = true; saved = false }) { Text(tr("reset")) }
        }
        if (saved) Text(tr("rulesSaved"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RuleField(label: String, value: String, onValueChange: (String) -> Unit, unit: String) {
    OutlinedTextField(value = value, onValueChange = { text -> if (text.length <= 4 && text.all(Char::isDigit)) onValueChange(text) }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, suffix = { Text(unit) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ActionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
