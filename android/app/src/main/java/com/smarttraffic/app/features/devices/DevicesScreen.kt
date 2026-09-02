package com.smarttraffic.app.features.devices

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

@Composable
fun DevicesScreen(paddingValues: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(paddingValues).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.DevicesOther, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("devices"), style = MaterialTheme.typography.headlineMedium)
        }
        Card(modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ESP32-CAM", style = MaterialTheme.typography.titleMedium)
                Text(tr("deviceNotConnected"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("deviceProfileHint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Button(onClick = {}) { Text(tr("connectionSettings")) }
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(tr("addDevice")) }
    }
}
