package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    val geometryConfidence: Float = 0.0f,
)

/** Scene-adaptive virtual timing gate. Metric scale is never invented for uncalibrated video. */
object AutoSpeedGateBuilder {
    fun build(tracks: List<Track>, imageWidth: Int, imageHeight: Int, calibration: CalibrationProfile? = null): SpeedGate? {
        if (imageWidth <= 1 || imageHeight <= 1) return null
        val samples = collectSamples(tracks, calibration)
        if (samples.size < 6) return null
        val axisResult = principalAxis(samples, tracks, calibration)
        val axis = axisResult.axis ?: return null
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
                geometryConfidence = axisResult.confidence,
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
            SpeedGate(line1, line2, separation, axis.x, axis.y, true, axisResult.confidence)
        }
    }

    private data class Sample(val x: Double, val y: Double, val t: Long)
    private data class Axis(val x: Double, val y: Double)
    private data class AxisResult(val axis: Axis?, val confidence: Float)

    private fun collectSamples(tracks: List<Track>, calibration: CalibrationProfile?): List<Sample> =
        tracks.asSequence()
            .filter { it.className.equals("car", ignoreCase = true) || it.hits >= 2 }
            .flatMap { track ->
                track.observations.takeLast(40).asSequence().mapNotNull { observation ->
                    if (calibration == null) {
                        val d = observation.detection
                        Sample((d.left + d.right) * 0.5, d.bottom.toDouble(), observation.timestampMs)
                    } else {
                        observation.groundPoint?.let { Sample(it.xMeters, it.yMeters, observation.timestampMs) }
                    }
                }
            }
            .filter { it.x.isFinite() && it.y.isFinite() && it.t >= 0L }
            .toList()

    private fun principalAxis(samples: List<Sample>, tracks: List<Track>, calibration: CalibrationProfile?): AxisResult {
        val motionVectors = ArrayList<Pair<Double, Double>>()
        for (track in tracks) {
            val observations = track.observations.takeLast(40)
            for (i in 1 until observations.size) {
                val a = observations[i - 1]
                val b = observations[i]
                val dt = (b.timestampMs - a.timestampMs) / 1000.0
                if (!dt.isFinite() || dt <= 0.0 || dt > 0.6) continue
                val ax: Double
                val ay: Double
                val bx: Double
                val by: Double
                if (calibration != null) {
                    val pa = a.groundPoint ?: continue
                    val pb = b.groundPoint ?: continue
                    ax = pa.xMeters; ay = pa.yMeters; bx = pb.xMeters; by = pb.yMeters
                } else {
                    ax = ((a.detection.left + a.detection.right) * 0.5).toDouble(); ay = a.detection.bottom.toDouble()
                    bx = ((b.detection.left + b.detection.right) * 0.5).toDouble(); by = b.detection.bottom.toDouble()
                }
                val dx = (bx - ax) / dt
                val dy = (by - ay) / dt
                val magnitude = hypot(dx, dy)
                if (!magnitude.isFinite() || magnitude < 1e-5) continue
                motionVectors += (dx / magnitude) to (dy / magnitude)
            }
        }
        if (motionVectors.size >= 4) {
            var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
            motionVectors.forEach { (x, y) -> cxx += x * x; cyy += y * y; cxy += x * y }
            val theta = 0.5 * atan2(2.0 * cxy, cxx - cyy)
            var ux = cos(theta); var uy = sin(theta)
            val norm = hypot(ux, uy)
            if (norm.isFinite() && norm > 1e-9) {
                ux /= norm; uy /= norm
                val aligned = motionVectors.sumOf { (x, y) -> abs(x * ux + y * uy) } / motionVectors.size
                return AxisResult(Axis(ux, uy), aligned.toFloat().coerceIn(0.0f, 1.0f))
            }
        }
        if (samples.size < 2) return AxisResult(null, 0.0f)
        val meanX = samples.map { it.x }.average(); val meanY = samples.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        samples.forEach { sample -> val dx = sample.x - meanX; val dy = sample.y - meanY; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        if (!sxx.isFinite() || !syy.isFinite() || !sxy.isFinite()) return AxisResult(null, 0.0f)
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var ux = cos(theta); var uy = sin(theta)
        val norm = hypot(ux, uy)
        if (!norm.isFinite() || norm < 1e-9) return AxisResult(null, 0.0f)
        ux /= norm; uy /= norm
        val trace = sxx + syy; val determinant = sxx * syy - sxy * sxy
        val discriminant = sqrt(max(0.0, trace * trace - 4.0 * determinant))
        val largest = 0.5 * (trace + discriminant); val smallest = 0.5 * (trace - discriminant)
        val confidence = if (largest > 1e-9) ((largest - smallest) / largest).coerceIn(0.0, 1.0) else 0.0
        return AxisResult(Axis(ux, uy), confidence.toFloat())
    }

    private fun pointAtProjection(cx: Double, cy: Double, centerProjection: Double, axisX: Double, axisY: Double, targetProjection: Double): Pair<Double, Double> {
        val delta = targetProjection - centerProjection
        return cx + axisX * delta to cy + axisY * delta
    }

    private fun imageLine(cx: Double, cy: Double, axisX: Double, axisY: Double, width: Double, height: Double, coordinate: Double): SpeedGateLine {
        val nx = -axisY; val ny = axisX; val candidates = ArrayList<Pair<Double, Double>>(4)
        fun add(t: Double) { val x = cx + nx * t; val y = cy + ny * t; if (x in 0.0..width && y in 0.0..height) candidates += x to y }
        if (abs(nx) > 1e-9) { add(-cx / nx); add((width - cx) / nx) }
        if (abs(ny) > 1e-9) { add(-cy / ny); add((height - cy) / ny) }
        if (candidates.size >= 2) return SpeedGateLine(candidates[0].first, candidates[0].second, candidates[1].first, candidates[1].second, coordinate)
        return SpeedGateLine(0.0, 0.0, width, height, coordinate)
    }

    private fun worldFromAxes(longitudinal: Double, transverse: Double, axisX: Double, axisY: Double): Pair<Double, Double> {
        val nx = -axisY; val ny = axisX
        return longitudinal * axisX + transverse * nx to longitudinal * axisY + transverse * ny
    }

    private fun inverseProject(h: List<Double>, x: Double, y: Double): Pair<Double, Double> {
        require(h.size == 9)
        val det = h[0] * (h[4] * h[8] - h[5] * h[7]) - h[1] * (h[3] * h[8] - h[5] * h[6]) + h[2] * (h[3] * h[7] - h[4] * h[6])
        require(abs(det) > 1e-12 && det.isFinite()) { "Homography is singular" }
        val inv = doubleArrayOf(
            (h[4] * h[8] - h[5] * h[7]) / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
            (h[5] * h[6] - h[3] * h[8]) / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
            (h[3] * h[7] - h[4] * h[6]) / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det,
        )
        val w = inv[6] * x + inv[7] * y + inv[8]
        require(w.isFinite() && abs(w) > 1e-12) { "Metric point maps to image infinity" }
        return ((inv[0] * x + inv[1] * y + inv[2]) / w) to ((inv[3] * x + inv[4] * y + inv[5]) / w)
    }

    private fun clipSegment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double, height: Double): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        var t0 = 0.0; var t1 = 1.0; val dx = x1 - x0; val dy = y1 - y0
        fun clip(p: Double, q: Double): Boolean {
            if (abs(p) < 1e-12) return q >= 0.0
            val r = q / p
            if (p < 0.0) { if (r > t1) return false; if (r > t0) t0 = r }
            else { if (r < t0) return false; if (r < t1) t1 = r }
            return true
        }
        if (!clip(-dx, x0)) return null; if (!clip(dx, width - x0)) return null
        if (!clip(-dy, y0)) return null; if (!clip(dy, height - y0)) return null
        return (x0 + t0 * dx to y0 + t0 * dy) to (x0 + t1 * dx to y0 + t1 * dy)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex; val low = position.toInt(); val high = min(sorted.lastIndex, low + 1)
        if (low == high) return sorted[low]
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}

object SpeedGateEstimator {
    private data class Crossing(val line: Int, val timestampMs: Double, val direction: Double, val bracketMs: Double)

    fun estimate(track: Track, gate: SpeedGate): SpeedEstimate? {
        val distance = gate.separationMeters ?: return null
        if (!distance.isFinite() || distance <= 0.0) return null
        val observations = track.observations.sortedBy { it.timestampMs }.filter { it.groundPoint != null }
        if (track.state != TrackState.CONFIRMED || observations.size < 3) return null
        fun coordinate(o: TrackObservation): Double? = o.groundPoint?.let { it.xMeters * gate.axisX + it.yMeters * gate.axisY }

        val events = buildList {
            crossings(observations, ::coordinate, gate.line1.coordinate).forEach { add(it.copy(line = 1)) }
            crossings(observations, ::coordinate, gate.line2.coordinate).forEach { add(it.copy(line = 2)) }
        }.sortedBy { it.timestampMs }
        val pair = firstOppositeLinePair(events) ?: return null
        val dtSeconds = (pair.second.timestampMs - pair.first.timestampMs) / 1000.0
        if (!dtSeconds.isFinite() || dtSeconds <= 0.05) return null
        val crossingSpeedMps = distance / dtSeconds
        if (!crossingSpeedMps.isFinite() || crossingSpeedMps <= 0.0) return null

        val start = pair.first.timestampMs - pair.first.bracketMs * 0.5
        val end = pair.second.timestampMs + pair.second.bracketMs * 0.5
        val trajectory = observations.mapNotNull { o ->
            val t = o.timestampMs.toDouble()
            if (t !in start..end) return@mapNotNull null
            coordinate(o)?.let { t to it }
        }
        val robustSlopeMps = robustMedianSlope(trajectory)
        val disagreement = if (robustSlopeMps != null && robustSlopeMps > 0.0) abs(robustSlopeMps - crossingSpeedMps) / max(crossingSpeedMps, 1e-9) else 1.0
        val speedMps = when {
            robustSlopeMps == null -> crossingSpeedMps
            disagreement <= 0.25 -> 0.6 * crossingSpeedMps + 0.4 * robustSlopeMps
            else -> crossingSpeedMps
        }
        if (!speedMps.isFinite() || speedMps <= 0.0) return null

        val timingUncertaintySeconds = 0.5 * (pair.first.bracketMs + pair.second.bracketMs) / 1000.0
        val timingErrorMps = speedMps * (timingUncertaintySeconds / dtSeconds)
        val disagreementErrorMps = if (robustSlopeMps != null) abs(robustSlopeMps - crossingSpeedMps) else 0.0
        val errorMps = max(timingErrorMps, disagreementErrorMps)
        val durationMs = pair.second.timestampMs - pair.first.timestampMs
        val timingConfidence = 1.0 - (timingUncertaintySeconds / dtSeconds).coerceIn(0.0, 1.0)
        val agreementConfidence = exp(-disagreement.coerceIn(0.0, 2.0)).coerceIn(0.0, 1.0)
        val observationConfidence = min(1.0, trajectory.size / 12.0)
        val geometryConfidence = gate.geometryConfidence.coerceIn(0.0f, 1.0f).toDouble()
        val confidence = (timingConfidence * agreementConfidence * observationConfidence * geometryConfidence).toFloat().coerceIn(0.0f, 1.0f)
        val directionSign = if (pair.first.line == 1) 1.0 else -1.0

        return SpeedEstimate(
            metersPerSecond = speedMps,
            kilometersPerHour = speedMps * 3.6,
            confidence = confidence,
            sampleCount = trajectory.size,
            durationMs = durationMs.toLong(),
            velocityXMps = gate.axisX * speedMps * directionSign,
            velocityYMps = gate.axisY * speedMps * directionSign,
            directionDegrees = Math.toDegrees(atan2(gate.axisY * directionSign, gate.axisX * directionSign)),
            positionResidualMeters = robustSlopeMps?.let { abs(it - crossingSpeedMps) * (durationMs / 1000.0) },
            errorKmh = (errorMps * 3.6).coerceAtLeast(0.0),
        )
    }

    private fun firstOppositeLinePair(events: List<Crossing>): Pair<Crossing, Crossing>? {
        if (events.size < 2) return null
        for (i in events.indices) {
            val first = events[i]
            for (j in i + 1 until min(events.size, i + 6)) {
                val second = events[j]
                if (second.line == first.line) continue
                if (second.timestampMs <= first.timestampMs + 50.0) continue
                if (first.direction == 0.0 || second.direction == 0.0) continue
                if (first.direction * second.direction <= 0.0) continue
                return first to second
            }
        }
        return null
    }

    private fun crossings(observations: List<TrackObservation>, coordinate: (TrackObservation) -> Double?, line: Double): List<Crossing> {
        val result = ArrayList<Crossing>()
        for (i in 1 until observations.size) {
            val a = observations[i - 1]; val b = observations[i]
            val ca = coordinate(a) ?: continue; val cb = coordinate(b) ?: continue
            val dt = (b.timestampMs - a.timestampMs).toDouble()
            if (dt <= 0.0 || dt > 600.0) continue
            val da = ca - line; val db = cb - line
            if (da == 0.0 && db == 0.0) continue
            if (!((da <= 0.0 && db >= 0.0) || (da >= 0.0 && db <= 0.0))) continue
            val denom = db - da
            if (abs(denom) < 1e-9) continue
            val ratio = (-da / denom).coerceIn(0.0, 1.0)
            result += Crossing(0, a.timestampMs + dt * ratio, db - da, dt)
        }
        return result
    }

    private fun robustMedianSlope(trajectory: List<Pair<Double, Double>>): Double? {
        if (trajectory.size < 3) return null
        val points = trajectory.sortedBy { it.first }.takeLast(160)
        val slopes = ArrayList<Double>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dtSeconds = (points[j].first - points[i].first) / 1000.0
                if (!dtSeconds.isFinite() || dtSeconds < 0.08 || dtSeconds > 1.5) continue
                val slope = abs((points[j].second - points[i].second) / dtSeconds)
                if (!slope.isFinite() || slope <= 1e-6 || slope > 100.0) continue
                slopes += slope
            }
        }
        if (slopes.size < 5) return null
        val sorted = slopes.sorted(); val med = percentile(sorted, 0.5)
        val deviations = sorted.map { abs(it - med) }.sorted(); val mad = percentile(deviations, 0.5)
        val cutoff = max(1e-6, 3.0 * max(mad, med * 0.02))
        val inliers = sorted.filter { abs(it - med) <= cutoff }
        return if (inliers.size >= 5) percentile(inliers, 0.5) else med
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = p.coerceIn(0.0, 1.0) * sorted.lastIndex; val low = position.toInt(); val high = min(sorted.lastIndex, low + 1)
        if (low == high) return sorted[low]
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
