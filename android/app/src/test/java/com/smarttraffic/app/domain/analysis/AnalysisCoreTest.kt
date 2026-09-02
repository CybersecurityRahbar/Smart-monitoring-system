package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCoreTest {
    @Test
    fun identityHomographyKeepsPointUnchanged() {
        val projector = HomographyProjector(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        val point = projector.project(12.5, 7.25)
        assertEquals(12.5, point.xMeters, 1e-9)
        assertEquals(7.25, point.yMeters, 1e-9)
    }

    @Test
    fun robustSpeedRejectsLargeOutlier() {
        val observations = (0 until 12).map { i ->
            val detection = Detection(2, "car", 0.95f, 0f, 0f, 10f, 10f, i.toLong(), i * 100L)
            val x = if (i == 8) 2.2 else i * 0.5
            TrackObservation(i.toLong(), i * 100L, detection, GroundPoint(x, 0.0, 0.0, 0.0))
        }
        val estimate = RobustSpeedEstimator(minimumSamples = 8, minimumDurationMs = 700L).estimate(observations)
        requireNotNull(estimate)
        assertTrue(estimate.kilometersPerHour > 15.0)
        assertTrue(estimate.kilometersPerHour < 25.0)
    }

    @Test
    fun speedValidationComputesToleranceRates() {
        val summary = ValidationMetrics.speed(
            referenceKmh = listOf(50.0, 60.0, 70.0),
            estimatedKmh = listOf(51.0, 61.0, 75.0),
        )
        requireNotNull(summary)
        assertEquals(3, summary.count)
        assertEquals(1.0, summary.within10Percent, 1e-9)
    }

    @Test
    fun homographyEstimatorRecoversSimpleTranslation() {
        val source = listOf(
            HomographyEstimator.Point(0.0, 0.0), HomographyEstimator.Point(10.0, 0.0),
            HomographyEstimator.Point(10.0, 10.0), HomographyEstimator.Point(0.0, 10.0),
        )
        val target = source.map { HomographyEstimator.Point(it.x + 5.0, it.y + 3.0) }
        val result = HomographyEstimator.estimate(source, target)
        assertTrue(result.meanError < 1e-6)
        assertTrue(result.maxError < 1e-5)
    }
}
