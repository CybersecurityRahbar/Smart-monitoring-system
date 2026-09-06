package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleKeypointMetricSpeedEstimatorTest {
    private val template = VehicleMetricTemplate36(
        points = List(36) { i ->
            VehicleMetricTemplatePoint("kp$i", (i % 6).toDouble(), (i / 6).toDouble())
        },
        planarFacets = listOf((0 until 12).map { "kp$it" }),
        verified = true,
        source = "synthetic unit-test template",
    )

    @Test
    fun constantMetricMotionProduces36Kmh() {
        val observations = (0 until 10).map { frame ->
            TrackObservation(
                frameIndex = frame.toLong(),
                timestampMs = frame * 100L,
                detection = Detection(2, "car", 0.95f, 0f, 0f, 100f, 100f, frame.toLong(), frame * 100L),
                keypoints = List(36) { i ->
                    VehicleKeypoint("kp$i", (i % 6).toDouble() + frame, (i / 6).toDouble(), 0.99f)
                },
            )
        }
        val track = Track(1L, "car", observations, 0.95f, state = TrackState.CONFIRMED, hits = 10)

        val estimate = VehicleKeypointMetricSpeedEstimator.estimate(
            track = track,
            template = template,
            minimumSamples = 6,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )

        assertNotNull(estimate)
        assertEquals(36.0, estimate!!.kilometersPerHour, 0.5)
        assertEquals("36-keypoint dynamic homography (sparse semantic points)", estimate.estimatorLabel)
        assertTrue(estimate.confidence > 0f)
    }

    @Test
    fun unverifiedTemplateFailsClosed() {
        val unverified = template.copy(verified = false)
        val estimate = VehicleKeypointMetricSpeedEstimator.estimate(
            track = Track(1L, "car", emptyList(), 0.9f),
            template = unverified,
            minimumSamples = 4,
            minimumDurationMs = 0L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )
        assertNull(estimate)
    }
}
