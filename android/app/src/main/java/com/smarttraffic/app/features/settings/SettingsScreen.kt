package com.smarttraffic.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr

@Composable
fun SettingsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    var languageMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(tr("settings"), style = MaterialTheme.typography.headlineMedium)
                Text("Operator preferences and display behavior", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("applicationSettings"), style = MaterialTheme.typography.titleLarge)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Language, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(tr("language"), style = MaterialTheme.typography.titleMedium)
                        Text(if (AppSettings.language == AppLanguage.ARABIC) tr("arabic") else tr("english"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ExposedDropdownMenuBox(expanded = languageMenu, onExpandedChange = { languageMenu = !languageMenu }) {
                        OutlinedButton(onClick = { languageMenu = true }) {
                            Text(if (AppSettings.language == AppLanguage.ARABIC) tr("arabic") else tr("english"))
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenu)
                        }
                        ExposedDropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            DropdownMenuItem(text = { Text("English") }, onClick = { AppSettings.setLanguage(context, AppLanguage.ENGLISH); languageMenu = false })
                            DropdownMenuItem(text = { Text("العربية") }, onClick = { AppSettings.setLanguage(context, AppLanguage.ARABIC); languageMenu = false })
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(tr("nightMode"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("nightModeDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = AppSettings.darkMode, onCheckedChange = { AppSettings.setDarkMode(context, it) })
                }
            }
        }

        Text(tr("systemSettings"), style = MaterialTheme.typography.titleLarge)
        listOf("Devices, connectivity and health", "Traffic rules, zones and thresholds", "Storage, retention and evidence policy").forEach { label ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Text(label, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
