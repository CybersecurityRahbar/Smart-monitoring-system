package com.smarttraffic.app

import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.PlateConsensus
import com.smarttraffic.app.domain.analysis.PlateReading
import com.smarttraffic.app.domain.analysis.SpeedEstimate
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.domain.analysis.TrackObservation
import com.smarttraffic.app.domain.analysis.TrafficRuleConfig
import com.smarttraffic.app.domain.analysis.TrafficRuleEngine
import com.smarttraffic.app.domain.analysis.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisReliabilityExtendedTest {
    @Test
    fun plateConsensusUsesPresentationTimestampNotFrameNumber() {
        val readings = listOf(
            PlateReading("ABC123", 0.90f, frameIndex = 1, trackId = 7L, timestampMs = 0L),
            PlateReading("XYZ999", 0.60f, frameIndex = 2, trackId = 7L, timestampMs = 100L),
        )

        val resolved = PlateConsensus.resolve(
            readings = readings,
            halfLifeMs = 100L,
            minimumSupport = 0.0,
        ).single()

        // The newest reading is one half-life newer, so its weighted support is 0.60
        // versus 0.45 for the older reading. The winner must therefore be XYZ999.
        assertEquals("XYZ999", resolved.text)
        assertEquals(100L, resolved.timestampMs)
    }

    @Test
    fun speedingRuleProducesEventOnlyFromValidatedHighConfidenceSpeed() {
        val track = Track(
            id = 42L,
            className = "car",
            observations = listOf(
                observation(0L, 0L),
                observation(1L, 100L),
            ),
            trackConfidence = 0.95f,
        )
        val calibration = CalibrationProfile(
            id = "road-a",
            imageWidth = 1920,
            imageHeight = 1080,
            homography = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            reprojectionErrorTargetUnits = 0.05,
            version = 3,
            homographyInlierCount = 12,
            homographyInlierRatio = 1.0,
        )
        val speed = SpeedEstimate(
            metersPerSecond = 30.0 / 3.6,
            kilometersPerHour = 30.0,
            confidence = 0.90f,
            sampleCount = 20,
            durationMs = 1900L,
        )

        val events = TrafficRuleEngine.evaluate(
            tracks = listOf(track),
            speedEstimates = mapOf(track.id to speed),
            config = TrafficRuleConfig(
                enabled = true,
                speedLimitKmh = 20.0,
                minimumSpeedConfidence = 0.70f,
            ),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = calibration,
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("SPEEDING", event.type)
        assertEquals(42L, event.trackId)
        assertEquals(3, event.calibrationVersion)
        assertTrue(event.evidenceRequested)
    }

    @Test
    fun speedingRuleRejectsLowConfidenceMeasurement() {
        val speed = SpeedEstimate(
            metersPerSecond = 10.0,
            kilometersPerHour = 36.0,
            confidence = 0.40f,
            sampleCount = 20,
            durationMs = 1900L,
        )
        val events = TrafficRuleEngine.evaluate(
            tracks = emptyList(),
            speedEstimates = mapOf(1L to speed),
            config = TrafficRuleConfig(
                enabled = true,
                speedLimitKmh = 20.0,
                minimumSpeedConfidence = 0.70f,
            ),
            detectorModel = "yolo26n",
            tracker = "bytetrack",
            calibration = CalibrationProfile(
                id = "road-a",
                imageWidth = 1920,
                imageHeight = 1080,
                homography = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                homographyInlierCount = 12,
                homographyInlierRatio = 1.0,
            ),
        )

        assertTrue(events.isEmpty())
    }

    private fun observation(frameIndex: Long, timestampMs: Long): TrackObservation =
        TrackObservation(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            detection = Detection(
                classId = 2,
                className = "car",
                confidence = 0.95f,
                left = 100f + frameIndex,
                top = 100f,
                right = 140f + frameIndex,
                bottom = 180f,
                frameIndex = frameIndex,
                timestampMs = timestampMs,
            ),
        )
}
