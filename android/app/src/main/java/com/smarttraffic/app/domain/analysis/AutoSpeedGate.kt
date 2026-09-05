package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A pair of virtual timing lines and their metric separation when calibration exists. */
data class SpeedGateLine(
    val startPixelX: Double,
    val startPixelY: Double,
    val endPixelX: Double,
    val endPixelY: Double,
    /** Coordinate along the inferred longitudinal traffic axis. */
    val coordinate: Double,
)

data class SpeedGate(
    val line1: SpeedGateLine,
    val line2: SpeedGateLine,
    val separationMeters: Double?,
    val axisX: Double,
    val axisY: Double,
    val calibrated: Boolean,
)

/**
 * Scene-adaptive timing-gate builder.
 *
 * 1. Collects real vehicle contact-point observations.
 * 2. Infers the dominant road/traffic axis with PCA over the observed trajectory point cloud.
 * 3. Places two cross-flow timing lines at robust longitudinal quantiles.
 * 4. Freezes those lines for the rest of the replay; no per-frame min/max normalization occurs.
 *
 * In calibrated mode all metric geometry is derived in the validated ground plane, then inverse
 * projected into the actual image. In uncalibrated mode the lines are visual-only and metric speed
 * is intentionally unavailable.
 */
object AutoSpeedGateBuilder {
    fun build(
        tracks: List<Track>,
        imageWidth: Int,
        imageHeight: Int,
        calibration: CalibrationProfile? = null,
    ): SpeedGate? {
        if (imageWidth <= 1 || imageHeight <= 1) return null
        val samples = collectSamples(tracks, calibration)
        if (samples.size < 6) return null

        val axis = principalAxis(samples) ?: return null
        val centerX = samples.map { it.x }.average()
        val centerY = samples.map { it.y }.average()
        val centerProjection = centerX * axis.x + centerY * axis.y
        val longitudinal = samples.map { it.x * axis.x + it.y * axis.y }.sorted()
        val line1Coordinate = percentile(longitudinal, 0.35)
        val line2Coordinate = percentile(longitudinal, 0.65)
        val separation = abs(line2Coordinate - line1Coordinate)
        if (!separation.isFinite() || separation <= 1e-6) return null

        return if (calibration == null) {
            val p1 = pointAtProjection(centerX, centerY, centerProjection, axis.x, axis.y, line1Coordinate)
            val p2 = pointAtProjection(centerX, centerY, centerProjection, axis.x, axis.y, line2Coordinate)
            SpeedGate(
                line1 = imageLine(p1.first, p1.second, axis.x, axis.y, imageWidth.toDouble(), imageHeight.toDouble(), line1Coordinate),
                line2 = imageLine(p2.first, p2.second, axis.x, axis.y, imageWidth.toDouble(), imageHeight.toDouble(), line2Coordinate),
                separationMeters = null,
                axisX = axis.x,
                axisY = axis.y,
                calibrated = false,
            )
        } else {
            val projector = HomographyProjector(calibration.homography)
            val corners = listOf(
                0.0 to 0.0,
                imageWidth.toDouble() to 0.0,
                imageWidth.toDouble() to imageHeight.toDouble(),
                0.0 to imageHeight.toDouble(),
            )
            val worldCorners = corners.mapNotNull { (x, y) -> runCatching { projector.project(x, y) }.getOrNull() }
            if (worldCorners.size != 4) return null

            val normalX = -axis.y
            val normalY = axis.x
            val transverse = worldCorners.map { p -> p.xMeters * normalX + p.yMeters * normalY }.sorted()
            val transverseLow = percentile(transverse, 0.05)
            val transverseHigh = percentile(transverse, 0.95)
            val transverseMargin = max(0.25, (transverseHigh - transverseLow) * 0.05)
            val low = transverseLow - transverseMargin
            val high = transverseHigh + transverseMargin
            if (!low.isFinite() || !high.isFinite() || high <= low) return null

            fun metricLine(coordinate: Double): SpeedGateLine? {
                val a = worldFromAxes(coordinate, low, axis.x, axis.y)
                val b = worldFromAxes(coordinate, high, axis.x, axis.y)
                val imageA = runCatching { inverseProject(calibration.homography, a.first, a.second) }.getOrNull() ?: return null
                val imageB = runCatching { inverseProject(calibration.homography, b.first, b.second) }.getOrNull() ?: return null
                val clipped = clipSegment(imageA.first, imageA.second, imageB.first, imageB.second, imageWidth.toDouble(), imageHeight.toDouble()) ?: return null
                return SpeedGateLine(clipped.first.first, clipped.first.second, clipped.second.first, clipped.second.second, coordinate)
            }

            val line1 = metricLine(line1Coordinate) ?: return null
            val line2 = metricLine(line2Coordinate) ?: return null
            SpeedGate(
                line1 = line1,
                line2 = line2,
                separationMeters = separation,
                axisX = axis.x,
                axisY = axis.y,
                calibrated = true,
            )
        }
    }

    private data class Sample(val x: Double, val y: Double, val t: Long)
    private data class Axis(val x: Double, val y: Double)

    private fun collectSamples(tracks: List<Track>, calibration: CalibrationProfile?): List<Sample> =
        tracks.flatMap { track ->
            track.observations.takeLast(40).mapNotNull { observation ->
                if (calibration == null) {
                    val d = observation.detection
                    Sample((d.left + d.right) * 0.5, d.bottom.toDouble(), observation.timestampMs)
                } else {
                    observation.groundPoint?.let { Sample(it.xMeters, it.yMeters, observation.timestampMs) }
                }
            }
        }.filter { it.x.isFinite() && it.y.isFinite() && it.t >= 0L }

    /** Dominant axis of the observed road/trajectory point cloud, independent of track order. */
    private fun principalAxis(samples: List<Sample>): Axis? {
        if (samples.size < 2) return null
        val meanX = samples.map { it.x }.average()
        val meanY = samples.map { it.y }.average()
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        samples.forEach { sample ->
            val dx = sample.x - meanX
            val dy = sample.y - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        if (!sxx.isFinite() || !syy.isFinite() || !sxy.isFinite()) return null
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var ux = cos(theta)
        var uy = sin(theta)
        val norm = hypot(ux, uy)
        if (!norm.isFinite() || norm < 1e-9) return null
        ux /= norm
        uy /= norm

        // PCA has no inherent sign. Orient it using the median signed displacement from each
        // individual track so opposite traffic directions never get concatenated accidentally.
        val signedDirections = mutableListOf<Double>()
        // Grouping is intentionally local to each track; caller supplies samples in track order
        // only indirectly, so use the sample time sequence as a stable scene-level orientation cue.
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            val dt = (current.t - previous.t) / 1000.0
            if (dt <= 0.0 || dt > 1.0) continue
            val vx = (current.x - previous.x) / dt
            val vy = (current.y - previous.y) / dt
            val speed = hypot(vx, vy)
            if (!speed.isFinite() || speed < 1e-6) continue
            signedDirections += vx * ux + vy * uy
        }
        if (signedDirections.isNotEmpty() && median(signedDirections) < 0.0) {
            ux = -ux
            uy = -uy
        }
        return Axis(ux, uy)
    }

    private fun pointAtProjection(
        centerX: Double,
        centerY: Double,
        centerProjection: Double,
        axisX: Double,
        axisY: Double,
        targetProjection: Double,
    ): Pair<Double, Double> {
        val delta = targetProjection - centerProjection
        return centerX + axisX * delta to centerY + axisY * delta
    }

    private fun imageLine(
        cx: Double,
        cy: Double,
        axisX: Double,
        axisY: Double,
        width: Double,
        height: Double,
        coordinate: Double,
    ): SpeedGateLine {
        val nx = -axisY
        val ny = axisX
        val candidates = ArrayList<Pair<Double, Double>>(4)
        fun add(t: Double) {
            val x = cx + nx * t
            val y = cy + ny * t
            if (x in 0.0..width && y in 0.0..height) candidates += x to y
        }
        if (abs(nx) > 1e-9) {
            add(-cx / nx)
            add((width - cx) / nx)
        }
        if (abs(ny) > 1e-9) {
            add(-cy / ny)
            add((height - cy) / ny)
        }
        if (candidates.size >= 2) return SpeedGateLine(candidates[0].first, candidates[0].second, candidates[1].first, candidates[1].second, coordinate)
        return SpeedGateLine(0.0, 0.0, width, height, coordinate)
    }

    private fun worldFromAxes(longitudinal: Double, transverse: Double, axisX: Double, axisY: Double): Pair<Double, Double> {
        val nx = -axisY
        val ny = axisX
        return longitudinal * axisX + transverse * nx to longitudinal * axisY + transverse * ny
    }

    private fun inverseProject(h: List<Double>, x: Double, y: Double): Pair<Double, Double> {
        require(h.size == 9)
        val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) + h[2] * (h[3] * h[7] - h[4] * h[6])
        require(abs(det) > 1e-12 && det.isFinite()) { "Homography is singular" }
        val inv = doubleArrayOf(
            (h[4] * h[8] - h[5] * h[7]) / det,
            (h[2] * h[7] - h[1] * h[8]) / det,
            (h[1] * h[5] - h[2] * h[4]) / det,
            (h[5] * h[6] - h[3] * h[8]) / det,
            (h[0] * h[8] - h[2] * h[6]) / det,
            (h[2] * h[3] - h[0] * h[5]) / det,
            (h[3] * h[7] - h[4] * h[6]) / det,
            (h[1] * h[6] - h[0] * h[7]) / det,
            (h[0] * h[4] - h[1] * h[3]) / det,
        )
        val w = inv[6] * x + inv[7] * y + inv[8]
        require(w.isFinite() && abs(w) > 1e-12) { "Metric point maps to image infinity" }
        return ((inv[0] * x + inv[1] * y + inv[2]) / w) to ((inv[3] * x + inv[4] * y + inv[5]) / w)
    }

    private fun clipSegment(
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

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex
        val low = position.toInt()
        val high = min(sorted.lastIndex, low + 1)
        if (low == high) return sorted[low]
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }

    private fun median(values: List<Double>): Double = percentile(values.sorted(), 0.5)
}

object SpeedGateEstimator {
    private data class Crossing(val timestampMs: Double, val direction: Double, val bracketMs: Double)

    fun estimate(track: Track, gate: SpeedGate): SpeedEstimate? {
        val distance = gate.separationMeters ?: return null
        val observations = track.observations.sortedBy { it.timestampMs }
        if (track.state != TrackState.CONFIRMED || observations.size < 2) return null

        fun coordinate(o: TrackObservation): Double? {
            val g = o.groundPoint ?: return null
            return g.xMeters * gate.axisX + g.yMeters * gate.axisY
        }

        val first = crossings(observations, ::coordinate, gate.line1.coordinate).firstOrNull() ?: return null
        val second = crossings(observations, ::coordinate, gate.line2.coordinate)
            .firstOrNull { it.timestampMs > first.timestampMs && it.direction * first.direction > 0.0 }
            ?: return null
        val dtSeconds = (second.timestampMs - first.timestampMs) / 1000.0
        if (!dtSeconds.isFinite() || dtSeconds <= 0.05) return null
        val speedMps = distance / dtSeconds
        if (!speedMps.isFinite() || speedMps <= 0.0) return null

        val timeUncertaintySeconds = 0.5 * (first.bracketMs + second.bracketMs) / 1000.0
        val errorMps = speedMps * (timeUncertaintySeconds / dtSeconds)
        return SpeedEstimate(
            metersPerSecond = speedMps,
            kilometersPerHour = speedMps * 3.6,
            confidence = 0.0f,
            sampleCount = observations.size,
            durationMs = (second.timestampMs - first.timestampMs).toLong(),
            velocityXMps = gate.axisX * speedMps,
            velocityYMps = gate.axisY * speedMps,
            directionDegrees = Math.toDegrees(atan2(gate.axisY, gate.axisX)),
            errorKmh = (errorMps * 3.6).coerceAtLeast(0.0),
        )
    }

    private fun crossings(
        observations: List<TrackObservation>,
        coordinate: (TrackObservation) -> Double?,
        line: Double,
    ): List<Crossing> {
        val result = ArrayList<Crossing>()
        for (i in 1 until observations.size) {
            val a = observations[i - 1]
            val b = observations[i]
            val ca = coordinate(a) ?: continue
            val cb = coordinate(b) ?: continue
            val dt = (b.timestampMs - a.timestampMs).toDouble()
            if (dt <= 0.0 || dt > 600.0) continue
            val da = ca - line
            val db = cb - line
            if (da == 0.0 && db == 0.0) continue
            if (!((da <= 0.0 && db >= 0.0) || (da >= 0.0 && db <= 0.0))) continue
            val denom = db - da
            if (abs(denom) < 1e-9) continue
            val ratio = (-da / denom).coerceIn(0.0, 1.0)
            result += Crossing(a.timestampMs + dt * ratio, cb - ca, dt)
        }
        return result
    }
}
