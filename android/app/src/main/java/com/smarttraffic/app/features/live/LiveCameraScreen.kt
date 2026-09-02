package com.smarttraffic.app.features.live

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LiveCameraScreen(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Live Camera", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("LIVE STREAM PLACEHOLDER")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {}) { Text("Capture") }
            OutlinedButton(onClick = {}) { Text("Camera Control") }
        }
    }
}
