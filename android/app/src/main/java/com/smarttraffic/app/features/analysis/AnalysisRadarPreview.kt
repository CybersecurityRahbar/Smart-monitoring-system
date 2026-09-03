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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.Track
import kotlin.math.max

@Composable
fun AnalysisRadarPreview(
    preview: AnalysisPreviewFrame?,
    modifier: Modifier = Modifier,
) {
    if (preview == null) return

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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (preview.calibrated) "METRIC RADAR" else "VISUAL RADAR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val ratio = preview.bitmap.width.toFloat() / max(1, preview.bitmap.height).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                ) {
                    Image(
                        bitmap = preview.bitmap.asImageBitmapCompat(),
                        contentDescription = "Analyzed video frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val sx = size.width / preview.frame.width.toFloat()
                        val sy = size.height / preview.frame.height.toFloat()
                        preview.tracks.forEach { track ->
                            val observation = track.observations.lastOrNull { it.frameIndex == preview.frame.index }
                                ?: track.observations.lastOrNull()
                                ?: return@forEach
                            val d = observation.detection
                            val left = d.left * sx
                            val top = d.top * sy
                            val right = d.right * sx
                            val bottom = d.bottom * sy
                            drawRoundRect(
                                color = MaterialTheme.colorScheme.primary,
                                topLeft = Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                                style = Stroke(width = 3f),
                            )
                            val label = buildString {
                                append("#")
                                append(track.id)
                                append(' ')
                                append(track.className)
                                preview.speedEstimates[track.id]?.let { speed ->
                                    append(" • ")
                                    append("%.1f km/h".format(speed.kilometersPerHour))
                                }
                            }
                            drawLabel(label, Offset(left + 4f, max(16f, top - 5f)))
                        }
                    }
                }
            }
        }
        RadarPanel(preview)
    }
}

@Composable
private fun RadarPanel(preview: AnalysisPreviewFrame) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tracking radar", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (preview.calibrated) "ground-plane coordinates" else "image-space visualization",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.75f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background),
                        ),
                    )
                    for (i in 1..4) {
                        val x = size.width * i / 5f
                        val y = size.height * i / 5f
                        drawLine(MaterialTheme.colorScheme.outlineVariant, Offset(x, 0f), Offset(x, size.height), 1f)
                        drawLine(MaterialTheme.colorScheme.outlineVariant, Offset(0f, y), Offset(size.width, y), 1f)
                    }

                    val points = preview.tracks.mapNotNull { track ->
                        val o = track.observations.lastOrNull() ?: return@mapNotNull null
                        val ground = o.groundPoint
                        if (ground != null) RadarPoint(track, ground.xMeters, ground.yMeters)
                        else {
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
                            val px = (((point.x - minX) / rangeX) * 0.82 + 0.09) * size.width
                            val py = ((1.0 - (point.y - minY) / rangeY) * 0.82 + 0.09) * size.height
                            drawCircle(MaterialTheme.colorScheme.primary, 8f, Offset(px.toFloat(), py.toFloat()))
                            drawCircle(MaterialTheme.colorScheme.background, 8f, Offset(px.toFloat(), py.toFloat()), style = Stroke(2f))
                            val speed = preview.speedEstimates[point.track.id]
                            val text = buildString {
                                append("#")
                                append(point.track.id)
                                speed?.let { append("  "); append("%.1f".format(it.kilometersPerHour)); append(" km/h") }
                            }
                            drawLabel(text, Offset(px.toFloat() + 10f, py.toFloat() - 4f))
                        }
                    }
                }
            }
            if (!preview.calibrated) {
                Text(
                    "Radar follows the tracked vehicle visually. Metric position and speed remain blocked until validated road calibration is supplied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class RadarPoint(val track: Track, val x: Double, val y: Double)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(text: String, anchor: Offset) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        drawText(text, anchor.x, anchor.y, paint)
    }
}

private fun android.graphics.Bitmap.asImageBitmapCompat(): androidx.compose.ui.graphics.ImageBitmap =
    androidx.compose.ui.graphics.asImageBitmap()
