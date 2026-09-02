package com.smarttraffic.app.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    var languageMenu by remember { mutableStateOf(false) }
    val ar = AppSettings.language == AppLanguage.ARABIC

    Column(Modifier.fillMaxSize().padding(paddingValues).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(tr("settings"), style = MaterialTheme.typography.headlineMedium)
                Text(if (ar) "تفضيلات المشغّل وسلوك العرض" else "Operator preferences and display behavior", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("applicationSettings"), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Language, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(tr("language"), style = MaterialTheme.typography.titleMedium)
                        Text(if (ar) tr("arabic") else tr("english"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ExposedDropdownMenuBox(expanded = languageMenu, onExpandedChange = { languageMenu = !languageMenu }) {
                        OutlinedButton(onClick = { languageMenu = true }) { Text(if (ar) tr("arabic") else tr("english")); ExposedDropdownMenuDefaults.TrailingIcon(languageMenu) }
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
        val systemItems = if (ar) listOf("الأجهزة والاتصال وصحة النظام", "قواعد المرور والمناطق والحدود", "التخزين والاحتفاظ وسياسة الأدلة") else listOf("Devices, connectivity and health", "Traffic rules, zones and thresholds", "Storage, retention and evidence policy")
        systemItems.forEach { label -> Card(shape = RoundedCornerShape(20.dp)) { Text(label, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) } }
    }
}
