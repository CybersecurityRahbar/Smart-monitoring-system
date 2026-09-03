package com.smarttraffic.app.features.analysis

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.Track
import kotlin.math.max

@Composable
fun AnalysisRadarPreview(
    preview: AnalysisPreviewFrame?,
    modifier: Modifier = Modifier,
) {
    if (preview == null) return

    var fullscreen by remember { mutableStateOf(false) }
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black,
            ) {
                ImmersiveVideo(
                    preview = preview,
                    primary = primary,
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFFD0D7DA),
                    onClose = { fullscreen = false },
                )
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

                val ratio = preview.bitmap.width.toFloat() / max(1, preview.bitmap.height).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .background(surfaceVariant, RoundedCornerShape(16.dp)),
                ) {
                    VideoFrameWithTracks(
                        preview = preview,
                        modifier = Modifier.fillMaxSize(),
                        primary = primary,
                        showClose = false,
                    )
                }
            }
        }
        RadarPanel(
            preview = preview,
            primary = primary,
            surfaceVariant = surfaceVariant,
            background = background,
            outline = outline,
            onSurfaceVariant = onSurfaceVariant,
        )
    }
}

@Composable
private fun ImmersiveVideo(
    preview: AnalysisPreviewFrame,
    primary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VideoFrameWithTracks(
            preview = preview,
            modifier = Modifier.fillMaxSize(),
            primary = primary,
            showClose = true,
            onClose = onClose,
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Full-screen analysis", color = onSurface, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${preview.tracks.size} tracks • ${preview.frame.timestampMs} ms • ${if (preview.calibrated) "metric" else "visual"} radar",
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun VideoFrameWithTracks(
    preview: AnalysisPreviewFrame,
    modifier: Modifier,
    primary: Color,
    showClose: Boolean,
    onClose: () -> Unit = {},
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
            val contentWidth = sourceWidth * scale
            val contentHeight = sourceHeight * scale
            val offsetX = (size.width - contentWidth) * 0.5f
            val offsetY = (size.height - contentHeight) * 0.5f

            preview.tracks.forEach { track ->
                val observation = track.observations.lastOrNull { it.frameIndex == preview.frame.index }
                    ?: track.observations.lastOrNull()
                    ?: return@forEach
                val d = observation.detection
                val left = offsetX + d.left * scale
                val top = offsetY + d.top * scale
                val right = offsetX + d.right * scale
                val bottom = offsetY + d.bottom * scale
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
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

@Composable
private fun RadarPanel(
    preview: AnalysisPreviewFrame,
    primary: Color,
    surfaceVariant: Color,
    background: Color,
    outline: Color,
    onSurfaceVariant: Color,
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tracking radar", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (preview.calibrated) "ground-plane coordinates" else "image-space visualization",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                )
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

                    val points = preview.tracks.mapNotNull { track ->
                        val o = track.observations.lastOrNull { it.frameIndex == preview.frame.index }
                            ?: track.observations.lastOrNull()
                            ?: return@mapNotNull null
                        val ground = o.groundPoint
                        if (ground != null) {
                            RadarPoint(track, ground.xMeters, ground.yMeters)
                        } else {
                            val d = o.detection
                            val x = ((d.left + d.right) * 0.5 / preview.frame.width) * 100.0
                            val y = (1.0 - d.bottom / preview.frame.height.toDouble()) * 100.0
                            RadarPoint(track, x, y)
                        }
                    }

                    if (points.isNotEmpty()) {
                        val minX = points.minOf { it.x }
                        val maxX = points.maxOf { it.x }
                        val minY = points.minOf { it.y }
                        val maxY = points.maxOf { it.y }
                        val rangeX = max(maxX - minX, 1e-6)
                        val rangeY = max(maxY - minY, 1e-6)

                        points.forEach { point ->
                            val history = point.track.observations
                                .takeLast(12)
                                .mapNotNull { o ->
                                    val g = o.groundPoint
                                    if (g != null) {
                                        RadarPoint(point.track, g.xMeters, g.yMeters)
                                    } else {
                                        val d = o.detection
                                        RadarPoint(
                                            point.track,
                                            ((d.left + d.right) * 0.5 / preview.frame.width) * 100.0,
                                            (1.0 - d.bottom / preview.frame.height.toDouble()) * 100.0,
                                        )
                                    }
                                }
                            if (history.size > 1) {
                                for (i in 1 until history.size) {
                                    val a = history[i - 1]
                                    val b = history[i]
                                    val ax = (((a.x - minX) / rangeX) * 0.82 + 0.09) * size.width
                                    val ay = ((1.0 - (a.y - minY) / rangeY) * 0.82 + 0.09) * size.height
                                    val bx = (((b.x - minX) / rangeX) * 0.82 + 0.09) * size.width
                                    val by = ((1.0 - (b.y - minY) / rangeY) * 0.82 + 0.09) * size.height
                                    drawLine(
                                        primary.copy(alpha = 0.18f + (i.toFloat() / history.lastIndex) * 0.52f),
                                        Offset(ax.toFloat(), ay.toFloat()),
                                        Offset(bx.toFloat(), by.toFloat()),
                                        strokeWidth = 3f,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }

                            val px = (((point.x - minX) / rangeX) * 0.82 + 0.09) * size.width
                            val py = ((1.0 - (point.y - minY) / rangeY) * 0.82 + 0.09) * size.height
                            drawCircle(primary, 8f, Offset(px.toFloat(), py.toFloat()))
                            drawCircle(background, 8f, Offset(px.toFloat(), py.toFloat()), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                        }
                    }
                }
            }
            if (!preview.calibrated) {
                Text(
                    "Radar follows tracked history. Metric position and speed remain blocked until validated road calibration is supplied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurfaceVariant,
                )
            }
        }
    }
}

private data class RadarPoint(val track: Track, val x: Double, val y: Double)
