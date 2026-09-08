package com.smarttraffic.app.features.analysis

import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.RadarBounds
import com.smarttraffic.app.domain.analysis.ResolvedTrackState
import com.smarttraffic.app.domain.analysis.TrackRenderResolver
import kotlin.math.max

@Composable
fun AnalysisRadarPreview(
    preview: AnalysisPreviewFrame?,
    modifier: Modifier = Modifier,
) {
    if (preview == null) return

    val videoUri = preview.videoUri?.let(Uri::parse)
    var fullscreen by remember { androidx.compose.runtime.mutableStateOf(false) }
    var playbackPositionMs by remember(videoUri) { mutableLongStateOf(0L) }
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                if (videoUri != null) {
                    AnalysisVideoPlayback(
                        preview = preview,
                        videoUri = videoUri,
                        modifier = Modifier.fillMaxSize(),
                        showClose = true,
                        onClose = { fullscreen = false },
                        onPositionChanged = { playbackPositionMs = it },
                    )
                } else {
                    Image(
                        bitmap = preview.bitmap.asImageBitmap(),
                        contentDescription = "Analyzed frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Live analysis preview", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Frame ${preview.frame.index} • ${preview.frame.timestampMs} ms • ${preview.tracks.size} active track(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant,
                        )
                        Text(
                            "Cars detected: ${preview.uniqueVehiclesDetected} • ${if (preview.calibrated) "metric" else "estimated"} speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (preview.calibrated) "METRIC RADAR" else "VISUAL RADAR",
                            style = MaterialTheme.typography.labelMedium,
                            color = primary,
                        )
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen")
                        }
                    }
                }

                if (videoUri != null) {
                    val ratio = preview.bitmap.width.toFloat() / max(1, preview.bitmap.height).toFloat()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .background(surfaceVariant, RoundedCornerShape(16.dp)),
                    ) {
                        AnalysisVideoPlayback(
                            videoUri = videoUri,
                            preview = preview,
                            modifier = Modifier.fillMaxSize(),
                            onPositionChanged = { playbackPositionMs = it },
                        )
                    }
                } else {
                    VideoFrameWithTracks(
                        preview = preview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(preview.bitmap.width.toFloat() / max(1, preview.bitmap.height).toFloat()),
                        primary = primary,
                    )
                }
            }
        }

        RadarPanel(
            preview = preview,
            playbackPositionMs = playbackPositionMs,
            primary = primary,
            surfaceVariant = surfaceVariant,
            background = background,
            outline = outline,
            onSurfaceVariant = onSurfaceVariant,
        )
    }
}

@Composable
private fun VideoFrameWithTracks(
    preview: AnalysisPreviewFrame,
    modifier: Modifier,
    primary: Color,
) {
    Box(modifier) {
        Image(
            bitmap = preview.bitmap.asImageBitmap(),
            contentDescription = "Analyzed video frame",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.fillMaxSize()) {
            val sourceWidth = preview.frame.width.toFloat().coerceAtLeast(1f)
            val sourceHeight = preview.frame.height.toFloat().coerceAtLeast(1f)
            val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
            val offsetX = (size.width - sourceWidth * scale) * 0.5f
            val offsetY = (size.height - sourceHeight * scale) * 0.5f
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 24f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                color = android.graphics.Color.WHITE
                setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
            }
            val target = preview.frame.timestampMs
            preview.renderTracks.mapNotNull { TrackRenderResolver.resolve(it, target) }.forEach { state ->
                val d = state.detection
                val left = offsetX + d.left.coerceIn(0f, sourceWidth) * scale
                val top = offsetY + d.top.coerceIn(0f, sourceHeight) * scale
                val right = offsetX + d.right.coerceIn(0f, sourceWidth) * scale
                val bottom = offsetY + d.bottom.coerceIn(0f, sourceHeight) * scale
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 4f),
                )
                drawContext.canvas.nativeCanvas.drawText("ID ${state.track.id}", left.coerceAtLeast(4f), (top - 8f).coerceAtLeast(26f), labelPaint)
            }
        }
    }
}

@Composable
private fun RadarPanel(
    preview: AnalysisPreviewFrame,
    playbackPositionMs: Long,
    primary: Color,
    surfaceVariant: Color,
    background: Color,
    outline: Color,
    onSurfaceVariant: Color,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Tracking radar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cars detected: ${preview.uniqueVehiclesDetected} • Active at video time: ${activeAt(preview, playbackPositionMs).size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant,
                    )
                }
                Text("SYNCED TO VIDEO", style = MaterialTheme.typography.bodySmall, color = primary)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.75f)
                    .background(background, RoundedCornerShape(16.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 1..4) {
                        val x = size.width * i / 5f
                        val y = size.height * i / 5f
                        drawLine(outline, Offset(x, 0f), Offset(x, size.height), 1f, cap = StrokeCap.Round)
                        drawLine(outline, Offset(0f, y), Offset(size.width, y), 1f, cap = StrokeCap.Round)
                    }

                    val timelineOrigin = preview.timelineStartTimestampMs ?: preview.frame.timestampMs
                    val targetTimestampMs = safeAddTimestamp(timelineOrigin, playbackPositionMs)
                    val bounds = preview.radarBounds ?: RadarBounds(
                        0.0,
                        preview.frame.width.toDouble().coerceAtLeast(1.0),
                        0.0,
                        preview.frame.height.toDouble().coerceAtLeast(1.0),
                    )
                    val rangeX = (bounds.maxX - bounds.minX).coerceAtLeast(1e-9)
                    val rangeY = (bounds.maxY - bounds.minY).coerceAtLeast(1e-9)
                    val active = activeAt(preview, playbackPositionMs)
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 22f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        color = android.graphics.Color.WHITE
                        setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
                    }

                    fun toX(value: Double): Float = (((value - bounds.minX) / rangeX).coerceIn(0.0, 1.0) * 0.82 + 0.09).toFloat() * size.width
                    fun toY(value: Double): Float = (((value - bounds.minY) / rangeY).coerceIn(0.0, 1.0) * 0.82 + 0.09).toFloat() * size.height
                    fun groundPoint(state: ResolvedTrackState): Pair<Double, Double> {
                        val obs = state.track.observations
                        val exact = obs.minByOrNull { kotlin.math.abs(it.timestampMs - targetTimestampMs) }
                        val g = exact?.groundPoint
                        return if (preview.calibrated && g != null) g.xMeters to g.yMeters
                        else ((state.detection.left + state.detection.right) * 0.5).toDouble() to state.detection.bottom.toDouble()
                    }

                    active.forEach { state ->
                        val history = TrackRenderResolver.trail(state.track, targetTimestampMs, 1500L)
                        if (history.size > 1) {
                            for (i in 1 until history.size) {
                                val a = groundPoint(history[i - 1])
                                val b = groundPoint(history[i])
                                drawLine(
                                    primary.copy(alpha = 0.12f + 0.70f * i / history.lastIndex.toFloat()),
                                    Offset(toX(a.first), toY(a.second)),
                                    Offset(toX(b.first), toY(b.second)),
                                    strokeWidth = 4f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                        val point = groundPoint(state)
                        val px = toX(point.first)
                        val py = toY(point.second)
                        drawCircle(primary, 9f, Offset(px, py))
                        drawCircle(background, 9f, Offset(px, py), style = Stroke(width = 2f))
                        drawContext.canvas.nativeCanvas.drawText("ID ${state.track.id}", px + 11f, (py - 10f).coerceAtLeast(22f), labelPaint)
                    }
                }
            }
            Text(
                "Radar position, trajectory trail and vehicle identity are sampled from the same source timestamp as the video. The trail window is time-based, not sample-count based.",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
            )
        }
    }
}

private fun activeAt(preview: AnalysisPreviewFrame, playbackPositionMs: Long): List<ResolvedTrackState> {
    val origin = preview.timelineStartTimestampMs ?: preview.frame.timestampMs
    val target = safeAddTimestamp(origin, playbackPositionMs)
    return preview.renderTracks.mapNotNull { TrackRenderResolver.resolve(it, target) }
}

private fun safeAddTimestamp(baseMs: Long, offsetMs: Long): Long = try {
    Math.addExact(baseMs, offsetMs.coerceAtLeast(0L))
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}
