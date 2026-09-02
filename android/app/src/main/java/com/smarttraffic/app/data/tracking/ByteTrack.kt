package com.smarttraffic.app.data.tracking

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.MultiObjectTracker
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.max
import kotlin.math.min

/**
 * ByteTrack-style Android baseline with two-stage high/low-confidence association.
 * This is the correctness baseline before the heavier BoT-SORT/Re-ID path.
 */
class ByteTrack(
    private val highThreshold: Float = 0.50f,
    private val lowThreshold: Float = 0.10f,
    private val matchIouThreshold: Float = 0.30f,
    private val maxLostFrames: Int = 30,
) : MultiObjectTracker {
    private val tracks = LinkedHashMap<Long, InternalTrack>()
    private var nextId = 1L

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

    override fun update(
        detections: List<Detection>,
        frameIndex: Long,
        timestampMs: Long,
    ): List<Track> {
        val high = detections.filter { it.confidence >= highThreshold }
            .sortedByDescending { it.confidence }
        val low = detections.filter { it.confidence in lowThreshold..<highThreshold }
            .sortedByDescending { it.confidence }

        tracks.values.forEach { it.predict() }

        associate(tracks.values.filter { !it.matched }, high).forEach { (track, detection) ->
            track.update(detection)
            track.matched = true
        }

        // ByteTrack's second association recovers existing tracks using lower-score detections.
        associate(tracks.values.filter { !it.matched && it.hits > 0 }, low).forEach { (track, detection) ->
            track.update(detection)
            track.matched = true
        }

        tracks.values.forEach { if (!it.matched) it.markLost() }

        // Unmatched high-confidence detections initialize new tracks.
        high.forEach { detection ->
            if (tracks.values.none { it.lastDetection === detection }) {
                val track = InternalTrack(nextId++, detection)
                track.hits = 1
                track.matched = true
                tracks[track.id] = track
            }
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val (_, track) = iterator.next()
            if (track.lostFrames > maxLostFrames) iterator.remove()
            else track.matched = false
        }

        return tracks.values
            .filter { it.hits >= 2 && it.lostFrames == 0 }
            .map { it.toDomainTrack(frameIndex, timestampMs) }
    }

    private fun associate(
        trackList: List<InternalTrack>,
        detections: List<Detection>,
    ): List<Pair<InternalTrack, Detection>> {
        if (trackList.isEmpty() || detections.isEmpty()) return emptyList()

        val candidates = ArrayList<Match>()
        for (track in trackList) {
            for (detection in detections) {
                if (track.lastDetection.classId != detection.classId) continue
                val overlap = iou(track.predicted, detection)
                if (overlap >= matchIouThreshold) candidates += Match(track, detection, overlap)
            }
        }
        candidates.sortWith(
            compareByDescending<Match> { it.iou }
                .thenByDescending { it.detection.confidence },
        )

        val usedTracks = HashSet<Long>()
        val usedDetections = HashSet<Int>()
        val matches = ArrayList<Pair<InternalTrack, Detection>>()
        for (candidate in candidates) {
            if (!usedTracks.add(candidate.track.id)) continue
            if (!usedDetections.add(System.identityHashCode(candidate.detection))) {
                usedTracks.remove(candidate.track.id)
                continue
            }
            matches += candidate.track to candidate.detection
        }
        return matches
    }

    private data class Match(
        val track: InternalTrack,
        val detection: Detection,
        val iou: Float,
    )

    private class InternalTrack(
        val id: Long,
        initial: Detection,
    ) {
        var lastDetection = initial
        var predicted = Box(initial)
        var vx = 0f
        var vy = 0f
        var vw = 0f
        var vh = 0f
        var hits = 0
        var lostFrames = 0
        var matched = false
        var confidenceEma = initial.confidence

        fun predict() {
            predicted = Box(
                left = predicted.left + vx,
                top = predicted.top + vy,
                right = predicted.right + vx + vw,
                bottom = predicted.bottom + vy + vh,
            )
        }

        fun update(detection: Detection) {
            val old = Box(lastDetection)
            val current = Box(detection)
            vx = 0.7f * (current.centerX - old.centerX) + 0.3f * vx
            vy = 0.7f * (current.centerY - old.centerY) + 0.3f * vy
            vw = 0.5f * (current.width - old.width) + 0.5f * vw
            vh = 0.5f * (current.height - old.height) + 0.5f * vh
            lastDetection = detection
            predicted = current
            hits += 1
            lostFrames = 0
            confidenceEma = 0.8f * confidenceEma + 0.2f * detection.confidence
        }

        fun markLost() {
            lostFrames += 1
        }

        fun toDomainTrack(frameIndex: Long, timestampMs: Long): Track = Track(
            id = id,
            className = lastDetection.className,
            observations = listOf(
                TrackObservation(frameIndex, timestampMs, lastDetection),
            ),
            trackConfidence = confidenceEma.coerceIn(0f, 1f),
            wasOccluded = lostFrames > 0,
        )
    }
}

private data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    constructor(detection: Detection) : this(
        detection.left, detection.top, detection.right, detection.bottom,
    )

    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

private fun iou(a: Box, b: Detection): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val areaA = a.width * a.height
    val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
    val union = areaA + areaB - intersection
    return if (union <= 0f) 0f else intersection / union
}
