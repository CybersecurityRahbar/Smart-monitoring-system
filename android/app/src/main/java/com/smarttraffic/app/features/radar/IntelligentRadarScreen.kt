package com.smarttraffic.app.features.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.core.ui.VideoViewport

@Composable
fun IntelligentRadarScreen(paddingValues: PaddingValues) {
    var minSpeed by remember { mutableFloatStateOf(0f) }
    var videoMode by remember { mutableStateOf(VideoDisplayMode.STANDARD) }

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Analytics, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(tr("radar"), style = MaterialTheme.typography.headlineSmall)
                }
                Text("Detection • tracking • trajectory • speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(onClick = {}, label = { Text(tr("standby")) })
        }

        VideoViewport(
            title = "INTELLIGENT RADAR",
            mode = videoMode,
            onModeChange = { videoMode = it },
            modifier = if (videoMode == VideoDisplayMode.FULLSCREEN) Modifier.weight(1f) else Modifier.fillMaxWidth(),
        )

        if (videoMode != VideoDisplayMode.COMPACT) {
            Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text("Target policy", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Minimum speed: ${minSpeed.toInt()} km/h", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = minSpeed, onValueChange = { minSpeed = it }, valueRange = 0f..160f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("Vehicles") })
                        AssistChip(onClick = {}, label = { Text("High confidence") })
                        AssistChip(onClick = {}, label = { Text("Forward") })
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Speed, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Start radar")
            }
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Analytics, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Analyze")
            }
        }
    }
}
