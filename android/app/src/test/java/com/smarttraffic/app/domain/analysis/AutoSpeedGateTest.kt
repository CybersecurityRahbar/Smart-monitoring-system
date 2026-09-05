package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedGateTest {
    @Test
    fun buildsStableImageSpaceGateFromVehicleMotion() {
        val track = syntheticTrack()
        val gate = AutoSpeedGateBuilder.build(
            tracks = listOf(track),
            imageWidth = 100,
            imageHeight = 100,
            calibration = null,
        )

        assertNotNull(gate)
        assertTrue(requireNotNull(gate).separationMeters == null)
        assertTrue(gate.line1.startPixelX.isFinite())
        assertTrue(gate.line2.startPixelX.isFinite())
    }

    @Test
    fun metricGateMeasuresCrossingSpeedFromInterpolatedTimestamps() {
        val track = syntheticTrack()
        val calibration = CalibrationProfile(
            id = "identity",
            imageWidth = 100,
            imageHeight = 100,
            homography = listOf(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0,
            ),
            reprojectionErrorPixels = 0.1,
            homographyInlierCount = 8,
            homographyInlierRatio = 1.0,
        )
        val gate = AutoSpeedGateBuilder.build(
            tracks = listOf(track),
            imageWidth = 100,
            imageHeight = 100,
            calibration = calibration,
        )

        assertNotNull(gate)
        val speedGate = requireNotNull(gate)
        assertNotNull(speedGate.separationMeters)
        val estimate = SpeedGateEstimator.estimate(track, speedGate)
        assertNotNull(estimate)
        val speed = requireNotNull(estimate)
        assertEquals(3.6, speed.kilometersPerHour, 0.25)
        assertTrue(speed.durationMs > 0L)
        assertTrue(speed.errorKmh >= 0.0)
    }

    private fun syntheticTrack(): Track {
        val observations = (0..9).map { index ->
            val x = index.toDouble()
            val detection = Detection(
                classId = 2,
                className = "car",
                confidence = 0.95f,
                left = (x - 2.0).toFloat(),
                top = 40f,
                right = (x + 2.0).toFloat(),
                bottom = 50f,
                frameIndex = index.toLong(),
                timestampMs = index.toLong() * 1000L,
            )
            TrackObservation(
                frameIndex = index.toLong(),
                timestampMs = index.toLong() * 1000L,
                detection = detection,
                groundPoint = GroundPoint(x, 50.0, x, 50.0),
            )
        }
        return Track(
            id = 1L,
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
