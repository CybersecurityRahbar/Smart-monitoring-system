package com.smarttraffic.app

import com.smarttraffic.app.data.tracking.ByteTrack
import com.smarttraffic.app.domain.analysis.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackAppearanceTest {
    @Test
    fun appearanceCuePreventsIdentitySwapWhenGeometryIsIndistinguishable() {
        val tracker = ByteTrack(appearanceWeight = 0.40, minimumAppearanceSimilarity = 0.60)
        tracker.update(
            listOf(
                detection(0L, 0f, floatArrayOf(1f, 0f, 0f)),
                detection(0L, 40f, floatArrayOf(0f, 1f, 0f)),
            ),
            0L,
            0L,
        )

        val tracks = tracker.update(
            listOf(
                detection(1L, 20f, floatArrayOf(0f, 1f, 0f)),
                detection(1L, 20f, floatArrayOf(1f, 0f, 0f)),
            ),
            1L,
            100L,
        )

        assertEquals(2, tracks.size)
        val redTrack = tracks.first { it.observations.last().detection.appearanceSignature?.firstOrNull() == 1f }
        val greenTrack = tracks.first { it.observations.last().detection.appearanceSignature?.getOrNull(1) == 1f }
        assertEquals(1L, redTrack.id)
        assertEquals(2L, greenTrack.id)
        assertTrue(redTrack.observations.size >= 2)
        assertTrue(greenTrack.observations.size >= 2)
    }

    private fun detection(frameIndex: Long, left: Float, signature: FloatArray): Detection = Detection(
        classId = 2,
        className = "car",
        confidence = 0.90f,
        left = left,
        top = 0f,
        right = left + 40f,
        bottom = 100f,
        frameIndex = frameIndex,
        timestampMs = frameIndex * 100L,
        appearanceSignature = signature,
    )
}
