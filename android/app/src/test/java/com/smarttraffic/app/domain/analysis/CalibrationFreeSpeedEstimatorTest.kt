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
        assertEquals(3.24, speed.kilometersPerHour, 0.45)
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

    @Test
    fun trimsOnlyWhenEnoughSamplesRemain() {
        val track = syntheticTrack(pointCount = 30, pixelsPerSecond = 10.0)
        val estimate = CalibrationFreeSpeedEstimator.estimate(
            track = track,
            minimumSamples = 8,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 600L,
        )
        assertNotNull(estimate)
        assertTrue(requireNotNull(estimate).sampleCount < track.observations.size)
        assertTrue(requireNotNull(estimate).sampleCount >= 8)
    }

    @Test
    fun handlesAnInvalidMiddleIntervalWithoutMisaligningTime() {
        val observations = (0 until 12).map { frame ->
            val x = frame * 10.0
            val bad = frame == 5
            TrackObservation(
                frameIndex = frame.toLong(),
                timestampMs = frame.toLong() * 1000L,
                detection = Detection(
                    classId = 2,
                    className = "car",
                    confidence = 0.95f,
                    left = if (bad) Float.NaN else (x - 10.0).toFloat(),
                    top = 35f,
                    right = (x + 10.0).toFloat(),
                    bottom = 55f,
                    frameIndex = frame.toLong(),
                    timestampMs = frame.toLong() * 1000L,
                ),
            )
        }
        val track = Track(
            id = 11L,
            className = "car",
            observations = observations,
            trackConfidence = 0.95f,
            state = TrackState.CONFIRMED,
            hits = observations.size,
            ageFrames = observations.size,
            lastTimestampMs = observations.last().timestampMs,
        )

        val estimate = CalibrationFreeSpeedEstimator.estimate(
            track = track,
            minimumSamples = 8,
            minimumDurationMs = 500L,
            maxPlausibleSpeedKmh = 250.0,
            maximumObservationGapMs = 2000L,
        )
        assertNotNull(estimate)
        assertTrue(requireNotNull(estimate).kilometersPerHour.isFinite())
    }

    private fun syntheticTrack(
        withGap: Boolean = false,
        pointCount: Int = 10,
        pixelsPerSecond: Double = 10.0,
    ): Track {
        val observations = (0 until pointCount).map { frame ->
            val timestamp = if (withGap && frame >= 5) frame.toLong() * 100L + 800L else frame.toLong() * 100L
            val x = frame * pixelsPerSecond * 0.1
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
