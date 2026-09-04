package com.smarttraffic.app.data.tracking

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.MultiObjectTracker
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import com.smarttraffic.app.domain.analysis.TrackState
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ByteTrack-inspired Android tracker with Kalman prediction, global assignment, two-stage
 * high/low score association, class-aware gating, bounded motion recovery and an optional
 * deterministic appearance cue.
 *
 * The motion model is deliberately conservative: empirical velocity is bounded by an
 * acceleration prior and a short prediction horizon before being blended with Kalman output.
 * It is not claimed to be reference-equivalent to official ByteTrack.
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
    private val empiricalVelocityHorizonSeconds: Double = 0.25,
    private val empiricalVelocityBlend: Double = 0.75,
    private val maxAccelerationPixelsPerSecond2: Double = 1_200.0,
    private val maxAdaptiveGateSeconds: Double = 0.50,
    private val maxHistoryObservations: Int = 900,
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
        require(empiricalVelocityHorizonSeconds > 0.0 && empiricalVelocityHorizonSeconds.isFinite())
        require(empiricalVelocityBlend in 0.0..1.0 && empiricalVelocityBlend.isFinite())
        require(maxAccelerationPixelsPerSecond2 > 0.0 && maxAccelerationPixelsPerSecond2.isFinite())
        require(maxAdaptiveGateSeconds > 0.0 && maxAdaptiveGateSeconds.isFinite())
        require(maxHistoryObservations >= 32)
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
            val track = InternalTrack(
                id = nextId++,
                initial = indexed.value,
                initialFrameIndex = frameIndex,
                initialTimestampMs = timestampMs,
                empiricalVelocityHorizonSeconds = empiricalVelocityHorizonSeconds,
                empiricalVelocityBlend = empiricalVelocityBlend,
                maxAccelerationPixelsPerSecond2 = maxAccelerationPixelsPerSecond2,
                maxHistoryObservations = maxHistoryObservations,
            )
            track.hits = 1
            track.matched = true
            tracks[track.id] = track
        }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next().value
            if (track.lostFrames > maxLostFrames) {
                track.state = TrackState.REMOVED
                iterator.remove()
            } else if (track.hits == 1 && track.lostFrames > 0) {
                track.state = TrackState.REMOVED
                iterator.remove()
            } else {
                track.matched = false
            }
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

    private fun associationScore(track: InternalTrack, detection: Detection, minimumIou: Float): Double {
        val predicted = track.predictedBox()
        val predictedIou = iou(predicted, detection)
        val measuredIou = if (predictedIou < minimumIou) iou(ByteTrackBox(track.lastDetection), detection) else predictedIou
        val motionScore = if (measuredIou >= minimumIou) measuredIou.toDouble()
        else centerDistanceScore(track, predicted, detection)
        if (motionScore <= 0.0) return 0.0

        val previousAppearance = track.lastDetection.appearanceSignature
        val currentAppearance = detection.appearanceSignature
        if (appearanceWeight <= 0.0 || previousAppearance == null || currentAppearance == null) return motionScore

        val cosine = AppearanceSignature.cosineSimilarity(previousAppearance, currentAppearance)
        val normalizedAppearance = ((cosine + 1f) * 0.5f).toDouble()
        if (!normalizedAppearance.isFinite() || normalizedAppearance < minimumAppearanceSimilarity) return 0.0
        return (1.0 - appearanceWeight) * motionScore + appearanceWeight * normalizedAppearance
    }

    private fun centerDistanceScore(track: InternalTrack, predicted: ByteTrackBox, detection: Detection): Double {
        val detectionCenterX = (detection.left + detection.right) * 0.5
        val detectionCenterY = (detection.top + detection.bottom) * 0.5
        val distance = hypot(
            predicted.centerX.toDouble() - detectionCenterX.toDouble(),
            predicted.centerY.toDouble() - detectionCenterY.toDouble(),
        )
        if (!distance.isFinite()) return 0.0
        val predictedArea = predicted.width.toDouble() * predicted.height.toDouble()
        val detectionArea = max(0f, detection.right - detection.left).toDouble() * max(0f, detection.bottom - detection.top).toDouble()
        val referenceScale = max(1.0, sqrt(max(1.0, min(predictedArea, detectionArea))))
        val adaptiveSeconds = min(track.predictionDtSeconds, maxAdaptiveGateSeconds)
        val temporalMultiplier = 1.0 + min(2.0, adaptiveSeconds / 0.10) * 0.35
        val allowedDistance = centerDistanceScale * referenceScale * temporalMultiplier
        if (!allowedDistance.isFinite() || allowedDistance <= 0.0 || distance > allowedDistance) return 0.0
        return 1.0 - distance / allowedDistance
    }

    private data class Match(val track: InternalTrack, val detection: IndexedValue<Detection>, val score: Double)

    private class InternalTrack(
        val id: Long,
        initial: Detection,
        initialFrameIndex: Long,
        initialTimestampMs: Long,
        private val empiricalVelocityHorizonSeconds: Double,
        private val empiricalVelocityBlend: Double,
        private val maxAccelerationPixelsPerSecond2: Double,
        private val maxHistoryObservations: Int,
    ) {
        var lastDetection = initial
        private var previousDetection: Detection? = null
        var predicted = ByteTrackBox(initial)
        var predictionDtSeconds = 0.0
            private set
        private var velocityX = 0.0
        private var velocityY = 0.0
        private var hasVelocity = false
        private val filter = KalmanBoxPredictor(predicted)
        private val history = ArrayDeque<TrackObservation>(maxHistoryObservations)
        private val confidenceHistory = ArrayDeque<Double>(maxHistoryObservations)
        var hits = 0
        var lostFrames = 0
        var ageFrames = 1
        var matched = false
        var everOccluded = false
        var confidenceEma = initial.confidence
        var lastFrameIndex = initialFrameIndex
        var lastTimestampMs = initialTimestampMs
        var state: TrackState = TrackState.TENTATIVE

        init {
            history += TrackObservation(initialFrameIndex, initialTimestampMs, initial)
            confidenceHistory += initial.confidence.toDouble()
        }

        fun predictTo(timestampMs: Long) {
            matched = false
            ageFrames += 1
            val dtMs = timestampMs - lastTimestampMs
            val dtSeconds = dtMs / 1000.0
            predictionDtSeconds = dtSeconds.coerceAtLeast(0.0)
            val kalmanPrediction = if (dtMs > 0L) filter.predict(dtSeconds) else filter.box()
            predicted = empiricalMotionPrediction(timestampMs, kalmanPrediction, dtSeconds)
        }

        fun predictedBox(): ByteTrackBox = predicted

        private fun empiricalMotionPrediction(timestampMs: Long, kalmanPrediction: ByteTrackBox, dtSeconds: Double): ByteTrackBox {
            val previous = previousDetection ?: return kalmanPrediction
            if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > empiricalVelocityHorizonSeconds) return kalmanPrediction
            val observedDtMs = lastTimestampMs - previous.timestampMs
            if (observedDtMs <= 0L) return kalmanPrediction
            val observedDtSeconds = observedDtMs / 1000.0
            if (!observedDtSeconds.isFinite() || observedDtSeconds <= 0.0) return kalmanPrediction
            val lastBox = ByteTrackBox(lastDetection)
            val previousBox = ByteTrackBox(previous)
            var observedVelocityX = (lastBox.centerX - previousBox.centerX) / observedDtSeconds
            var observedVelocityY = (lastBox.centerY - previousBox.centerY) / observedDtSeconds
            if (!observedVelocityX.isFinite() || !observedVelocityY.isFinite()) return kalmanPrediction

            if (hasVelocity) {
                val maxDelta = maxAccelerationPixelsPerSecond2 * observedDtSeconds
                observedVelocityX = clamp(observedVelocityX, velocityX - maxDelta, velocityX + maxDelta)
                observedVelocityY = clamp(observedVelocityY, velocityY - maxDelta, velocityY + maxDelta)
                val dot = observedVelocityX * velocityX + observedVelocityY * velocityY
                val currentSpeed = hypot(observedVelocityX, observedVelocityY)
                val previousSpeed = hypot(velocityX, velocityY)
                if (dot < 0.0 && currentSpeed > previousSpeed * 0.75) return kalmanPrediction
            }

            val displacementSeconds = min(dtSeconds, empiricalVelocityHorizonSeconds)
            val displacementX = (observedVelocityX * displacementSeconds).toFloat()
            val displacementY = (observedVelocityY * displacementSeconds).toFloat()
            if (!displacementX.isFinite() || !displacementY.isFinite()) return kalmanPrediction
            val observedPrediction = ByteTrackBox(
                left = lastBox.left + displacementX,
                top = lastBox.top + displacementY,
                right = lastBox.right + displacementX,
                bottom = lastBox.bottom + displacementY,
            )
            val blend = empiricalVelocityBlend.toFloat()
            return ByteTrackBox(
                left = lerp(kalmanPrediction.left, observedPrediction.left, blend),
                top = lerp(kalmanPrediction.top, observedPrediction.top, blend),
                right = lerp(kalmanPrediction.right, observedPrediction.right, blend),
                bottom = lerp(kalmanPrediction.bottom, observedPrediction.bottom, blend),
            )
        }

        fun update(detection: Detection, frameIndex: Long, timestampMs: Long) {
            if (lostFrames > 0) everOccluded = true
            previousDetection = lastDetection
            val observedDtSeconds = (timestampMs - lastTimestampMs) / 1000.0
            val currentBox = ByteTrackBox(detection)
            if (observedDtSeconds.isFinite() && observedDtSeconds > 0.0 && observedDtSeconds <= empiricalVelocityHorizonSeconds) {
                val dx = currentBox.centerX - ByteTrackBox(lastDetection).centerX
                val dy = currentBox.centerY - ByteTrackBox(lastDetection).centerY
                val nextVelocityX = dx / observedDtSeconds
                val nextVelocityY = dy / observedDtSeconds
                if (nextVelocityX.isFinite() && nextVelocityY.isFinite()) {
                    velocityX = if (hasVelocity) {
                        val maxDelta = maxAccelerationPixelsPerSecond2 * observedDtSeconds
                        clamp(nextVelocityX, velocityX - maxDelta, velocityX + maxDelta)
                    } else nextVelocityX
                    velocityY = if (hasVelocity) {
                        val maxDelta = maxAccelerationPixelsPerSecond2 * observedDtSeconds
                        clamp(nextVelocityY, velocityY - maxDelta, velocityY + maxDelta)
                    } else nextVelocityY
                    hasVelocity = true
                }
            }

            filter.update(currentBox)
            lastDetection = detection
            predicted = filter.box()
            hits += 1
            lostFrames = 0
            matched = true
            if (hits >= 2) state = TrackState.CONFIRMED
            confidenceEma = 0.80f * confidenceEma + 0.20f * detection.confidence
            confidenceHistory.addLast(detection.confidence.toDouble())
            while (confidenceHistory.size > maxHistoryObservations) confidenceHistory.removeFirst()
            lastFrameIndex = frameIndex
            lastTimestampMs = timestampMs
            history.addLast(TrackObservation(frameIndex, timestampMs, detection))
            if (history.size > maxHistoryObservations) history.removeFirst()
            state = TrackState.CONFIRMED
        }

        fun markLost() {
            lostFrames += 1
            everOccluded = true
            if (hits >= 2) state = TrackState.LOST
        }

        fun toDomainTrack(): Track = Track(
            id = id,
            className = lastDetection.className,
            observations = history.toList(),
            trackConfidence = confidenceEma.coerceIn(0f, 1f),
            wasOccluded = everOccluded,
            state = if (state == TrackState.LOST) TrackState.LOST else TrackState.CONFIRMED,
            hits = hits,
            misses = lostFrames,
            ageFrames = ageFrames,
            lastTimestampMs = lastTimestampMs,
        )
    }
}

private fun clamp(value: Double, lower: Double, upper: Double): Double = value.coerceIn(lower, upper)
private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

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
