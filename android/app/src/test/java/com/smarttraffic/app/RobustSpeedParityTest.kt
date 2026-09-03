package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.GroundPoint
import com.smarttraffic.app.domain.analysis.KotlinSpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.TrackObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors shared conceptually with tools/native-parity/test_traffic_core.cpp. */
class RobustSpeedParityTest {
    @Test
    fun linearVectorMatchesNativeGoldenContract() {
        val result = estimate(
            x = doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7),
            y = DoubleArray(8),
            timestamps = longArrayOf(0, 100, 200, 300, 400, 500, 600, 700),
        )
        assertNotNull(result)
        result!!
        assertEquals(1.0, result.metersPerSecond, 1e-9)
        assertEquals(1.0, result.velocityXMps!!, 1e-9)
        assertEquals(0.0, result.velocityYMps!!, 1e-9)
        assertEquals(0.9025f, result.confidence, 1e-6f)
        assertEquals(0.0, result.positionResidualMeters!!, 1e-9)
        assertEquals(28, result.sampleCount)
    }

    @Test
    fun diagonalVectorMatchesNativeGoldenContract() {
        val values = doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
        val result = estimate(
            x = values,
            y = values,
            timestamps = longArrayOf(0, 100, 200, 300, 400, 500, 600, 700),
        )
        assertNotNull(result)
        result!!
        assertEquals(kotlin.math.sqrt(2.0), result.metersPerSecond, 1e-9)
        assertEquals(1.0, result.velocityXMps!!, 1e-9)
        assertEquals(1.0, result.velocityYMps!!, 1e-9)
        assertEquals(0.9025f, result.confidence, 1e-6f)
        assertEquals(0.0, result.positionResidualMeters!!, 1e-9)
        assertEquals(28, result.sampleCount)
    }

    @Test
    fun variableTimestampsDoNotAssumeFixedFrameCadence() {
        val result = estimate(
            x = doubleArrayOf(0.0, 0.25, 0.55, 1.0, 1.3, 1.8, 2.2, 2.5),
            y = DoubleArray(8),
            timestamps = longArrayOf(0, 125, 275, 500, 650, 900, 1100, 1250),
        )
        assertNotNull(result)
        assertEquals(2.0, result!!.metersPerSecond, 1e-9)
        assertEquals(2.0, result.velocityXMps!!, 1e-9)
        assertEquals(0.94375f, result.confidence, 1e-6f)
        assertEquals(27, result.sampleCount)
        assertTrue(result.durationMs == 1250L)
    }

    private fun estimate(x: DoubleArray, y: DoubleArray, timestamps: LongArray) =
        KotlinSpeedEstimatorBackend.estimate(
            observations = timestamps.indices.map { index ->
                TrackObservation(
                    frameIndex = index.toLong(),
                    timestampMs = timestamps[index],
                    detection = Detection(
                        classId = 2,
                        className = "car",
                        confidence = 0.95f,
                        left = 0f,
                        top = 0f,
                        right = 1f,
                        bottom = 1f,
                        frameIndex = index.toLong(),
                        timestampMs = timestamps[index],
                    ),
                    groundPoint = GroundPoint(
                        xMeters = x[index],
                        yMeters = y[index],
                        sourcePixelX = 0.0,
                        sourcePixelY = 0.0,
                    ),
                )
            },
            minimumSamples = 8,
            minimumDurationMs = 0L,
            maxPlausibleSpeedKmh = 250.0,
        )
}
