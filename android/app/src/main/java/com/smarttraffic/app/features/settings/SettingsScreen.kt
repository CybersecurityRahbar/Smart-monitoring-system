package com.smarttraffic.app.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        ListItem(
            headlineContent = { Text("System settings") },
            supportingContent = { Text("Devices, calibration, traffic rules and retention") },
        )
        ListItem(
            headlineContent = { Text("Application settings") },
            supportingContent = { Text("Theme, notifications, language and app behavior") },
        )
    }
}
