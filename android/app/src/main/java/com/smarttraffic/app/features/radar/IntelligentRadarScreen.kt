package com.smarttraffic.app.features.radar

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
    var radarRunning by remember { mutableStateOf(false) }
    var vehiclesOnly by remember { mutableStateOf(true) }
    var highConfidence by remember { mutableStateOf(false) }
    var forwardOnly by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Analytics, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(tr("radar"), style = MaterialTheme.typography.headlineSmall)
                }
                Text(tr("radarDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(
                onClick = { radarRunning = !radarRunning },
                leadingIcon = { Icon(if (radarRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, null) },
                label = { Text(if (radarRunning) tr("running") else tr("standby"), maxLines = 1) },
            )
        }

        VideoViewport(
            title = tr("intelligentRadar"),
            mode = videoMode,
            onModeChange = { videoMode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(tr("targetPolicy"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("radarFiltersDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr("minimumSpeed") + ": ${minSpeed.toInt()} km/h", style = MaterialTheme.typography.bodyMedium)
                Slider(value = minSpeed, onValueChange = { minSpeed = it }, valueRange = 0f..160f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = vehiclesOnly, onClick = { vehiclesOnly = !vehiclesOnly }, label = { Text(tr("vehicles")) })
                    FilterChip(selected = highConfidence, onClick = { highConfidence = !highConfidence }, label = { Text(tr("highConfidence")) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = forwardOnly, onClick = { forwardOnly = !forwardOnly }, label = { Text(tr("forward")) })
                    AssistChip(onClick = {}, label = { Text(tr("speedRange")) })
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { radarRunning = !radarRunning }, modifier = Modifier.weight(1f)) {
                Icon(if (radarRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (radarRunning) tr("stopRadar") else tr("startRadar"))
            }
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Analytics, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("analyzeMedia"))
            }
        }

        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(tr("trackingStatus"), style = MaterialTheme.typography.labelLarge)
                    Text(tr("tracksReady"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("0", tr("tracks"))
                    Metric("—", tr("speed"))
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
