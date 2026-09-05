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
 * Scene-adaptive timing-gate builder. It infers the dominant vehicle-flow direction from
 * actual track motion, then freezes two cross-flow lines at robust 35/65 percent positions.
 * No per-frame min/max rescaling is used.
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

        val vx = median(samples.zipWithNext().mapNotNull { (a, b) ->
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0.0 || dt > 1.0) null else (b.x - a.x) / dt
        })
        val vy = median(samples.zipWithNext().mapNotNull { (a, b) ->
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0.0 || dt > 1.0) null else (b.y - a.y) / dt
        })
        val norm = hypot(vx, vy)
        if (!norm.isFinite() || norm < 1e-6) return null
        val ux = vx / norm
        val uy = vy / norm
        val centerX = median(samples.map { it.x })
        val centerY = median(samples.map { it.y })
        val scalar = samples.map { (it.x - centerX) * ux + (it.y - centerY) * uy }.sorted()
        val s1 = percentile(scalar, 0.35)
        val s2 = percentile(scalar, 0.65)
        val rawSeparation = abs(s2 - s1)
        if (!rawSeparation.isFinite() || rawSeparation <= 1e-6) return null

        return if (calibration == null) {
            val p1 = centerX + ux * s1 to centerY + uy * s1
            val p2 = centerX + ux * s2 to centerY + uy * s2
            SpeedGate(
                line1 = imageLine(p1.first, p1.second, ux, uy, imageWidth.toDouble(), imageHeight.toDouble(), s1),
                line2 = imageLine(p2.first, p2.second, ux, uy, imageWidth.toDouble(), imageHeight.toDouble(), s2),
                separationMeters = null,
                axisX = ux,
                axisY = uy,
                calibrated = false,
            )
        } else {
            val p1 = projectWorldToImage(calibration.homography, centerX + ux * s1, centerY + uy * s1)
            val p2 = projectWorldToImage(calibration.homography, centerX + ux * s2, centerY + uy * s2)
            val t1 = lineExtent(tracks, calibration, ux, uy, centerX + ux * s1, centerY + uy * s1)
            val t2 = lineExtent(tracks, calibration, ux, uy, centerX + ux * s2, centerY + uy * s2)
            val low = min(t1.first, t2.first)
            val high = max(t1.second, t2.second)
            val transverseCenter = (low + high) * 0.5
            val transverseHalf = max(0.5, (high - low) * 0.60)
            val nx = -uy
            val ny = ux
            val a1 = projectWorldToImage(calibration.homography, centerX + ux * s1 + nx * transverseCenter - nx * transverseHalf, centerY + uy * s1 + ny * transverseCenter - ny * transverseHalf)
            val b1 = projectWorldToImage(calibration.homography, centerX + ux * s1 + nx * transverseCenter + nx * transverseHalf, centerY + uy * s1 + ny * transverseCenter + ny * transverseHalf)
            val a2 = projectWorldToImage(calibration.homography, centerX + ux * s2 + nx * transverseCenter - nx * transverseHalf, centerY + uy * s2 + ny * transverseCenter - ny * transverseHalf)
            val b2 = projectWorldToImage(calibration.homography, centerX + ux * s2 + nx * transverseCenter + nx * transverseHalf, centerY + uy * s2 + ny * transverseCenter + ny * transverseHalf)
            SpeedGate(
                line1 = SpeedGateLine(a1.first, a1.second, b1.first, b1.second, s1),
                line2 = SpeedGateLine(a2.first, a2.second, b2.first, b2.second, s2),
                separationMeters = rawSeparation,
                axisX = ux,
                axisY = uy,
                calibrated = true,
            )
        }
    }

    private data class Sample(val x: Double, val y: Double, val t: Long)

    private fun collectSamples(tracks: List<Track>, calibration: CalibrationProfile?): List<Sample> =
        tracks.flatMap { track ->
            track.observations.takeLast(40).mapNotNull { observation ->
                if (calibration == null) {
                    val d = observation.detection
                    Sample((d.left + d.right) * 0.5, d.bottom.toDouble(), observation.timestampMs)
                } else {
                    val g = observation.groundPoint ?: return@mapNotNull null
                    Sample(g.xMeters, g.yMeters, observation.timestampMs)
                }
            }
        }.filter { it.x.isFinite() && it.y.isFinite() && it.t >= 0L }

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
        val first = candidates.getOrNull(0) ?: (0.0 to 0.0)
        val second = candidates.getOrNull(1) ?: (width to height)
        return SpeedGateLine(first.first, first.second, second.first, second.second, coordinate)
    }

    private fun lineExtent(tracks: List<Track>, calibration: CalibrationProfile, ux: Double, uy: Double, cx: Double, cy: Double): Pair<Double, Double> {
        val nx = -uy
        val ny = ux
        val values = tracks.flatMap { it.observations.takeLast(40) }.mapNotNull { o ->
            val g = o.groundPoint ?: return@mapNotNull null
            val value = (g.xMeters - cx) * nx + (g.yMeters - cy) * ny
            value.takeIf { it.isFinite() }
        }.sorted()
        return if (values.isEmpty()) -1.0 to 1.0 else percentile(values, 0.05) to percentile(values, 0.95)
    }

    private fun projectWorldToImage(h: List<Double>, x: Double, y: Double): Pair<Double, Double> {
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
        require(abs(w) > 1e-12 && w.isFinite())
        return ((inv[0] * x + inv[1] * y + inv[2]) / w) to ((inv[3] * x + inv[4] * y + inv[5]) / w)
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
    data class Crossing(val timestampMs: Double, val direction: Double)

    fun estimate(track: Track, gate: SpeedGate): SpeedEstimate? {
        val distance = gate.separationMeters ?: return null
        val observations = track.observations.sortedBy { it.timestampMs }
        if (track.state != TrackState.CONFIRMED || observations.size < 2) return null

        fun coordinate(observation: TrackObservation): Double? {
            val g = observation.groundPoint ?: return null
            return g.xMeters * gate.axisX + g.yMeters * gate.axisY
        }

        val c1 = crossings(observations, coordinate, gate.line1.coordinate)
        val c2 = crossings(observations, coordinate, gate.line2.coordinate)
        val first = c1.firstOrNull() ?: return null
        val second = c2.firstOrNull { it.timestampMs > first.timestampMs && it.direction * first.direction > 0.0 } ?: return null
        val dt = (second.timestampMs - first.timestampMs) / 1000.0
        if (!dt.isFinite() || dt <= 0.05) return null
        val mps = distance / dt
        if (!mps.isFinite() || mps < 0.0) return null
        val quality = (1.0 - min(1.0, (gate.separationMeters / max(distance, 0.25)) * 0.0)).toFloat()
        return SpeedEstimate(mps, mps * 3.6, quality, observations.size, (second.timestampMs - first.timestampMs).toLong(), errorKmh = null)
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
            result += Crossing(a.timestampMs + dt * ratio, (cb - ca).coerceIn(-1e12, 1e12))
        }
        return result
    }
}
