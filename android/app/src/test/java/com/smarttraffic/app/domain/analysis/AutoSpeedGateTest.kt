package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedGateTest {
    @Test
    fun buildsStableImageSpaceGateFromVehicleMotion() {
        val track = syntheticTrack(reverse = false)
        val gate = AutoSpeedGateBuilder.build(listOf(track), 100, 100, null)
        assertNotNull(gate)
        assertTrue(requireNotNull(gate).separationMeters == null)
        assertTrue(gate.line1.startPixelX.isFinite())
        assertTrue(gate.line2.startPixelX.isFinite())
    }

    @Test
    fun metricGateMeasuresForwardCrossingSpeedFromInterpolatedTimestamps() {
        val track = syntheticTrack(reverse = false)
        val gate = requireNotNull(AutoSpeedGateBuilder.build(listOf(track), 100, 100, identityCalibration()))
        val speed = requireNotNull(SpeedGateEstimator.estimate(track, gate))
        assertEquals(3.6, speed.kilometersPerHour, 0.25)
        assertTrue(speed.durationMs > 0L)
        assertTrue(speed.errorKmh >= 0.0)
        assertTrue(speed.confidence > 0.0f)
    }

    @Test
    fun metricGateAlsoMeasuresReverseCrossingOrder() {
        val track = syntheticTrack(reverse = true)
        val gate = requireNotNull(AutoSpeedGateBuilder.build(listOf(track), 100, 100, identityCalibration()))
        val speed = requireNotNull(SpeedGateEstimator.estimate(track, gate))
        assertEquals(3.6, speed.kilometersPerHour, 0.25)
        assertTrue(speed.velocityXMps != null)
        assertTrue(speed.velocityXMps!! < 0.0)
    }

    private fun identityCalibration() = CalibrationProfile(
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

    private fun syntheticTrack(reverse: Boolean): Track {
        val indices = (0..9).toList().let { if (reverse) it.reversed() else it }
        val observations = indices.mapIndexed { frame, xInt ->
            val x = xInt.toDouble()
            val detection = Detection(
                classId = 2,
                className = "car",
                confidence = 0.95f,
                left = (x - 2.0).toFloat(),
                top = 40f,
                right = (x + 2.0).toFloat(),
                bottom = 50f,
                frameIndex = frame.toLong(),
                timestampMs = frame.toLong() * 1000L,
            )
            TrackObservation(
                frameIndex = frame.toLong(),
                timestampMs = frame.toLong() * 1000L,
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
