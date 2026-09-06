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
import androidx.media3.ui.PlayerView
import com.smarttraffic.app.R
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.SpeedEstimateMode
import com.smarttraffic.app.domain.analysis.SpeedGate
import com.smarttraffic.app.domain.analysis.Track
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Recorded-video presentation uses the source video as the master visual clock. The player is
 * allowed to run at natural speed during analysis so the operator never sees a frozen source.
 * Track boxes are interpolated/extrapolated against that playback position. After analysis, the
 * same player continues with the completed track history rather than restarting from zero.
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

    // Keep natural playback enabled for both in-progress and completed previews. Completion must
    // not seek the already-running player back to zero because that would visibly reset the video.
    LaunchedEffect(player, preview.videoUri) {
        if (preview.videoUri != null) {
            player.playWhenReady = true
            player.play()
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.view_analysis_player, null) as PlayerView).apply {
                    this.player = player
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
            val gateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 24f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                color = android.graphics.Color.RED
                setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
            }
            val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.RED
                strokeWidth = 5f
                style = android.graphics.Paint.Style.STROKE
                setShadowLayer(6f, 0f, 1f, android.graphics.Color.BLACK)
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 28f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                color = android.graphics.Color.WHITE
                setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
            }

            fun drawGateLine(lineX1: Double, lineY1: Double, lineX2: Double, lineY2: Double, text: String) {
                val x1 = offsetX + lineX1.toFloat().coerceIn(0f, sourceWidth) * scale
                val y1 = offsetY + lineY1.toFloat().coerceIn(0f, sourceHeight) * scale
                val x2 = offsetX + lineX2.toFloat().coerceIn(0f, sourceWidth) * scale
                val y2 = offsetY + lineY2.toFloat().coerceIn(0f, sourceHeight) * scale
                drawContext.canvas.nativeCanvas.drawLine(x1, y1, x2, y2, gatePaint)
                drawContext.canvas.nativeCanvas.drawText(text, x1.coerceAtLeast(8f), y1.coerceAtLeast(30f), gateTextPaint)
            }

            if (preview.calibrated) {
                val gate: SpeedGate? = preview.speedGate
                if (gate != null) {
                    drawGateLine(gate.line1.startPixelX, gate.line1.startPixelY, gate.line1.endPixelX, gate.line1.endPixelY, "SPEED LINE 1")
                    drawGateLine(gate.line2.startPixelX, gate.line2.startPixelY, gate.line2.endPixelX, gate.line2.endPixelY, "SPEED LINE 2")
                }
            } else {
                visualGate(preview.tracks, sourceWidth.toDouble(), sourceHeight.toDouble())?.let { gate ->
                    drawGateLine(gate.line1.x1, gate.line1.y1, gate.line1.x2, gate.line1.y2, "SPEED LINE 1")
                    drawGateLine(gate.line2.x1, gate.line2.y1, gate.line2.x2, gate.line2.y2, "SPEED LINE 2")
                }
            }

            preview.tracks.forEach { track ->
                val detection = interpolatedDetection(track, positionMs) ?: return@forEach
                val left = offsetX + detection.left.coerceIn(0f, sourceWidth) * scale
                val top = offsetY + detection.top.coerceIn(0f, sourceHeight) * scale
                val right = offsetX + detection.right.coerceIn(0f, sourceWidth) * scale
                val bottom = offsetY + detection.bottom.coerceIn(0f, sourceHeight) * scale
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 4f),
                )
                val speedText = preview.speedEstimates[track.id]?.let { speed ->
                    val error = speed.errorKmh?.let { " ± %.1f".format(it) } ?: ""
                    val modeLabel = when (speed.mode) {
                        SpeedEstimateMode.CALIBRATED_GROUND_PLANE -> ""
                        SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE -> " (calibration-free estimate)"
                    }
                    " | speed: %.1f km/h%s%s".format(speed.kilometersPerHour, error, modeLabel)
                }.orEmpty()
                drawContext.canvas.nativeCanvas.drawText(
                    "car ID: ${track.id}$speedText",
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

private data class VisualGateLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val coordinate: Double,
)

private data class VisualGate(
    val line1: VisualGateLine,
    val line2: VisualGateLine,
)

/**
 * Builds two visible cross-flow lines from the observed traffic corridor. Unlike the old image
 * gate, the segment is restricted to the transverse span occupied by tracked vehicles, so lines
 * do not blindly cover the whole frame or drift into empty sky/sidewalk regions.
 */
private fun visualGate(tracks: List<Track>, width: Double, height: Double): VisualGate? {
    data class Point(val x: Double, val y: Double)

    val points = tracks.flatMap { track ->
        track.observations.takeLast(40).map { observation ->
            val d = observation.detection
            Point((d.left + d.right) * 0.5, d.bottom.toDouble())
        }
    }.filter { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..width && it.y in 0.0..height }
    if (points.size < 6) return null

    val motion = buildList {
        tracks.forEach { track ->
            val observations = track.observations.takeLast(40)
            for (i in 1 until observations.size) {
                val a = observations[i - 1]
                val b = observations[i]
                val dt = (b.timestampMs - a.timestampMs) / 1000.0
                if (!dt.isFinite() || dt <= 0.0 || dt > 0.6) continue
                val ax = ((a.detection.left + a.detection.right) * 0.5).toDouble()
                val ay = a.detection.bottom.toDouble()
                val bx = ((b.detection.left + b.detection.right) * 0.5).toDouble()
                val by = b.detection.bottom.toDouble()
                val dx = (bx - ax) / dt
                val dy = (by - ay) / dt
                val magnitude = hypot(dx, dy)
                if (magnitude.isFinite() && magnitude >= 1.0) add(dx / magnitude to dy / magnitude)
            }
        }
    }
    if (motion.size < 3) return null

    var cxx = 0.0
    var cyy = 0.0
    var cxy = 0.0
    motion.forEach { (x, y) ->
        cxx += x * x
        cyy += y * y
        cxy += x * y
    }
    val theta = 0.5 * atan2(2.0 * cxy, cxx - cyy)
    var axisX = cos(theta)
    var axisY = sin(theta)
    val norm = hypot(axisX, axisY)
    if (!norm.isFinite() || norm <= 1e-9) return null
    axisX /= norm
    axisY /= norm
    if (motion.sumOf { (x, y) -> x * axisX + y * axisY } < 0.0) {
        axisX = -axisX
        axisY = -axisY
    }

    val centerX = points.map { it.x }.average()
    val centerY = points.map { it.y }.average()
    val normalX = -axisY
    val normalY = axisX

    val longitudinal = points.map { it.x * axisX + it.y * axisY }.sorted()
    if (longitudinal.size < 6) return null
    val span = (longitudinal.last() - longitudinal.first()).coerceAtLeast(1.0)
    val q1 = percentile(longitudinal, 0.35)
    val q2 = percentile(longitudinal, 0.65)
    val minSeparation = max(12.0, span * 0.15)
    val adjustedQ2 = if (q2 - q1 >= minSeparation) q2 else min(longitudinal.last(), q1 + minSeparation)
    val adjustedQ1 = if (adjustedQ2 - q1 >= minSeparation) q1 else max(longitudinal.first(), adjustedQ2 - minSeparation)
    if (adjustedQ2 <= adjustedQ1) return null

    val transverse = points.map { it.x * normalX + it.y * normalY }.sorted()
    val low = percentile(transverse, 0.05)
    val high = percentile(transverse, 0.95)
    val margin = max(16.0, (high - low) * 0.08)
    val transverseLow = low - margin
    val transverseHigh = high + margin

    fun lineAt(coordinate: Double): VisualGateLine? {
        val baseX = centerX + axisX * (coordinate - (centerX * axisX + centerY * axisY))
        val baseY = centerY + axisY * (coordinate - (centerX * axisX + centerY * axisY))
        val aX = baseX + normalX * transverseLow
        val aY = baseY + normalY * transverseLow
        val bX = baseX + normalX * transverseHigh
        val bY = baseY + normalY * transverseHigh
        val clipped = clipLineSegment(aX, aY, bX, bY, width, height) ?: return null
        return VisualGateLine(clipped.first.first, clipped.first.second, clipped.second.first, clipped.second.second, coordinate)
    }

    val line1 = lineAt(adjustedQ1) ?: return null
    val line2 = lineAt(adjustedQ2) ?: return null
    if (hypot(line2.x1 - line1.x1, line2.y1 - line1.y1) < 8.0 && hypot(line2.x2 - line1.x2, line2.y2 - line1.y2) < 8.0) return null
    return VisualGate(line1, line2)
}

private fun percentile(sorted: List<Double>, p: Double): Double {
    val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex
    val lower = position.toInt()
    val upper = min(sorted.lastIndex, lower + 1)
    if (lower == upper) return sorted[lower]
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

private fun clipLineSegment(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    width: Double,
    height: Double,
): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
    var t0 = 0.0
    var t1 = 1.0
    val dx = x1 - x0
    val dy = y1 - y0

    fun clip(p: Double, q: Double): Boolean {
        if (abs(p) < 1e-12) return q >= 0.0
        val r = q / p
        if (p < 0.0) {
            if (r > t1) return false
            if (r > t0) t0 = r
        } else {
            if (r < t0) return false
            if (r < t1) t1 = r
        }
        return true
    }

    if (!clip(-dx, x0)) return null
    if (!clip(dx, width - x0)) return null
    if (!clip(-dy, y0)) return null
    if (!clip(dy, height - y0)) return null
    return (x0 + t0 * dx to y0 + t0 * dy) to (x0 + t1 * dx to y0 + t1 * dy)
}

private fun interpolatedDetection(track: Track, positionMs: Long): Detection? {
    val observations = track.observations.sortedBy { it.timestampMs }
    if (observations.isEmpty()) return null

    val first = observations.first()
    val last = observations.last()
    if (positionMs < first.timestampMs) return null
    if (positionMs > last.timestampMs) {
        val previous = observations.asReversed().drop(1).firstOrNull { it.timestampMs < last.timestampMs } ?: return null
        if (last.timestampMs <= previous.timestampMs) return null
        val extrapolationMs = (positionMs - last.timestampMs).coerceAtMost(350L)
        if (positionMs - last.timestampMs > 350L) return null
        val dt = (last.timestampMs - previous.timestampMs).toFloat()
        if (dt <= 0f) return null
        return extrapolateDetection(previous.detection, last.detection, extrapolationMs.toFloat() / dt)
    }

    val before = observations.lastOrNull { it.timestampMs <= positionMs }
    val after = observations.firstOrNull { it.timestampMs >= positionMs }
    if (before != null && after != null && before.timestampMs != after.timestampMs) {
        val ratio = ((positionMs - before.timestampMs).toDouble() / (after.timestampMs - before.timestampMs).toDouble()).coerceIn(0.0, 1.0)
        return interpolateDetection(before.detection, after.detection, ratio)
    }
    return before?.detection ?: after?.detection
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
