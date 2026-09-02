package com.smarttraffic.app.data.tracking

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.MultiObjectTracker
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ByteTrack-style Android baseline with deterministic two-stage association.
 *
 * The implementation intentionally exposes only confirmed, currently observed tracks
 * to the analysis pipeline. Low-confidence detections are used in the second matching
 * stage so occlusion recovery is not disabled by the report confidence threshold.
 */
class ByteTrack(
    private val highThreshold: Float = 0.50f,
    private val lowThreshold: Float = 0.10f,
    private val matchIouThreshold: Float = 0.30f,
    private val maxLostFrames: Int = 30,
) : MultiObjectTracker {
    private val tracks = LinkedHashMap<Long, InternalTrack>()
    private var nextId = 1L

    init {
        require(lowThreshold >= 0f && highThreshold <= 1f && lowThreshold < highThreshold)
        require(matchIouThreshold in 0f..1f)
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

        tracks.values.forEach { it.prepareForFrame(frameIndex, timestampMs) }
        val matchedDetectionIndices = HashSet<Int>()

        associate(tracks.values.toList(), high).forEach { match ->
            match.track.update(match.detection.value, frameIndex, timestampMs)
            matchedDetectionIndices += match.detection.index
        }

        // ByteTrack's second association recovers tracked objects using lower-score boxes.
        associate(tracks.values.filter { !it.matched && it.hits > 0 }, low).forEach { match ->
            match.track.update(match.detection.value, frameIndex, timestampMs)
            matchedDetectionIndices += match.detection.index
        }

        tracks.values.forEach { if (!it.matched) it.markLost() }

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
            if (track.lostFrames > maxLostFrames) iterator.remove()
            else track.matched = false
        }

        return tracks.values
            .filter { it.hits >= 2 && it.lostFrames == 0 && it.lastFrameIndex == frameIndex }
            .map { it.toDomainTrack(frameIndex, timestampMs) }
    }

    private fun associate(
        trackList: List<InternalTrack>,
        detections: List<IndexedValue<Detection>>,
    ): List<Match> {
        if (trackList.isEmpty() || detections.isEmpty()) return emptyList()
        val candidates = ArrayList<Match>()
        for (track in trackList) {
            for (detection in detections) {
                if (track.lastDetection.classId != detection.value.classId) continue
                val overlap = iou(track.predicted, detection.value)
                if (overlap >= matchIouThreshold) candidates += Match(track, detection, overlap)
            }
        }
        candidates.sortWith(
            compareByDescending<Match> { it.iou }
                .thenByDescending { it.detection.value.confidence }
                .thenBy { it.track.id }
                .thenBy { it.detection.index },
        )

        val usedTracks = HashSet<Long>()
        val usedDetections = HashSet<Int>()
        val matches = ArrayList<Match>()
        for (candidate in candidates) {
            if (!usedTracks.add(candidate.track.id)) continue
            if (!usedDetections.add(candidate.detection.index)) {
                usedTracks.remove(candidate.track.id)
                continue
            }
            matches += candidate
        }
        return matches
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
        var predicted = Box(initial)
        var vx = 0.0f
        var vy = 0.0f
        var vw = 0.0f
        var vh = 0.0f
        var hits = 0
        var lostFrames = 0
        var matched = false
        var everOccluded = false
        var confidenceEma = initial.confidence
        var lastFrameIndex = initialFrameIndex
        var lastTimestampMs = initialTimestampMs

        fun prepareForFrame(frameIndex: Long, timestampMs: Long) {
            matched = false
            val dtMs = timestampMs - lastTimestampMs
            if (dtMs > 0L) {
                val dt = dtMs / 1000.0f
                predicted = predicted.translated(vx * dt, vy * dt, vw * dt, vh * dt)
            }
        }

        fun update(detection: Detection, frameIndex: Long, timestampMs: Long) {
            val previous = Box(lastDetection)
            val current = Box(detection)
            val dtMs = timestampMs - lastTimestampMs
            if (dtMs > 0L) {
                val dt = dtMs / 1000.0f
                val measuredVx = (current.centerX - previous.centerX) / dt
                val measuredVy = (current.centerY - previous.centerY) / dt
                val measuredVw = (current.width - previous.width) / dt
                val measuredVh = (current.height - previous.height) / dt
                vx = 0.70f * measuredVx + 0.30f * vx
                vy = 0.70f * measuredVy + 0.30f * vy
                vw = 0.50f * measuredVw + 0.50f * vw
                vh = 0.50f * measuredVh + 0.50f * vh
            }
            if (lostFrames > 0) everOccluded = true
            lastDetection = detection
            predicted = current
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
            observations = listOf(
                TrackObservation(frameIndex, timestampMs, lastDetection),
            ),
            trackConfidence = confidenceEma.coerceIn(0f, 1f),
            wasOccluded = everOccluded,
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

    fun translated(dx: Float, dy: Float, dw: Float, dh: Float): Box {
        val newWidth = max(1f, width + dw)
        val newHeight = max(1f, height + dh)
        val centerX = centerX + dx
        val centerY = centerY + dy
        return Box(
            centerX - newWidth * 0.5f,
            centerY - newHeight * 0.5f,
            centerX + newWidth * 0.5f,
            centerY + newHeight * 0.5f,
        )
    }
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
    return if (union <= 0f || !union.isFinite()) 0f else intersection / union
}
