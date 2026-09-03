package com.smarttraffic.app

import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.domain.analysis.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackPredictionRecoveryTest {
    @Test
    fun handlesVariableTimestampIntervalsWithoutAssumingFixedFrameRate() {
        val tracker = ByteTrack()
        tracker.update(listOf(detection(0L, 0f, 0L)), 0L, 0L)
        val first = tracker.update(listOf(detection(1L, 12f, 150L)), 1L, 150L)
        assertEquals(1, first.size)
        assertEquals(1L, first.single().id)

        val second = tracker.update(listOf(detection(2L, 28f, 350L)), 2L, 350L)
        assertEquals(1, second.size)
        assertEquals(1L, second.single().id)
    }

    @Test
    fun rejectsLargeDirectionReversalInsteadOfTeleportingPrediction() {
        val tracker = ByteTrack()
        tracker.update(listOf(detection(0L, 0f, 0L)), 0L, 0L)
        tracker.update(listOf(detection(1L, 20f, 100L)), 1L, 100L)

        val result = tracker.update(listOf(detection(2L, -90f, 200L)), 2L, 200L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun accelerationClampStillAllowsBoundedShortRecovery() {
        val tracker = ByteTrack()
        tracker.update(listOf(detection(0L, 0f, 0L)), 0L, 0L)
        tracker.update(listOf(detection(1L, 8f, 100L)), 1L, 100L)

        val recovered = tracker.update(listOf(detection(2L, 55f, 200L)), 2L, 200L)
        assertEquals(1, recovered.size)
        assertEquals(1L, recovered.single().id)
    }

    private fun detection(frameIndex: Long, left: Float, timestampMs: Long): Detection = Detection(
        classId = 2,
        className = "car",
        confidence = 0.90f,
        left = left,
        top = 0f,
        right = left + 40f,
        bottom = 100f,
        frameIndex = frameIndex,
        timestampMs = timestampMs,
    )
}
