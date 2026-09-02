package com.smarttraffic.app.features.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DevicesScreen(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Devices", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("ESP32-CAM", style = MaterialTheme.typography.titleMedium)
                Text("Not connected yet")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {}) { Text("Connection settings") }
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Text("Add device")
        }
    }
}
