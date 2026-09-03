package com.smarttraffic.app.features.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Stop
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.DeviceSettings
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.network.MjpegStreamClient
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.core.ui.VideoViewport
import com.smarttraffic.app.features.analysis.AnalysisRadarPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun LiveCameraScreen(
    paddingValues: PaddingValues,
    analysisViewModel: LiveAnalysisViewModel = viewModel(),
) {
    var videoMode by remember { mutableStateOf(VideoDisplayMode.FULLSCREEN) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var streamState by remember { mutableStateOf(StreamState.IDLE) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { MjpegStreamClient() }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val analysisState by analysisViewModel.state.collectAsStateWithLifecycle()
    val analysisPreview by analysisViewModel.preview.collectAsStateWithLifecycle()

    val analyzing = analysisState.phase == LiveAnalysisPhase.STARTING || analysisState.phase == LiveAnalysisPhase.RUNNING

    fun startRawStream() {
        streamJob?.cancel()
        streamState = StreamState.CONNECTING
        streamMessage = liveText("Connecting to stream…", "جارٍ الاتصال بالبث…")
        streamJob = scope.launch {
            try {
                client.collect(DeviceSettings.streamUrl()) { bitmap ->
                    frame = bitmap
                    streamState = StreamState.LIVE
                    streamMessage = liveText("LIVE • ESP32-CAM", "مباشر • ESP32-CAM")
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

    fun startAnalysis() {
        streamJob?.cancel()
        streamJob = null
        frame = null
        analysisViewModel.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            streamJob?.cancel()
            analysisViewModel.stop()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(tr("live"), style = MaterialTheme.typography.headlineSmall)
            Text(
                "ESP32-CAM can now feed the same real analysis engine used by the Local Analysis Lab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (analysisPreview != null) {
            AnalysisRadarPreview(analysisPreview, Modifier.fillMaxWidth())
        } else {
            VideoViewport(
                title = tr("primaryCamera"),
                mode = videoMode,
                onModeChange = { videoMode = it },
                frame = frame,
                statusText = streamMessage ?: tr("streamNotConnected"),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (analyzing) analysisViewModel.stop() else startAnalysis() },
                modifier = Modifier.weight(1f),
                enabled = analysisState.phase != LiveAnalysisPhase.STARTING,
            ) {
                Icon(if (analyzing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (analyzing) "Stop analysis" else "Analyze live")
            }
            OutlinedButton(
                onClick = { analysisViewModel.stop(); startRawStream() },
                modifier = Modifier.weight(1f),
                enabled = !analyzing,
            ) {
                Text(if (streamState == StreamState.LIVE) liveText("Reconnect", "إعادة الاتصال") else liveText("Connect stream", "اتصال بالبث"))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { fetchCapture(DeviceSettings.captureUrl()) }
                            .onSuccess { captured ->
                                withContext(Dispatchers.Main.immediate) {
                                    frame = captured
                                    streamState = StreamState.LIVE
                                    streamMessage = liveText("Captured frame", "تم التقاط الإطار")
                                }
                            }
                            .onFailure { error ->
                                withContext(Dispatchers.Main.immediate) {
                                    streamState = StreamState.ERROR
                                    streamMessage = "${liveText("Capture error", "خطأ في الالتقاط")}: ${error.message ?: "HTTP error"}"
                                }
                            }
                    }
                },
                enabled = !analyzing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(tr("capture"))
            }
            OutlinedButton(
                onClick = { streamMessage = liveText("Camera control endpoint is not defined by the current ESP32 contract.", "واجهة تحكم الكاميرا غير معرفة في عقد ESP32 الحالي.") },
                enabled = !analyzing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.SettingsRemote, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(tr("cameraControl"))
            }
        }

        if (analysisState.message != null) {
            Text(
                analysisState.message!! + (analysisState.droppedFrames.takeIf { it > 0L }?.let { " • dropped $it live frame(s)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun fetchCapture(urlString: String): Bitmap {
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000
        readTimeout = 5000
        requestMethod = "GET"
        useCaches = false
        doInput = true
    }
    return try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
        requireNotNull(bitmap) { "Capture endpoint returned invalid image data" }
    } finally {
        connection.disconnect()
    }
}

private enum class StreamState { IDLE, CONNECTING, LIVE, DISCONNECTED, ERROR }

private fun liveText(en: String, ar: String): String =
    if (AppSettings.language == AppLanguage.ARABIC) ar else en
