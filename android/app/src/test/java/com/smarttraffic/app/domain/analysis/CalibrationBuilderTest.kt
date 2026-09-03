package com.smarttraffic.app.domain.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationBuilderTest {
    @Test
    fun buildsValidatedProfileAndRejectsSingleOutlier() {
        val image = listOf(
            HomographyEstimator.Point(100.0, 100.0),
            HomographyEstimator.Point(900.0, 100.0),
            HomographyEstimator.Point(900.0, 600.0),
            HomographyEstimator.Point(100.0, 600.0),
            HomographyEstimator.Point(500.0, 350.0),
            HomographyEstimator.Point(300.0, 450.0),
        )
        val ground = image.map { point ->
            HomographyEstimator.Point(
                x = point.x * 0.01 + 1.0,
                y = point.y * 0.01 - 2.0,
            )
        }.toMutableList()
        ground[5] = HomographyEstimator.Point(40.0, -30.0)

        val profile = CalibrationBuilder.build(
            id = "test-road",
            imageWidth = 1000,
            imageHeight = 700,
            imagePoints = image,
            groundPointsMeters = ground,
            reprojectionThresholdMeters = 0.05,
            iterations = 1000,
        )

        assertEquals(5, profile.homographyInlierCount)
        assertTrue((profile.homographyInlierRatio ?: 0.0) >= 5.0 / 6.0)
        assertTrue((profile.reprojectionErrorTargetUnits ?: Double.POSITIVE_INFINITY) <= 0.05)
        assertEquals(1, profile.version)
        assertEquals(9, profile.homography.size)
    }

    @Test
    fun rejectsSingularHomographyAndMissingMeasuredError() {
        val singular = CalibrationProfile(
            id = "singular",
            imageWidth = 1920,
            imageHeight = 1080,
            homography = listOf(
                1.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 1.0,
            ),
            homographyInlierCount = 4,
            homographyInlierRatio = 1.0,
        )
        val validation = CalibrationValidator.validate(singular)
        assertFalse(validation.accepted)
        assertTrue(validation.reasons.any { it.contains("singular") })
        assertTrue(validation.reasons.any { it.contains("reprojection error") })
    }
}
