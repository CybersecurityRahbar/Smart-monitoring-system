package com.smarttraffic.app.features.live

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.DeviceSettings
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.network.MjpegStreamClient
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.core.ui.VideoViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LiveCameraScreen(paddingValues: PaddingValues) {
    var videoMode by remember { mutableStateOf(VideoDisplayMode.FULLSCREEN) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var streamState by remember { mutableStateOf(StreamState.IDLE) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { MjpegStreamClient() }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    fun startStream() {
        streamJob?.cancel()
        streamState = StreamState.CONNECTING
        streamMessage = liveText("Connecting to stream…", "جارٍ الاتصال بالبث…")
        streamJob = scope.launch {
            try {
                client.collect(DeviceSettings.streamUrl()) { bitmap ->
                    withContext(Dispatchers.Main.immediate) {
                        frame = bitmap
                        streamState = StreamState.LIVE
                        streamMessage = liveText("LIVE • ESP32-CAM", "مباشر • ESP32-CAM")
                    }
                }
                if (streamState == StreamState.LIVE) {
                    streamState = StreamState.DISCONNECTED
                    streamMessage = liveText("Stream ended", "انتهى البث")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                streamState = StreamState.ERROR
                streamMessage = "${liveText("Stream error", "خطأ في البث")}: ${error.message ?: liveText("network error", "خطأ في الشبكة") }"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { streamJob?.cancel() }
    }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(tr("live"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("liveDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VideoViewport(
            title = tr("primaryCamera"),
            mode = videoMode,
            onModeChange = { videoMode = it },
            frame = frame,
            statusText = streamMessage ?: tr("streamNotConnected"),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { startStream() }, modifier = Modifier.weight(1f), enabled = streamState != StreamState.CONNECTING) {
                Text(if (streamState == StreamState.LIVE) liveText("Reconnect", "إعادة الاتصال") else liveText("Connect stream", "اتصال بالبث"))
            }
            OutlinedButton(onClick = { streamJob?.cancel(); streamState = StreamState.DISCONNECTED }, modifier = Modifier.weight(1f)) {
                Text(liveText("Disconnect", "قطع الاتصال"))
            }
        }
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

private enum class StreamState { IDLE, CONNECTING, LIVE, DISCONNECTED, ERROR }

private fun liveText(en: String, ar: String): String =
    if (AppSettings.language == AppLanguage.ARABIC) ar else en
