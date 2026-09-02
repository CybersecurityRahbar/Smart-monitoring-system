package com.smarttraffic.app.features.live

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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.core.ui.VideoViewport

@Composable
fun LiveCameraScreen(paddingValues: PaddingValues) {
    var videoMode by remember { mutableStateOf(VideoDisplayMode.FULLSCREEN) }

    Column(Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(tr("live"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("liveDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VideoViewport(title = tr("primaryCamera"), mode = videoMode, onModeChange = { videoMode = it }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(tr("capture"))
            }
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SettingsRemote, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(tr("cameraControl"))
            }
        }
    }
}
