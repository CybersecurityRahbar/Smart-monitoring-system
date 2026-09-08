package com.smarttraffic.app.features.analysis

import android.graphics.Paint
import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.smarttraffic.app.R
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.ResolvedTrackState
import com.smarttraffic.app.domain.analysis.SpeedEstimateMode
import com.smarttraffic.app.domain.analysis.TrackRenderResolver
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

@Composable
fun AnalysisVideoPlayback(
    videoUri: Uri,
    preview: AnalysisPreviewFrame,
    modifier: Modifier = Modifier,
    showClose: Boolean = false,
    onClose: () -> Unit = {},
    onPositionChanged: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    var positionMs by remember(player) { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            val position = player.currentPosition.coerceAtLeast(0L)
            if (position != positionMs) {
                positionMs = position
                onPositionChanged(position)
            }
            delay(16L)
        }
    }

    LaunchedEffect(player) {
        player.playWhenReady = true
        player.play()
    }

    Box(modifier) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.view_analysis_player, null) as PlayerView).apply {
                    this.player = player
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    useController = true
                }
            },
            update = { view -> (view as PlayerView).player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(Modifier.fillMaxSize()) {
            val sourceWidth = preview.frame.width.toFloat().coerceAtLeast(1f)
            val sourceHeight = preview.frame.height.toFloat().coerceAtLeast(1f)
            val scale = min(size.width / sourceWidth, size.height / sourceHeight)
            val contentWidth = sourceWidth * scale
            val contentHeight = sourceHeight * scale
            val offsetX = (size.width - contentWidth) * 0.5f
            val offsetY = (size.height - contentHeight) * 0.5f
            val neonGreen = Color(0xFF39FF14)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 24f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                color = android.graphics.Color.WHITE
                setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
            }
            val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 22f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                color = android.graphics.Color.WHITE
                setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
            }

            val timelineOrigin = preview.timelineStartTimestampMs ?: preview.frame.timestampMs
            val targetTimestampMs = safeAddTimestamp(timelineOrigin, positionMs)
            val resolvedTracks = preview.renderTracks.mapNotNull { track ->
                TrackRenderResolver.resolve(track, targetTimestampMs)
            }.sortedBy { it.track.id }

            resolvedTracks.forEach { state ->
                val detection = state.detection
                val left = offsetX + detection.left.coerceIn(0f, sourceWidth) * scale
                val top = offsetY + detection.top.coerceIn(0f, sourceHeight) * scale
                val right = offsetX + detection.right.coerceIn(0f, sourceWidth) * scale
                val bottom = offsetY + detection.bottom.coerceIn(0f, sourceHeight) * scale
                drawRoundRect(
                    color = neonGreen,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = if (state.predicted) 3f else 4f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "ID ${state.track.id}",
                    left.coerceAtLeast(4f),
                    (top - 8f).coerceAtLeast(26f),
                    labelPaint,
                )
            }

            val rowHeight = 30f
            val maxRowsPerSide = max(1, ((size.height - 32f) / rowHeight).toInt())
            val leftTracks = resolvedTracks.filter { it.track.id % 2L == 1L }
            val rightTracks = resolvedTracks.filter { it.track.id % 2L == 0L }

            fun drawTelemetry(trackList: List<ResolvedTrackState>, rightAligned: Boolean) {
                trackList.take(maxRowsPerSide).forEachIndexed { row, state ->
                    val estimate = preview.speedEstimates[state.track.id]
                    val speed = estimate?.let {
                        val suffix = if (it.mode == SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE) " est" else ""
                        "%.0f km/h%s".format(it.kilometersPerHour, suffix)
                    } ?: "--"
                    val text = "ID ${state.track.id}  •  $speed"
                    val x = if (rightAligned) size.width - sidePaint.measureText(text) - 14f else 14f
                    val y = 30f + row * rowHeight
                    drawContext.canvas.nativeCanvas.drawText(text, x.coerceAtLeast(8f), y, sidePaint)
                }
            }

            drawTelemetry(leftTracks, rightAligned = false)
            drawTelemetry(rightTracks, rightAligned = true)
        }

        if (showClose) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Exit full screen", tint = Color.White)
            }
        }
    }
}

private fun safeAddTimestamp(baseMs: Long, offsetMs: Long): Long = try {
    Math.addExact(baseMs, offsetMs.coerceAtLeast(0L))
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}
