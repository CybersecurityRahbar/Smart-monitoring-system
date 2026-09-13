package com.smarttraffic.app.features.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
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
import com.smarttraffic.app.core.MediaStorageSettings
import com.smarttraffic.app.core.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    var languageMenu by remember { mutableStateOf(false) }
    var storageFolder by remember { mutableStateOf(MediaStorageSettings.folderUri) }
    val ar = AppSettings.language == AppLanguage.ARABIC

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            MediaStorageSettings.takePersistableFolderPermission(context, uri)
            storageFolder = uri.toString()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(tr("settings"), style = MaterialTheme.typography.headlineMedium)
                Text(if (ar) "تفضيلات المشغّل وسلوك العرض" else "Operator preferences and display behavior", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
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

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (ar) "التخزين والحفظ" else "Storage & Recording", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (ar) "الصور تحفظ كصور JPEG. تسجيل البث يحفظ كملف MJPEG متعدد الإطارات."
                    else "Photos are saved as JPEG. Live recording is saved as a multipart MJPEG file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (ar) "حفظ الصور الملتقطة" else "Save captured photos", style = MaterialTheme.typography.titleMedium)
                        Text(if (ar) "إلى التخزين المحدد أدناه" else "To the storage target below", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = MediaStorageSettings.savePhotos, onCheckedChange = { MediaStorageSettings.setSavePhotos(context, it) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (ar) "تمكين تسجيل البث المباشر" else "Enable live recording", style = MaterialTheme.typography.titleMedium)
                        Text(if (ar) "يمكن بدء/إيقاف التسجيل من شاشة البث" else "Start/stop recording from Live Camera", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = MediaStorageSettings.recordLive, onCheckedChange = { MediaStorageSettings.setRecordLive(context, it) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(if (ar) "مكان الحفظ" else "Storage location", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (storageFolder == null) {
                                if (ar) "ذاكرة الهاتف: Pictures/SmartTraffic و Movies/SmartTraffic" else "Phone storage: Pictures/SmartTraffic and Movies/SmartTraffic"
                            } else {
                                if (ar) "مجلد محدد (يمكن أن يكون بطاقة SD)" else "Selected folder (can be an SD card)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (ar) "اختيار" else "Choose") }
                }
                if (storageFolder != null) {
                    TextButton(onClick = { MediaStorageSettings.setFolder(context, null); storageFolder = null }) {
                        Text(if (ar) "استخدام ذاكرة الهاتف" else "Use phone storage")
                    }
                }
            }
        }

        Text(tr("systemSettings"), style = MaterialTheme.typography.titleLarge)
        val systemItems = if (ar) listOf("الأجهزة والاتصال وصحة النظام", "قواعد المرور والمناطق والحدود", "التخزين والاحتفاظ وسياسة الأدلة") else listOf("Devices, connectivity and health", "Traffic rules, zones and thresholds", "Storage, retention and evidence policy")
        systemItems.forEach { label -> Card(shape = RoundedCornerShape(20.dp)) { Text(label, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) } }
    }
}
