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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.smarttraffic.app.R
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.Track
import kotlin.math.max
import kotlin.math.min

/**
 * Real video playback is intentionally independent from the analysis frame producer.
 * Media3 owns the presentation clock; the AI pipeline may run faster or slower without changing
 * the video's playback rate.
 */
@Composable
fun AnalysisVideoPlayback(
    videoUri: Uri,
    preview: AnalysisPreviewFrame,
    modifier: Modifier = Modifier,
    showClose: Boolean = false,
    onClose: () -> Unit = {},
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
            positionMs = player.currentPosition.coerceAtLeast(0L)
            kotlinx.coroutines.delay(16L)
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.view_analysis_player, null) as PlayerView).apply {
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
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
            val trackColor = Color(0xFF39FF14)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD,
                )
                color = android.graphics.Color.WHITE
                setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
            }

            preview.tracks.forEach { track ->
                val detection = interpolatedDetection(track, positionMs) ?: return@forEach
                val left = offsetX + detection.left * scale
                val top = offsetY + detection.top * scale
                val right = offsetX + detection.right * scale
                val bottom = offsetY + detection.bottom * scale
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, top),
                    size = Size(
                        (right - left).coerceAtLeast(0f),
                        (bottom - top).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 4f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "#${track.id} ${track.className}",
                    left.coerceAtLeast(4f),
                    (top - 8f).coerceAtLeast(30f),
                    labelPaint,
                )
            }
        }

        if (showClose) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(50)),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Exit full screen", tint = Color.White)
            }
        }
    }
}

private fun interpolatedDetection(track: Track, positionMs: Long): Detection? {
    val observations = track.observations.sortedBy { it.timestampMs }
    if (observations.isEmpty()) return null

    val before = observations.lastOrNull { it.timestampMs <= positionMs }
    val after = observations.firstOrNull { it.timestampMs >= positionMs }

    if (before != null && after != null && before.timestampMs != after.timestampMs) {
        val ratio = ((positionMs - before.timestampMs).toDouble() /
            (after.timestampMs - before.timestampMs).toDouble()).coerceIn(0.0, 1.0)
        return interpolateDetection(before.detection, after.detection, ratio)
    }

    if (before != null) {
        val previous = observations
            .asReversed()
            .drop(1)
            .firstOrNull { it.timestampMs < before.timestampMs }
        if (previous != null && before.timestampMs > previous.timestampMs) {
            val extrapolationMs = (positionMs - before.timestampMs).coerceIn(0L, 350L)
            val dt = (before.timestampMs - previous.timestampMs).toFloat()
            val alpha = extrapolationMs.toFloat() / dt
            return extrapolateDetection(previous.detection, before.detection, alpha)
        }
        return before.detection
    }

    return after?.detection
}

private fun interpolateDetection(a: Detection, b: Detection, ratio: Double): Detection {
    fun lerp(x: Float, y: Float): Float = (x + (y - x) * ratio).toFloat()
    return a.copy(
        left = lerp(a.left, b.left),
        top = lerp(a.top, b.top),
        right = lerp(a.right, b.right),
        bottom = lerp(a.bottom, b.bottom),
        confidence = max(a.confidence, b.confidence),
    )
}

private fun extrapolateDetection(previous: Detection, latest: Detection, alpha: Float): Detection {
    fun extrapolate(old: Float, current: Float): Float = current + (current - old) * alpha
    return latest.copy(
        left = extrapolate(previous.left, latest.left),
        top = extrapolate(previous.top, latest.top),
        right = extrapolate(previous.right, latest.right),
        bottom = extrapolate(previous.bottom, latest.bottom),
    )
}
