package com.smarttraffic.app.features.analysis

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LocalAnalysisScreen(paddingValues: PaddingValues) {
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { /* Analysis session will consume the selected URI in the next stage. */ },
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Local Analysis Lab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Test detection, tracking and camera-based speed estimation using media stored on the phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Input", style = MaterialTheme.typography.titleMedium)
                Text("Choose a video or image. The same analysis pipeline will later accept ESP32 live frames.")
                Button(
                    onClick = {
                        mediaPicker.launch(arrayOf("video/*", "image/*"))
                    },
                ) {
                    Text("Choose from device")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Analysis session", style = MaterialTheme.typography.titleMedium)
                Text("Detection • Tracking • Ground-plane calibration • Speed • Confidence")
                Text("No physical sensor is required for this lab mode.")
            }
        }
    }
}
