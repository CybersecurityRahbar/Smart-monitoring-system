package com.smarttraffic.app.features.live

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.DeviceSettings
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.network.Esp32CameraClient
import com.smarttraffic.app.core.network.MjpegStreamClient
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.core.ui.VideoViewport
import com.smarttraffic.app.features.analysis.AnalysisRadarPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LiveCameraScreen(
    paddingValues: PaddingValues,
    analysisViewModel: LiveAnalysisViewModel = viewModel(),
) {
    var videoMode by remember { mutableStateOf(VideoDisplayMode.STANDARD) }
    var fullscreen by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var streamState by remember { mutableStateOf(StreamState.IDLE) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    var captureBusy by remember { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(false) }
    var controlBusy by remember { mutableStateOf(false) }
    var controlMessage by remember { mutableStateOf<String?>(null) }
    var jpegQuality by remember { mutableIntStateOf(10) }
    var flashOn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val streamClient = remember { MjpegStreamClient() }
    val esp32 = remember { Esp32CameraClient() }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val analysisState by analysisViewModel.state.collectAsStateWithLifecycle()
    val analysisPreview by analysisViewModel.preview.collectAsStateWithLifecycle()

    val analyzing = analysisState.phase == LiveAnalysisPhase.STARTING || analysisState.phase == LiveAnalysisPhase.RUNNING

    DisposableEffect(fullscreen) {
        val activity = context as? ComponentActivity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    fun startRawStream() {
        streamJob?.cancel()
        streamState = StreamState.CONNECTING
        streamMessage = liveText("Connecting to stream…", "جارٍ الاتصال بالبث…")
        streamJob = scope.launch {
            try {
                streamClient.collect(DeviceSettings.streamUrl()) { bitmap ->
                    frame = bitmap
                    streamState = StreamState.LIVE
                    streamMessage = liveText("LIVE • ESP32-S3 camera", "مباشر • كاميرا ESP32-S3")
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

    suspend fun pauseRawStream() {
        streamJob?.cancel()
        streamJob = null
        if (streamState == StreamState.LIVE || streamState == StreamState.CONNECTING) {
            streamState = StreamState.CONNECTING
            streamMessage = liveText("Pausing stream for camera command…", "جارٍ إيقاف البث مؤقتًا لتنفيذ أمر الكاميرا…")
            kotlinx.coroutines.delay(80L)
        }
    }

    fun startAnalysis() {
        fullscreen = false
        streamJob?.cancel()
        streamJob = null
        frame = null
        analysisViewModel.start()
    }

    fun captureFrame() {
        if (captureBusy || analyzing) return
        captureBusy = true
        scope.launch {
            pauseRawStream()
            runCatching { esp32.capture() }
                .onSuccess { captured ->
                    frame = captured
                    streamState = StreamState.DISCONNECTED
                    streamMessage = liveText("Captured from ESP32-S3 • press Connect stream to resume", "تم الالتقاط من ESP32-S3 • اضغط اتصال بالبث للمتابعة")
                }
                .onFailure { error ->
                    streamState = StreamState.ERROR
                    streamMessage = "${liveText("Capture error", "خطأ في الالتقاط")}: ${error.message ?: "HTTP error"}"
                }
            captureBusy = false
        }
    }

    fun applyControl(action: suspend () -> String, successText: (String) -> String) {
        if (controlBusy || analyzing) return
        controlBusy = true
        scope.launch {
            val shouldReconnect = streamState == StreamState.LIVE || streamState == StreamState.CONNECTING
            pauseRawStream()
            var success = false
            runCatching { action() }
                .onSuccess { response ->
                    success = true
                    controlMessage = successText(response.ifBlank { "OK" })
                }
                .onFailure { error ->
                    controlMessage = "${liveText("Control error", "خطأ في التحكم")}: ${error.message ?: "network error"}"
                }
            controlBusy = false
            if (shouldReconnect && success) startRawStream()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fullscreen = false
            streamJob?.cancel()
            analysisViewModel.stop()
        }
    }

    if (fullscreen && analysisPreview == null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                BoxFullscreenVideo(
                    frame = frame,
                    title = tr("primaryCamera"),
                    message = streamMessage,
                    onClose = { fullscreen = false },
                    onCapture = ::captureFrame,
                    onControl = { controlsOpen = true },
                    captureBusy = captureBusy,
                    controlsEnabled = !analyzing,
                )
            }
        }
    }

    if (controlsOpen) {
        AlertDialog(
            onDismissRequest = { if (!controlBusy) controlsOpen = false },
            title = { Text(liveText("ESP32 camera control", "تحكم بكاميرا ESP32")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        liveText("Commands use the firmware /control contract. The live MJPEG stream pauses for each command.", "الأوامر تستخدم عقد /control الخاص بالـFirmware، ويتوقف بث MJPEG لحظيًا لكل أمر."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val target = !flashOn
                                applyControl(
                                    action = { esp32.setFlash(target) },
                                    successText = { response ->
                                        flashOn = target
                                        liveText("Flash updated: $response", "تم تحديث الفلاش: $response")
                                    },
                                )
                            },
                            enabled = !controlBusy,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (flashOn) liveText("Flash OFF", "إيقاف الفلاش") else liveText("Flash ON", "تشغيل الفلاش")) }
                        OutlinedButton(
                            onClick = {
                                applyControl(
                                    action = { esp32.status() },
                                    successText = { response -> liveText("Status: $response", "الحالة: $response") },
                                )
                            },
                            enabled = !controlBusy,
                            modifier = Modifier.weight(1f),
                        ) { Text(liveText("Read status", "قراءة الحالة")) }
                    }
                    Text(liveText("JPEG quality: $jpegQuality (5 = higher quality / larger frames)", "جودة JPEG: $jpegQuality (5 = جودة أعلى / إطارات أكبر)"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 20, 30).forEach { value ->
                            TextButton(
                                onClick = {
                                    applyControl(
                                        action = { esp32.setJpegQuality(value) },
                                        successText = { response ->
                                            jpegQuality = value
                                            liveText("Quality $value: $response", "الجودة $value: $response")
                                        },
                                    )
                                },
                                enabled = !controlBusy,
                                modifier = Modifier.weight(1f),
                            ) { Text(value.toString()) }
                        }
                    }
                    controlMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { controlsOpen = false }, enabled = !controlBusy) { Text(liveText("Done", "تم")) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(paddingValues)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(tr("live"), style = MaterialTheme.typography.headlineSmall)
            Text(
                liveText("ESP32-S3 camera • local Wi-Fi • live MJPEG", "كاميرا ESP32-S3 • Wi-Fi محلي • بث MJPEG مباشر"),
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
                onFullscreenRequested = { fullscreen = true },
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
                Text(if (analyzing) liveText("Stop analysis", "إيقاف التحليل") else liveText("Analyze live", "تحليل مباشر"))
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
                onClick = ::captureFrame,
                enabled = !analyzing && !captureBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("capture"))
            }
            OutlinedButton(
                onClick = { controlsOpen = true },
                enabled = !analyzing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.SettingsRemote, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("cameraControl"))
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

@Composable
private fun BoxFullscreenVideo(
    frame: Bitmap?,
    title: String,
    message: String?,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onControl: () -> Unit,
    captureBusy: Boolean,
    controlsEnabled: Boolean,
) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(
            message ?: "",
            modifier = Modifier.align(Alignment.Center).graphicsLayer(shadowElevation = 6f),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(50)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = liveText("Close", "إغلاق"), tint = Color.White)
            }
            Text(
                text = message ?: liveText("ESP32-S3 live", "بث ESP32-S3 مباشر"),
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterVertically).graphicsLayer(shadowElevation = 8f),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCapture, enabled = !captureBusy && controlsEnabled) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("capture"))
            }
            OutlinedButton(onClick = onControl, enabled = controlsEnabled) {
                Icon(Icons.Filled.SettingsRemote, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("cameraControl"))
            }
        }
    }
}

private enum class StreamState { IDLE, CONNECTING, LIVE, DISCONNECTED, ERROR }

private fun liveText(en: String, ar: String): String =
    if (AppSettings.language == AppLanguage.ARABIC) ar else en
