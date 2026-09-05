package com.smarttraffic.app.features.rules

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.domain.analysis.TrafficRuleConfig

private data class RuleFormState(
    val speedLimit: String,
    val confidence: String,
    val enabled: Boolean,
    val captureAction: Boolean,
    val alertAction: Boolean,
    val preserveAction: Boolean,
)

@Composable
fun TrafficRulesScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val initial = remember {
        TrafficRulePreferences.load(context).let {
            RuleFormState(
                speedLimit = it.speedLimitKmh.toInt().toString(),
                confidence = (it.minimumSpeedConfidence * 100f).toInt().toString(),
                enabled = it.enabled,
                captureAction = it.captureOnViolation,
                alertAction = it.createAlertOnViolation,
                preserveAction = it.preserveEvidence,
            )
        }
    }
    var speedLimit by remember { mutableStateOf(initial.speedLimit) }
    var confidence by remember { mutableStateOf(initial.confidence) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var captureAction by remember { mutableStateOf(initial.captureAction) }
    var alertAction by remember { mutableStateOf(initial.alertAction) }
    var preserveAction by remember { mutableStateOf(initial.preserveAction) }
    var saved by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(tr("rules"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    tr("rulesDescription"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; saved = false })
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Text(tr("speedPolicy"), style = MaterialTheme.typography.titleLarge)
                }
                RuleField(tr("speedLimit"), speedLimit, { speedLimit = it; saved = false }, tr("kmh"))
                RuleField(tr("minimumConfidence"), confidence, { confidence = it; saved = false }, "%")
                Text(
                    "The analysis engine currently evaluates one authoritative speed-violation threshold. Warning/capture thresholds are not shown as independent settings until the domain model supports them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(tr("enforcementActions"), style = MaterialTheme.typography.titleMedium)
                ActionRow(tr("captureOnViolation"), captureAction) { captureAction = it; saved = false }
                ActionRow(tr("createAlertOnViolation"), alertAction) { alertAction = it; saved = false }
                ActionRow(tr("preserveEvidence"), preserveAction) { preserveAction = it; saved = false }
            }
        }

        errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val limit = speedLimit.toDoubleOrNull()
                    val confidenceValue = confidence.toFloatOrNull()
                    errorMessage = when {
                        limit == null || !limit.isFinite() || limit <= 0.0 -> "Enter a positive speed limit."
                        confidenceValue == null || !confidenceValue.isFinite() || confidenceValue !in 0f..100f -> "Confidence must be between 0 and 100%."
                        else -> null
                    }
                    if (errorMessage == null) {
                        TrafficRulePreferences.save(
                            context,
                            TrafficRuleConfig(
                                enabled = enabled,
                                speedLimitKmh = limit!!,
                                minimumSpeedConfidence = confidenceValue!! / 100f,
                                captureOnViolation = captureAction,
                                createAlertOnViolation = alertAction,
                                preserveEvidence = preserveAction,
                            ),
                        )
                        saved = true
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("saveRules"))
            }
            TextButton(
                onClick = {
                    TrafficRulePreferences.save(context, TrafficRuleConfig())
                    speedLimit = "80"
                    confidence = "70"
                    enabled = false
                    captureAction = true
                    alertAction = true
                    preserveAction = true
                    errorMessage = null
                    saved = false
                },
            ) {
                Text(tr("reset"))
            }
        }
        if (saved) {
            Text(
                tr("rulesSaved"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RuleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.length <= 4 && text.all(Char::isDigit)) onValueChange(text)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ActionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
