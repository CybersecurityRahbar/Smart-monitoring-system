package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationFreeSpeedEstimatorTest {
    @Test
    fun estimatesVehicleSpeedWithoutGroundPlaneCalibration() {
        val track = syntheticTrack()

        val estimate = CalibrationFreeSpeedEstimator.estimate(
            track = track,
            minimumSamples = 8,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )

        assertNotNull(estimate)
        val speed = requireNotNull(estimate)
        assertEquals(SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE, speed.mode)
        assertEquals(3.24, speed.kilometersPerHour, 0.35)
        assertTrue(speed.sampleCount >= 8)
        assertTrue(speed.durationMs >= 500L)
        assertTrue(speed.confidence > 0.0f)
        assertTrue((speed.errorKmh ?: 0.0) > 0.0)
    }

    @Test
    fun rejectsCalibrationFreeEstimateForLargeObservationGap() {
        val track = syntheticTrack(withGap = true)
        val estimate = CalibrationFreeSpeedEstimator.estimate(
            track = track,
            minimumSamples = 8,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )
        assertTrue(estimate == null)
    }

    private fun syntheticTrack(withGap: Boolean = false): Track {
        val observations = (0 until 10).map { frame ->
            val timestamp = if (withGap && frame >= 5) frame.toLong() * 1000L + 800L else frame.toLong() * 1000L
            val x = frame * 10.0
            TrackObservation(
                frameIndex = frame.toLong(),
                timestampMs = timestamp,
                detection = Detection(
                    classId = 2,
                    className = "car",
                    confidence = 0.95f,
                    left = (x - 10.0).toFloat(),
                    top = 35f,
                    right = (x + 10.0).toFloat(),
                    bottom = 55f,
                    frameIndex = frame.toLong(),
                    timestampMs = timestamp,
                ),
            )
        }
        return Track(
            id = 7L,
            className = "car",
            observations = observations,
            trackConfidence = 0.95f,
            state = TrackState.CONFIRMED,
            hits = observations.size,
            ageFrames = observations.size,
            lastTimestampMs = observations.last().timestampMs,
        )
    }
}
