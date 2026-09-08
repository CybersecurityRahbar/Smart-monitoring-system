package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ResolvedTrackState(
    val track: Track,
    val detection: Detection,
    val observationTimestampMs: Long,
    val predicted: Boolean,
)

object TrackRenderResolver {
    fun resolve(track: Track, targetTimestampMs: Long, maxExtrapolationMs: Long = 300L): ResolvedTrackState? {
        val observations = track.observations.sortedBy { it.timestampMs }
        if (observations.isEmpty()) return null
        val first = observations.first()
        val last = observations.last()
        if (targetTimestampMs < first.timestampMs) return null

        if (targetTimestampMs >= last.timestampMs) {
            val gap = targetTimestampMs - last.timestampMs
            if (gap > maxExtrapolationMs) return null
            if (gap == 0L || observations.size < 2) {
                return ResolvedTrackState(track, last.detection, last.timestampMs, gap > 0L)
            }
            val previous = observations[observations.lastIndex - 1]
            val dt = (last.timestampMs - previous.timestampMs).coerceAtLeast(1L).toDouble()
            val horizon = gap.coerceAtMost(maxExtrapolationMs).toDouble()
            val predicted = translateDetection(
                last.detection,
                (last.detection.left - previous.detection.left) * horizon / dt,
                (last.detection.top - previous.detection.top) * horizon / dt,
                (last.detection.right - previous.detection.right) * horizon / dt,
                (last.detection.bottom - previous.detection.bottom) * horizon / dt,
            )
            return ResolvedTrackState(track, predicted, targetTimestampMs, true)
        }

        val upper = observations.indexOfFirst { it.timestampMs >= targetTimestampMs }
        if (upper < 0) return null
        if (upper == 0) return ResolvedTrackState(track, first.detection, first.timestampMs, false)
        val lower = upper - 1
        val a = observations[lower]
        val b = observations[upper]
        val dt = (b.timestampMs - a.timestampMs).coerceAtLeast(1L).toDouble()
        val t = ((targetTimestampMs - a.timestampMs).toDouble() / dt).coerceIn(0.0, 1.0)
        val before = observations.getOrNull(lower - 1) ?: a
        val after = observations.getOrNull(upper + 1) ?: b
        return ResolvedTrackState(
            track = track,
            detection = cubicDetection(before.detection, a.detection, b.detection, after.detection, t),
            observationTimestampMs = targetTimestampMs,
            predicted = false,
        )
    }

    fun trail(track: Track, targetTimestampMs: Long, windowMs: Long = 1500L): List<ResolvedTrackState> {
        val start = (targetTimestampMs - windowMs).coerceAtLeast(Long.MIN_VALUE + 1L)
        val samples = track.observations
            .asSequence()
            .filter { it.timestampMs in start..targetTimestampMs }
            .sortedBy { it.timestampMs }
            .map { ResolvedTrackState(track, it.detection, it.timestampMs, false) }
            .toMutableList()
        resolve(track, targetTimestampMs)?.let { current ->
            if (samples.isEmpty() || samples.last().observationTimestampMs != targetTimestampMs) samples += current
        }
        return samples
    }

    private fun cubicDetection(before: Detection, a: Detection, b: Detection, after: Detection, t: Double): Detection {
        fun value(p0: Double, p1: Double, p2: Double, p3: Double): Double {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5 * (
                2.0 * p1 +
                    (-p0 + p2) * t +
                    (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                    (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
            )
        }
        fun bounded(v: Double, x: Double, y: Double, padding: Double): Float =
            v.coerceIn(min(x, y) - padding, max(x, y) + padding).toFloat()

        val paddingX = max(2.0, abs(b.right - a.right) * 0.10 + abs(b.left - a.left) * 0.10)
        val paddingY = max(2.0, abs(b.bottom - a.bottom) * 0.10 + abs(b.top - a.top) * 0.10)
        return Detection(
            classId = b.classId,
            className = b.className,
            confidence = max(a.confidence, b.confidence),
            left = bounded(value(before.left.toDouble(), a.left.toDouble(), b.left.toDouble(), after.left.toDouble()), a.left.toDouble(), b.left.toDouble(), paddingX),
            top = bounded(value(before.top.toDouble(), a.top.toDouble(), b.top.toDouble(), after.top.toDouble()), a.top.toDouble(), b.top.toDouble(), paddingY),
            right = bounded(value(before.right.toDouble(), a.right.toDouble(), b.right.toDouble(), after.right.toDouble()), a.right.toDouble(), b.right.toDouble(), paddingX),
            bottom = bounded(value(before.bottom.toDouble(), a.bottom.toDouble(), b.bottom.toDouble(), after.bottom.toDouble()), a.bottom.toDouble(), b.bottom.toDouble(), paddingY),
            frameIndex = b.frameIndex,
            timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * t).toLong(),
            appearanceSignature = b.appearanceSignature,
        )
    }

    private fun translateDetection(detection: Detection, leftDelta: Double, topDelta: Double, rightDelta: Double, bottomDelta: Double): Detection =
        detection.copy(
            left = detection.left + leftDelta.toFloat(),
            top = detection.top + topDelta.toFloat(),
            right = detection.right + rightDelta.toFloat(),
            bottom = detection.bottom + bottomDelta.toFloat(),
        )
}
