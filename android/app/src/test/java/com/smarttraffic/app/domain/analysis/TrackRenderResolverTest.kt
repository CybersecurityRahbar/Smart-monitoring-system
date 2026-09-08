package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRenderResolverTest {
    private fun detection(x: Float, timestamp: Long, frame: Long) = Detection(
        classId = 2,
        className = "car",
        confidence = 0.9f,
        left = x,
        top = 10f,
        right = x + 20f,
        bottom = 30f,
        frameIndex = frame,
        timestampMs = timestamp,
    )

    private fun track() = Track(
        id = 7L,
        className = "car",
        observations = listOf(
            TrackObservation(0L, 0L, detection(0f, 0L, 0L)),
            TrackObservation(1L, 100L, detection(10f, 100L, 1L)),
            TrackObservation(2L, 200L, detection(20f, 200L, 2L)),
            TrackObservation(3L, 300L, detection(30f, 300L, 3L)),
        ),
        trackConfidence = 0.9f,
    )

    @Test
    fun resolvesAtPlayerTimestampBetweenObservations() {
        val resolved = TrackRenderResolver.resolve(track(), 150L)
        assertNotNull(resolved)
        assertEquals(15f, resolved!!.detection.left, 0.1f)
        assertFalse(resolved.predicted)
        assertEquals(150L, resolved.observationTimestampMs)
    }

    @Test
    fun boundedPredictionBridgesShortEndGap() {
        val resolved = TrackRenderResolver.resolve(track(), 350L, maxExtrapolationMs = 100L)
        assertNotNull(resolved)
        assertTrue(resolved!!.predicted)
        assertEquals(35f, resolved.detection.left, 0.5f)
    }

    @Test
    fun predictionStopsAfterConfiguredHorizon() {
        val resolved = TrackRenderResolver.resolve(track(), 501L, maxExtrapolationMs = 200L)
        assertEquals(null, resolved)
    }

    @Test
    fun trailUsesTimeWindowNotSampleCount() {
        val trail = TrackRenderResolver.trail(track(), 250L, windowMs = 120L)
        assertTrue(trail.all { it.observationTimestampMs >= 130L })
        assertEquals(250L, trail.last().observationTimestampMs)
    }
}
