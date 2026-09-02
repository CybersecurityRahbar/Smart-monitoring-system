package com.smarttraffic.app.data.tracking

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.MultiObjectTracker
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.max
import kotlin.math.min

/**
 * ByteTrack-inspired Android tracker with production-oriented ingredients:
 * Kalman motion prediction, global linear assignment, two-stage high/low score
 * association, class-aware gating, confirmation and explicit lost-track handling.
 *
 * It is intentionally named ByteTrack (not claimed as the official reference
 * implementation); exact benchmark equivalence must be established against MOT data.
 */
class ByteTrack(
    private val highThreshold: Float = 0.50f,
    private val lowThreshold: Float = 0.10f,
    private val matchIouThreshold: Float = 0.30f,
    private val lowMatchIouThreshold: Float = 0.20f,
    private val maxLostFrames: Int = 30,
) : MultiObjectTracker {
    private val tracks = LinkedHashMap<Long, InternalTrack>()
    private var nextId = 1L

    init {
        require(lowThreshold >= 0f && highThreshold <= 1f && lowThreshold < highThreshold)
        require(matchIouThreshold in 0f..1f)
        require(lowMatchIouThreshold in 0f..1f)
        require(maxLostFrames >= 1)
    }

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

    override fun update(
        detections: List<Detection>,
        frameIndex: Long,
        timestampMs: Long,
    ): List<Track> {
        val high = detections.withIndex()
            .filter { it.value.confidence >= highThreshold }
            .sortedByDescending { it.value.confidence }
        val low = detections.withIndex()
            .filter { it.value.confidence in lowThreshold..<highThreshold }
            .sortedByDescending { it.value.confidence }

        tracks.values.forEach { it.predictTo(frameIndex, timestampMs) }
        val matchedDetectionIndices = HashSet<Int>()

        associate(tracks.values.toList(), high, matchIouThreshold).forEach { match ->
            match.track.update(match.detection.value, frameIndex, timestampMs)
            matchedDetectionIndices += match.detection.index
        }

        // Second association: unmatched existing tracks with lower-confidence detections.
        associate(
            tracks.values.filter { !it.matched && it.hits > 0 },
            low,
            lowMatchIouThreshold,
        ).forEach { match ->
            match.track.update(match.detection.value, frameIndex, timestampMs)
            matchedDetectionIndices += match.detection.index
        }

        tracks.values.forEach { if (!it.matched) it.markLost() }

        // New identities are created only from unmatched high-confidence observations.
        high.forEach { indexed ->
            if (indexed.index in matchedDetectionIndices) return@forEach
            val track = InternalTrack(nextId++, indexed.value, frameIndex, timestampMs)
            track.hits = 1
            track.matched = true
            tracks[track.id] = track
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next().value
            // Single-frame false positives are discarded immediately. Confirmed tracks
            // may survive temporary detector misses/occlusion up to maxLostFrames.
            if ((track.hits == 1 && track.lostFrames > 0) || track.lostFrames > maxLostFrames) {
                iterator.remove()
            } else {
                track.matched = false
            }
        }

        return tracks.values
            .filter { it.hits >= 2 && it.lostFrames == 0 && it.lastFrameIndex == frameIndex }
            .map { it.toDomainTrack(frameIndex, timestampMs) }
    }

    private fun associate(
        trackList: List<InternalTrack>,
        detections: List<IndexedValue<Detection>>,
        minimumIou: Float,
    ): List<Match> {
        if (trackList.isEmpty() || detections.isEmpty()) return emptyList()

        val invalidCost = 1_000_000.0
        val costs = Array(trackList.size) { row ->
            DoubleArray(detections.size) { column ->
                val track = trackList[row]
                val detection = detections[column].value
                if (track.lastDetection.classId != detection.classId) {
                    invalidCost
                } else {
                    val associationIou = associationIou(track, detection, minimumIou)
                    if (associationIou < minimumIou) invalidCost else 1.0 - associationIou
                }
            }
        }

        return LinearAssignment.solve(costs).mapNotNull { (row, column) ->
            if (row !in trackList.indices || column !in detections.indices) return@mapNotNull null
            val track = trackList[row]
            val detection = detections[column]
            if (track.lastDetection.classId != detection.value.classId) return@mapNotNull null
            val score = associationIou(track, detection.value, minimumIou)
            if (score < minimumIou) return@mapNotNull null
            Match(track, detection, score)
        }
    }

    /**
     * Prediction-first association gate. A valid Kalman prediction is preferred because
     * it preserves motion-based identity through close interactions. When prediction
     * falls below the configured gate (for example while the filter is still converging
     * or after a short frame gap), the latest measured box is used as a bounded fallback.
     */
    private fun associationIou(
        track: InternalTrack,
        detection: Detection,
        minimumIou: Float,
    ): Float {
        val predicted = iou(track.predicted, detection)
        if (predicted >= minimumIou) return predicted
        return iou(ByteTrackBox(track.lastDetection), detection)
    }

    private data class Match(
        val track: InternalTrack,
        val detection: IndexedValue<Detection>,
        val iou: Float,
    )

    private class InternalTrack(
        val id: Long,
        initial: Detection,
        initialFrameIndex: Long,
        initialTimestampMs: Long,
    ) {
        var lastDetection = initial
        var predicted = ByteTrackBox(initial)
        private val filter = KalmanBoxPredictor(predicted)
        var hits = 0
        var lostFrames = 0
        var matched = false
        var everOccluded = false
        var confidenceEma = initial.confidence
        var lastFrameIndex = initialFrameIndex
        var lastTimestampMs = initialTimestampMs

        fun predictTo(frameIndex: Long, timestampMs: Long) {
            matched = false
            val dtMs = timestampMs - lastTimestampMs
            predicted = if (dtMs > 0L) {
                filter.predict(dtMs / 1000.0)
            } else {
                filter.box()
            }
        }

        fun update(detection: Detection, frameIndex: Long, timestampMs: Long) {
            if (lostFrames > 0) everOccluded = true
            val current = ByteTrackBox(detection)
            filter.update(current)
            lastDetection = detection
            predicted = filter.box()
            hits += 1
            lostFrames = 0
            matched = true
            confidenceEma = 0.80f * confidenceEma + 0.20f * detection.confidence
            lastFrameIndex = frameIndex
            lastTimestampMs = timestampMs
        }

        fun markLost() {
            lostFrames += 1
            everOccluded = true
        }

        fun toDomainTrack(frameIndex: Long, timestampMs: Long): Track = Track(
            id = id,
            className = lastDetection.className,
            observations = listOf(TrackObservation(frameIndex, timestampMs, lastDetection)),
            trackConfidence = confidenceEma.coerceIn(0f, 1f),
            wasOccluded = everOccluded,
        )
    }
}

private fun iou(a: ByteTrackBox, b: Detection): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val areaA = a.width * a.height
    val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
    val union = areaA + areaB - intersection
    return if (union <= 0f || !union.isFinite()) 0f else intersection / union
}
