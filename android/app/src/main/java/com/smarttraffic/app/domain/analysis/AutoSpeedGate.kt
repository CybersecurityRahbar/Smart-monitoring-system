package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class SpeedGateLine(
    val startPixelX: Double,
    val startPixelY: Double,
    val endPixelX: Double,
    val endPixelY: Double,
    /** Longitudinal coordinate along the inferred flow axis. */
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
 * Vehicle motion is converted into per-track velocity vectors. A 2D principal-component axis
 * is then extracted from those vectors, which preserves the road direction even when traffic
 * contains vehicles moving in opposite directions. Two cross-flow lines are placed at robust
 * scene quantiles along that fixed axis and are never re-scaled by the current active tracks.
 * Metric separation is published only for a valid image-to-ground calibration.
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
        val velocities = collectVelocities(tracks, calibration)
        if (velocities.size < 4) return null

        val axis = principalAxis(velocities) ?: return null
        val ux = axis.first
        val uy = axis.second
        val scalar = samples.map { it.x * ux + it.y * uy }.sorted()
        val line1Coordinate = percentile(scalar, 0.35)
        val line2Coordinate = percentile(scalar, 0.65)
        val separation = abs(line2Coordinate - line1Coordinate)
        if (!separation.isFinite() || separation <= 1e-6) return null

        return if (calibration == null) {
            val centerX = median(samples.map { it.x })
            val centerY = median(samples.map { it.y })
            val centerProjection = centerX * ux + centerY * uy
            val p1 = pointAtProjection(centerProjection, centerX, centerY, ux, uy, line1Coordinate)
            val p2 = pointAtProjection(centerProjection, centerX, centerY, ux, uy, line2Coordinate)
            SpeedGate(
                line1 = imageLine(p1.first, p1.second, ux, uy, imageWidth.toDouble(), imageHeight.toDouble(), line1Coordinate),
                line2 = imageLine(p2.first, p2.second, ux, uy, imageWidth.toDouble(), imageHeight.toDouble(), line2Coordinate),
                separationMeters = null,
                axisX = ux,
                axisY = uy,
                calibrated = false,
            )
        } else {
            val projector = HomographyProjector(calibration.homography)
            val worldCorners = listOf(
                0.0 to 0.0,
                imageWidth.toDouble() to 0.0,
                imageWidth.toDouble() to imageHeight.toDouble(),
                0.0 to imageHeight.toDouble(),
            ).mapNotNull { (x, y) -> runCatching { projector.project(x, y) }.getOrNull() }
            if (worldCorners.size < 4) return null
            val nx = -uy
            val ny = ux
            val transverse = worldCorners.map { p -> p.xMeters * nx + p.yMeters * ny }.sorted()
            if (transverse.size < 4) return null
            val tLow = percentile(transverse, 0.05)
            val tHigh = percentile(transverse, 0.95)
            val margin = max(0.25, (tHigh - tLow) * 0.05)
            val transverseLow = tLow - margin
            val transverseHigh = tHigh + margin
            if (!transverseLow.isFinite() || !transverseHigh.isFinite() || transverseHigh <= transverseLow) return null

            fun metricLine(coordinate: Double): SpeedGateLine? {
                val pA = worldFromAxes(coordinate, transverseLow, ux, uy)
                val pB = worldFromAxes(coordinate, transverseHigh, ux, uy)
                val imageA = runCatching { inverseProject(calibration.homography, pA.first, pA.second) }.getOrNull() ?: return null
                val imageB = runCatching { inverseProject(calibration.homography, pB.first, pB.second) }.getOrNull() ?: return null
                val clipped = clipSegment(imageA.first, imageA.second, imageB.first, imageB.second, imageWidth.toDouble(), imageHeight.toDouble()) ?: return null
                return SpeedGateLine(clipped.first.first, clipped.first.second, clipped.second.first, clipped.second.second, coordinate)
            }

            val line1 = metricLine(line1Coordinate) ?: return null
            val line2 = metricLine(line2Coordinate) ?: return null
            SpeedGate(line1, line2, separation, ux, uy, true)
        }
    }

    private data class Sample(val x: Double, val y: Double, val t: Long)

    private fun pointFor(observation: TrackObservation, calibration: CalibrationProfile?): Pair<Double, Double>? {
        if (calibration != null) {
            val ground = observation.groundPoint ?: return null
            return ground.xMeters to ground.yMeters
        }
        val d = observation.detection
        return ((d.left + d.right) * 0.5).toDouble() to d.bottom.toDouble()
    }

    private fun collectSamples(tracks: List<Track>, calibration: CalibrationProfile?): List<Sample> =
        tracks.flatMap { it.observations.takeLast(40) }.mapNotNull { observation ->
            pointFor(observation, calibration)?.let { Sample(it.first, it.second, observation.timestampMs) }
        }.filter { it.x.isFinite() && it.y.isFinite() && it.t >= 0L }

    private fun collectVelocities(tracks: List<Track>, calibration: CalibrationProfile?): List<Pair<Double, Double>> =
        tracks.flatMap { track ->
            track.observations.takeLast(40).zipWithNext().mapNotNull { (a, b) ->
                val dt = (b.timestampMs - a.timestampMs) / 1000.0
                if (!dt.isFinite() || dt <= 0.0 || dt > 1.0) return@mapNotNull null
                val p1 = pointFor(a, calibration) ?: return@mapNotNull null
                val p2 = pointFor(b, calibration) ?: return@mapNotNull null
                val vx = (p2.first - p1.first) / dt
                val vy = (p2.second - p1.second) / dt
                if (!vx.isFinite() || !vy.isFinite()) return@mapNotNull null
                if (hypot(vx, vy) < 1e-3) return@mapNotNull null
                vx to vy
            }
        }

    /** PCA of 2D velocity vectors; eigenvector sign is arbitrary because a road axis is undirected. */
    private fun principalAxis(vectors: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (vectors.size < 2) return null
        val meanX = vectors.map { it.first }.average()
        val meanY = vectors.map { it.second }.average()
        var cxx = 0.0
        var cxy = 0.0
        var cyy = 0.0
        vectors.forEach { (x, y) ->
            val dx = x - meanX
            val dy = y - meanY
            cxx += dx * dx
            cxy += dx * dy
            cyy += dy * dy
        }
        val trace = cxx + cyy
        val det = cxx * cyy - cxy * cxy
        val discriminant = max(0.0, trace * trace - 4.0 * det)
        val lambda = (trace + kotlin.math.sqrt(discriminant)) * 0.5
        var ax = cxy
        var ay = lambda - cxx
        if (hypot(ax, ay) < 1e-9) {
            ax = lambda - cyy
            ay = cxy
        }
        val norm = hypot(ax, ay)
        if (!norm.isFinite() || norm < 1e-9) return null
        return ax / norm to ay / norm
    }

    private fun pointAtProjection(
        centerProjection: Double,
        centerX: Double,
        centerY: Double,
        ux: Double,
        uy: Double,
        targetProjection: Double,
    ): Pair<Double, Double> {
        val delta = targetProjection - centerProjection
        return centerX + ux * delta to centerY + uy * delta
    }

    private fun imageLine(
        cx: Double,
        cy: Double,
        ux: Double,
        uy: Double,
        width: Double,
        height: Double,
        coordinate: Double,
    ): SpeedGateLine {
        val nx = -uy
        val ny = ux
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
        val a = candidates.getOrNull(0) ?: (0.0 to 0.0)
        val b = candidates.getOrNull(1) ?: (width to height)
        return SpeedGateLine(a.first, a.second, b.first, b.second, coordinate)
    }

    private fun worldFromAxes(longitudinal: Double, transverse: Double, ux: Double, uy: Double): Pair<Double, Double> {
        val nx = -uy
        val ny = ux
        return longitudinal * ux + transverse * nx to longitudinal * uy + transverse * ny
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
            directionDegrees = Math.toDegrees(kotlin.math.atan2(gate.axisY, gate.axisX)),
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
            result += Crossing(
                timestampMs = a.timestampMs + dt * ratio,
                direction = cb - ca,
                bracketMs = dt,
            )
        }
        return result
    }
}
