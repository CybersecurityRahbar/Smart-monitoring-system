package com.smarttraffic.app

import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.domain.analysis.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackMotionGateTest {
    @Test
    fun preservesIdentityAcrossShortNonOverlapUsingPredictedMotion() {
        val tracker = ByteTrack()
        tracker.update(listOf(detection(0L, 0f)), 0L, 0L)
        val confirmed = tracker.update(listOf(detection(1L, 8f)), 1L, 100L)
        assertEquals(1, confirmed.size)
        assertEquals(1L, confirmed.single().id)

        val recovered = tracker.update(listOf(detection(2L, 55f)), 2L, 200L)
        assertEquals(1, recovered.size)
        assertEquals(1L, recovered.single().id)
        assertTrue(recovered.single().observations.last().detection.left >= 55f)
    }

    private fun detection(frameIndex: Long, left: Float): Detection = Detection(
        classId = 2,
        className = "car",
        confidence = 0.90f,
        left = left,
        top = 0f,
        right = left + 40f,
        bottom = 100f,
        frameIndex = frameIndex,
        timestampMs = frameIndex * 100L,
    )
}
