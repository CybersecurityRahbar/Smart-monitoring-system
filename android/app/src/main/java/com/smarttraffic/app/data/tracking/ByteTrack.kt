package com.smarttraffic.app.data.tracking

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.MultiObjectTracker
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ByteTrack-inspired Android tracker with Kalman prediction, global assignment,
 * two-stage high/low score association, class-aware gating, bounded motion recovery,
 * and an optional deterministic appearance cue.
 *
 * This implementation is not claimed to be reference-equivalent to official ByteTrack.
 * Its production accuracy must be established on annotated traffic/MOT sequences.
 */
class ByteTrack(
    private val highThreshold: Float = 0.50f,
    private val lowThreshold: Float = 0.10f,
    private val matchIouThreshold: Float = 0.30f,
    private val lowMatchIouThreshold: Float = 0.20f,
    private val maxLostFrames: Int = 30,
    private val centerDistanceScale: Double = 0.75,
    private val appearanceWeight: Double = 0.20,
    private val minimumAppearanceSimilarity: Double = 0.20,
) : MultiObjectTracker {
    private val tracks = LinkedHashMap<Long, InternalTrack>()
    private var nextId = 1L

    init {
        require(lowThreshold >= 0f && highThreshold <= 1f && lowThreshold < highThreshold)
        require(matchIouThreshold in 0f..1f)
        require(lowMatchIouThreshold in 0f..1f)
        require(maxLostFrames >= 1)
        require(centerDistanceScale > 0.0 && centerDistanceScale.isFinite())
        require(appearanceWeight in 0.0..1.0 && appearanceWeight.isFinite())
        require(minimumAppearanceSimilarity in 0.0..1.0 && minimumAppearanceSimilarity.isFinite())
    }

    override fun reset() {
        tracks.clear()
        nextId = 1L
    }

    override fun update(detections: List<Detection>, frameIndex: Long, timestampMs: Long): List<Track> {
        val high = detections.withIndex()
            .filter { it.value.confidence >= highThreshold }
            .sortedByDescending { it.value.confidence }
        val low = detections.withIndex()
            .filter { it.value.confidence in lowThreshold..<highThreshold }
            .sortedByDescending { it.value.confidence }

        tracks.values.forEach { it.predictTo(timestampMs) }
        val matchedDetectionIndices = HashSet<Int>()

        associate(tracks.values.toList(), high, matchIouThreshold).forEach { match ->
            match.track.update(match.detection.value, frameIndex, timestampMs)
            matchedDetectionIndices += match.detection.index
        }

        associate(tracks.values.filter { !it.matched && it.hits > 0 }, low, lowMatchIouThreshold).forEach { match ->
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
            if ((track.hits == 1 && track.lostFrames > 0) || track.lostFrames > maxLostFrames) iterator.remove()
            else track.matched = false
        }

        return tracks.values
            .filter { it.hits >= 2 && it.lostFrames == 0 && it.lastFrameIndex == frameIndex }
            .map { it.toDomainTrack() }
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
                    val score = associationScore(track, detection, minimumIou)
                    if (score <= 0.0) invalidCost else 1.0 - score
                }
            }
        }
        return LinearAssignment.solve(costs).mapNotNull { (row, column) ->
            if (row !in trackList.indices || column !in detections.indices) return@mapNotNull null
            val track = trackList[row]
            val detection = detections[column]
            if (track.lastDetection.classId != detection.value.classId) return@mapNotNull null
            val score = associationScore(track, detection.value, minimumIou)
            if (score <= 0.0) return@mapNotNull null
            Match(track, detection, score)
        }
    }

    /** Geometry is mandatory; deterministic appearance is a secondary cue when available. */
    private fun associationScore(track: InternalTrack, detection: Detection, minimumIou: Float): Double {
        val predictedIou = iou(track.predicted, detection)
        val measuredIou = if (predictedIou < minimumIou) iou(ByteTrackBox(track.lastDetection), detection) else predictedIou
        val motionScore = if (measuredIou >= minimumIou) measuredIou.toDouble()
        else centerDistanceScore(track.predicted, detection)
        if (motionScore <= 0.0) return 0.0

        val previousAppearance = track.lastDetection.appearanceSignature
        val currentAppearance = detection.appearanceSignature
        if (appearanceWeight <= 0.0 || previousAppearance == null || currentAppearance == null) return motionScore

        val cosine = AppearanceSignature.cosineSimilarity(previousAppearance, currentAppearance)
        val normalizedAppearance = ((cosine + 1f) * 0.5f).toDouble()
        if (!normalizedAppearance.isFinite() || normalizedAppearance < minimumAppearanceSimilarity) return 0.0
        return (1.0 - appearanceWeight) * motionScore + appearanceWeight * normalizedAppearance
    }

    private fun centerDistanceScore(predicted: ByteTrackBox, detection: Detection): Double {
        val distance = hypot(
            predicted.centerX.toDouble() - detection.centerX.toDouble(),
            predicted.centerY.toDouble() - detection.centerY.toDouble(),
        )
        if (!distance.isFinite()) return 0.0
        val predictedArea = predicted.width.toDouble() * predicted.height.toDouble()
        val detectionArea = max(0f, detection.right - detection.left).toDouble() * max(0f, detection.bottom - detection.top).toDouble()
        val referenceScale = max(1.0, kotlin.math.sqrt(max(1.0, min(predictedArea, detectionArea))))
        val allowedDistance = centerDistanceScale * referenceScale
        if (!allowedDistance.isFinite() || allowedDistance <= 0.0 || distance > allowedDistance) return 0.0
        return 1.0 - distance / allowedDistance
    }

    private data class Match(
        val track: InternalTrack,
        val detection: IndexedValue<Detection>,
        val score: Double,
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
        private val history = ArrayList<TrackObservation>()
        var hits = 0
        var lostFrames = 0
        var matched = false
        var everOccluded = false
        var confidenceEma = initial.confidence
        var lastFrameIndex = initialFrameIndex
        var lastTimestampMs = initialTimestampMs

        init {
            history += TrackObservation(initialFrameIndex, initialTimestampMs, initial)
        }

        fun predictTo(timestampMs: Long) {
            matched = false
            val dtMs = timestampMs - lastTimestampMs
            predicted = if (dtMs > 0L) filter.predict(dtMs / 1000.0) else filter.box()
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
            history += TrackObservation(frameIndex, timestampMs, detection)
        }

        fun markLost() {
            lostFrames += 1
            everOccluded = true
        }

        fun toDomainTrack(): Track = Track(
            id = id,
            className = lastDetection.className,
            observations = history.toList(),
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
