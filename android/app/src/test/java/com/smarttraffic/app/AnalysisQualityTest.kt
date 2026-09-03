package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.CalibrationValidator
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.PlateConsensus
import com.smarttraffic.app.domain.analysis.PlateReading
import com.smarttraffic.app.domain.analysis.SpeedEstimate
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import com.smarttraffic.app.domain.analysis.TrafficRuleConfig
import com.smarttraffic.app.domain.analysis.TrafficRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisQualityTest {
    @Test
    fun calibrationValidatorAcceptsOnlyFiniteNonSingularValidatedProfile() {
        val valid = CalibrationProfile(
            id = "cam-1",
            imageWidth = 1920,
            imageHeight = 1080,
            homography = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            reprojectionErrorPixels = 0.8,
            homographyInlierCount = 8,
            homographyInlierRatio = 1.0,
        )
        assertTrue(CalibrationValidator.validate(valid).accepted)

        val singular = valid.copy(homography = List(9) { 0.0 })
        assertFalse(CalibrationValidator.validate(singular).accepted)

        val unvalidated = valid.copy(reprojectionErrorPixels = null, homographyInlierRatio = null)
        assertFalse(CalibrationValidator.validate(unvalidated).accepted)
    }

    @Test
    fun plateConsensusPrefersConsistentTemporalReading() {
        val readings = listOf(
            PlateReading("123-ABC", 0.55f, 0, 7L),
            PlateReading("123ABC", 0.55f, 1, 7L),
            PlateReading("123ABC", 0.60f, 2, 7L),
            PlateReading("923ABC", 0.98f, 3, 7L),
        )

        val result = PlateConsensus.resolve(readings, halfLifeMs = 10_000L, minimumSupport = 0.20)

        assertEquals(1, result.size)
        assertEquals("123ABC", result.single().text)
    }

    @Test
    fun trafficRuleEngineEmitsOnlyForValidatedHighConfidenceSpeed() {
        val calibration = CalibrationProfile(
            id = "cam-1",
            imageWidth = 1920,
            imageHeight = 1080,
            homography = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            reprojectionErrorPixels = 0.5,
            homographyInlierCount = 12,
            homographyInlierRatio = 1.0,
            version = 3,
        )
        val detection = Detection(2, "car", 0.95f, 0f, 0f, 40f, 100f, 5L, 500L)
        val track = Track(
            id = 4L,
            className = "car",
            observations = listOf(TrackObservation(5L, 500L, detection)),
            trackConfidence = 0.90f,
        )
        val speed = SpeedEstimate(
            metersPerSecond = 25.0 / 3.6,
            kilometersPerHour = 25.0,
            confidence = 0.91f,
            sampleCount = 12,
            durationMs = 1200L,
        )

        val events = TrafficRuleEngine.evaluate(
            tracks = listOf(track),
            speedEstimates = mapOf(track.id to speed),
            config = TrafficRuleConfig(enabled = true, speedLimitKmh = 20.0, minimumSpeedConfidence = 0.70f),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = calibration,
        )

        assertEquals(1, events.size)
        assertEquals("SPEEDING", events.single().type)
        assertEquals(3, events.single().calibrationVersion)
        assertEquals("cam-1", events.single().calibrationId)

        val lowConfidenceEvents = TrafficRuleEngine.evaluate(
            tracks = listOf(track),
            speedEstimates = mapOf(track.id to speed.copy(confidence = 0.40f)),
            config = TrafficRuleConfig(enabled = true, speedLimitKmh = 20.0, minimumSpeedConfidence = 0.70f),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = calibration,
        )
        assertTrue(lowConfidenceEvents.isEmpty())
    }
}
