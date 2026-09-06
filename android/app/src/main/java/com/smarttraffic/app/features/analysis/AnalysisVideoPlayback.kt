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
 * Recorded-video presentation uses the source video as the master visual clock. The player runs at
 * natural speed. Tracking analytics remain timestamp-authentic; this renderer applies a separate
 * short-horizon smoother/interpolator so green boxes look continuous and cinematic.
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
            val renderTracks = preview.renderTracks
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

            fun drawGateLine(x1: Double, y1: Double, x2: Double, y2: Double, text: String) {
                val sx1 = offsetX + x1.toFloat().coerceIn(0f, sourceWidth) * scale
                val sy1 = offsetY + y1.toFloat().coerceIn(0f, sourceHeight) * scale
                val sx2 = offsetX + x2.toFloat().coerceIn(0f, sourceWidth) * scale
                val sy2 = offsetY + y2.toFloat().coerceIn(0f, sourceHeight) * scale
                drawContext.canvas.nativeCanvas.drawLine(sx1, sy1, sx2, sy2, gatePaint)
                drawContext.canvas.nativeCanvas.drawText(text, sx1.coerceAtLeast(8f), sy1.coerceAtLeast(30f), gateTextPaint)
            }

            if (preview.calibrated) {
                preview.speedGate?.let { gate ->
                    drawGateLine(gate.line1.startPixelX, gate.line1.startPixelY, gate.line1.endPixelX, gate.line1.endPixelY, "SPEED LINE 1")
                    drawGateLine(gate.line2.startPixelX, gate.line2.startPixelY, gate.line2.endPixelX, gate.line2.endPixelY, "SPEED LINE 2")
                }
            } else {
                visualGate(renderTracks, sourceWidth.toDouble(), sourceHeight.toDouble())?.let { gate ->
                    drawGateLine(gate.line1.x1, gate.line1.y1, gate.line1.x2, gate.line1.y2, "SPEED LINE 1")
                    drawGateLine(gate.line2.x1, gate.line2.y1, gate.line2.x2, gate.line2.y2, "SPEED LINE 2")
                }
            }

            // The recorded source timeline is authoritative. A vehicle entering the frame late
            // must never redefine t=0 for the overlay.
            val timelineOriginMs = preview.timelineStartTimestampMs ?: preview.frame.timestampMs
            val targetTimestampMs = safeAddTimestamp(timelineOriginMs, positionMs)

            renderTracks.forEach { track ->
                val detection = cinematicDetection(track, targetTimestampMs) ?: return@forEach
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

private data class VisualGateLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
private data class VisualGate(val line1: VisualGateLine, val line2: VisualGateLine)

/** Builds two short cross-flow gate lines from the observed traffic corridor. */
private fun visualGate(tracks: List<Track>, width: Double, height: Double): VisualGate? {
    data class Point(val x: Double, val y: Double)
    val points = tracks.flatMap { track ->
        track.observations.takeLast(40).map { o ->
            val d = o.detection
            Point((d.left + d.right) * 0.5, d.bottom.toDouble())
        }
    }.filter { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..width && it.y in 0.0..height }
    if (points.size < 6) return null

    val motion = buildList {
        tracks.forEach { track ->
            val o = track.observations.takeLast(40)
            for (i in 1 until o.size) {
                val a = o[i - 1]
                val b = o[i]
                val dt = (b.timestampMs - a.timestampMs) / 1000.0
                if (!dt.isFinite() || dt <= 0.0 || dt > 0.6) continue
                val ax = ((a.detection.left + a.detection.right) * 0.5).toDouble()
                val ay = a.detection.bottom.toDouble()
                val bx = ((b.detection.left + b.detection.right) * 0.5).toDouble()
                val by = b.detection.bottom.toDouble()
                val dx = (bx - ax) / dt
                val dy = (by - ay) / dt
                val m = hypot(dx, dy)
                if (m.isFinite() && m >= 1.0) add(dx / m to dy / m)
            }
        }
    }
    if (motion.size < 3) return null

    var cxx = 0.0
    var cyy = 0.0
    var cxy = 0.0
    motion.forEach { (x, y) -> cxx += x * x; cyy += y * y; cxy += x * y }
    val theta = 0.5 * atan2(2.0 * cxy, cxx - cyy)
    var axisX = cos(theta)
    var axisY = sin(theta)
    val n = hypot(axisX, axisY)
    if (!n.isFinite() || n <= 1e-9) return null
    axisX /= n
    axisY /= n
    if (motion.sumOf { (x, y) -> x * axisX + y * axisY } < 0.0) { axisX = -axisX; axisY = -axisY }

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
        val centerCoordinate = centerX * axisX + centerY * axisY
        val baseX = centerX + axisX * (coordinate - centerCoordinate)
        val baseY = centerY + axisY * (coordinate - centerCoordinate)
        val aX = baseX + normalX * transverseLow
        val aY = baseY + normalY * transverseLow
        val bX = baseX + normalX * transverseHigh
        val bY = baseY + normalY * transverseHigh
        val clipped = clipLineSegment(aX, aY, bX, bY, width, height) ?: return null
        return VisualGateLine(clipped.first.first, clipped.first.second, clipped.second.first, clipped.second.second)
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

private fun clipLineSegment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, height: Double): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
    var t0 = 0.0
    var t1 = 1.0
    val dx = x1 - x0
    val dy = y1 - y0
    fun clip(p: Double, q: Double): Boolean {
        if (abs(p) < 1e-12) return q >= 0.0
        val r = q / p
        if (p < 0.0) { if (r > t1) return false; if (r > t0) t0 = r }
        else { if (r < t0) return false; if (r < t1) t1 = r }
        return true
    }
    if (!clip(-dx, x0)) return null
    if (!clip(dx, width - x0)) return null
    if (!clip(-dy, y0)) return null
    if (!clip(dy, height - y0)) return null
    return (x0 + t0 * dx to y0 + t0 * dy) to (x0 + t1 * dx to y0 + t1 * dy)
}

private data class RenderSample(
    val timestampMs: Long,
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
)

/** Render-only trajectory smoothing; raw detector history and analytics are unchanged. */
private fun cinematicDetection(track: Track, targetTimestampMs: Long): Detection? {
    val observations = track.observations.asSequence()
        .sortedWith(compareBy { it.timestampMs })
        .map { o ->
            val d = o.detection
            RenderSample(o.timestampMs, ((d.left + d.right) * 0.5).toDouble(), ((d.top + d.bottom) * 0.5).toDouble(),
                (d.right - d.left).toDouble().coerceAtLeast(1.0), (d.bottom - d.top).toDouble().coerceAtLeast(1.0), d.confidence.toDouble().coerceIn(0.0, 1.0))
        }.toList()
    if (observations.isEmpty()) return null

    val samples = trailingSmooth(observations, 5)
    val first = samples.first()
    val last = samples.last()
    if (targetTimestampMs < first.timestampMs) return null

    if (targetTimestampMs >= last.timestampMs) {
        val gapMs = targetTimestampMs - last.timestampMs
        // Rendering can bridge small decode/preview gaps, but never extrapolate indefinitely.
        if (gapMs > 350L) return null
        val velocity = robustVelocity(samples.takeLast(5))
        return renderSampleToDetection(boundedExtrapolation(last, velocity, gapMs / 1000.0), last.confidence)
    }

    val upperIndex = samples.indexOfFirst { it.timestampMs >= targetTimestampMs }
    if (upperIndex < 0) return null
    if (upperIndex == 0) return renderSampleToDetection(first, first.confidence)
    val lowerIndex = upperIndex - 1
    val a = samples[lowerIndex]
    val b = samples[upperIndex]
    val dtMs = b.timestampMs - a.timestampMs
    if (dtMs <= 0L) return renderSampleToDetection(a, a.confidence)
    val ratio = ((targetTimestampMs - a.timestampMs).toDouble() / dtMs.toDouble()).coerceIn(0.0, 1.0)
    val previous = samples.getOrNull(lowerIndex - 1) ?: a
    val next = samples.getOrNull(upperIndex + 1) ?: b
    val interpolated = hermite(a, b, finiteDifference(previous, b), finiteDifference(a, next), ratio)
    return renderSampleToDetection(interpolated, max(a.confidence, b.confidence))
}

private fun trailingSmooth(samples: List<RenderSample>, radius: Int): List<RenderSample> {
    if (samples.size < 3) return samples
    return samples.indices.map { index ->
        val window = samples.subList(max(0, index - radius + 1), index + 1)
        val weights = window.indices.map { (it + 1).toDouble() }
        val sum = weights.sum().coerceAtLeast(1.0)
        fun avg(selector: (RenderSample) -> Double): Double = window.indices.sumOf { selector(window[it]) * weights[it] } / sum
        RenderSample(samples[index].timestampMs, avg { it.centerX }, avg { it.centerY }, avg { it.width }.coerceAtLeast(1.0), avg { it.height }.coerceAtLeast(1.0), window.maxOf { it.confidence })
    }
}

private data class RenderVelocity(val xPerSecond: Double, val yPerSecond: Double, val widthPerSecond: Double, val heightPerSecond: Double)

private fun robustVelocity(samples: List<RenderSample>): RenderVelocity {
    if (samples.size < 2) return RenderVelocity(0.0, 0.0, 0.0, 0.0)
    val pairs = samples.zipWithNext().mapNotNull { (a, b) ->
        val dt = (b.timestampMs - a.timestampMs) / 1000.0
        if (!dt.isFinite() || dt <= 0.0) null else listOf((b.centerX - a.centerX) / dt, (b.centerY - a.centerY) / dt, (b.width - a.width) / dt, (b.height - a.height) / dt)
    }
    if (pairs.isEmpty()) return RenderVelocity(0.0, 0.0, 0.0, 0.0)
    fun median(index: Int): Double {
        val values = pairs.map { it[index] }.filter { it.isFinite() }.sorted()
        return if (values.isEmpty()) 0.0 else percentile(values, 0.5)
    }
    return RenderVelocity(median(0), median(1), median(2), median(3))
}

private fun boundedExtrapolation(sample: RenderSample, velocity: RenderVelocity, dtSeconds: Double): RenderSample {
    val horizon = dtSeconds.coerceIn(0.0, 0.35)
    val damping = (1.0 - 0.22 * horizon / 0.35).coerceIn(0.72, 1.0)
    return sample.copy(
        centerX = sample.centerX + velocity.xPerSecond * horizon * damping,
        centerY = sample.centerY + velocity.yPerSecond * horizon * damping,
        width = (sample.width + velocity.widthPerSecond * horizon * damping).coerceAtLeast(1.0),
        height = (sample.height + velocity.heightPerSecond * horizon * damping).coerceAtLeast(1.0),
    )
}

private fun finiteDifference(a: RenderSample, b: RenderSample): RenderVelocity {
    val dt = (b.timestampMs - a.timestampMs) / 1000.0
    if (!dt.isFinite() || dt <= 0.0) return RenderVelocity(0.0, 0.0, 0.0, 0.0)
    return RenderVelocity((b.centerX - a.centerX) / dt, (b.centerY - a.centerY) / dt, (b.width - a.width) / dt, (b.height - a.height) / dt)
}

private fun hermite(a: RenderSample, b: RenderSample, tangentA: RenderVelocity, tangentB: RenderVelocity, t: Double): RenderSample {
    val dt = ((b.timestampMs - a.timestampMs).coerceAtLeast(1L)) / 1000.0
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
    val h10 = t3 - 2.0 * t2 + t
    val h01 = -2.0 * t3 + 3.0 * t2
    val h11 = t3 - t2
    fun curve(p0: Double, v0: Double, p1: Double, v1: Double): Double = h00 * p0 + h10 * v0 * dt + h01 * p1 + h11 * v1 * dt
    fun bounded(v: Double, p0: Double, p1: Double, padding: Double): Double = v.coerceIn(min(p0, p1) - padding, max(p0, p1) + padding)
    return RenderSample(
        timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * t).toLong(),
        centerX = bounded(curve(a.centerX, tangentA.xPerSecond, b.centerX, tangentB.xPerSecond), a.centerX, b.centerX, max(2.0, abs(b.centerX - a.centerX) * 0.08)),
        centerY = bounded(curve(a.centerY, tangentA.yPerSecond, b.centerY, tangentB.yPerSecond), a.centerY, b.centerY, max(2.0, abs(b.centerY - a.centerY) * 0.08)),
        width = bounded(curve(a.width, tangentA.widthPerSecond, b.width, tangentB.widthPerSecond), a.width, b.width, max(1.0, abs(b.width - a.width) * 0.10)).coerceAtLeast(1.0),
        height = bounded(curve(a.height, tangentA.heightPerSecond, b.height, tangentB.heightPerSecond), a.height, b.height, max(1.0, abs(b.height - a.height) * 0.10)).coerceAtLeast(1.0),
        confidence = max(a.confidence, b.confidence),
    )
}

private fun renderSampleToDetection(sample: RenderSample, confidence: Double): Detection {
    val halfWidth = sample.width.toFloat().coerceAtLeast(0.5f) * 0.5f
    val halfHeight = sample.height.toFloat().coerceAtLeast(0.5f) * 0.5f
    return Detection(
        classId = 2,
        className = "vehicle",
        confidence = confidence.toFloat().coerceIn(0f, 1f),
        left = sample.centerX.toFloat() - halfWidth,
        top = sample.centerY.toFloat() - halfHeight,
        right = sample.centerX.toFloat() + halfWidth,
        bottom = sample.centerY.toFloat() + halfHeight,
        frameIndex = 0L,
        timestampMs = sample.timestampMs,
    )
}

private fun safeAddTimestamp(baseMs: Long, offsetMs: Long): Long = try {
    Math.addExact(baseMs, offsetMs.coerceAtLeast(0L))
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}
