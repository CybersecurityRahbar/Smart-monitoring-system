package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRuleEnginePolicyTest {
    @Test
    fun enabledRulesWithoutCalibrationDoNotFailAnalysis() {
        val result = TrafficRuleEngine.evaluate(
            tracks = emptyList(),
            speedEstimates = emptyMap(),
            config = TrafficRuleConfig(enabled = true),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = null,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun calibrationFreeSpeedNeverCreatesPhysicalViolation() {
        val track = Track(
            id = 7L,
            className = "car",
            observations = listOf(
                TrackObservation(
                    frameIndex = 1L,
                    timestampMs = 100L,
                    detection = Detection(2, "car", 0.95f, 10f, 10f, 30f, 30f, 1L, 100L),
                ),
                TrackObservation(
                    frameIndex = 2L,
                    timestampMs = 200L,
                    detection = Detection(2, "car", 0.95f, 20f, 10f, 40f, 30f, 2L, 200L),
                ),
            ),
            trackConfidence = 0.95f,
            hits = 2,
            state = TrackState.CONFIRMED,
            lastTimestampMs = 200L,
        )
        val speed = SpeedEstimate(
            metersPerSecond = 20.0,
            kilometersPerHour = 72.0,
            confidence = 0.95f,
            sampleCount = 8,
            durationMs = 800L,
            errorKmh = 10.0,
            mode = SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE,
        )
        val calibration = CalibrationProfile(
            id = "physical",
            imageWidth = 100,
            imageHeight = 100,
            homography = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            reprojectionErrorPixels = 0.1,
            homographyInlierRatio = 1.0,
        )

        val result = TrafficRuleEngine.evaluate(
            tracks = listOf(track),
            speedEstimates = mapOf(track.id to speed),
            config = TrafficRuleConfig(enabled = true, speedLimitKmh = 50.0),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = calibration,
        )

        assertTrue(result.isEmpty())
    }
}
