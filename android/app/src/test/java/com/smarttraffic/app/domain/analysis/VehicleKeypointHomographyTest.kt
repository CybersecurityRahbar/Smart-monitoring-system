package com.smarttraffic.app.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class VehicleKeypointHomographyTest {
    @Test
    fun dlt_recovers_projective_mapping() {
        val h = doubleArrayOf(1.20, 0.10, 35.0, -0.05, 1.10, 20.0, 0.0007, -0.0004, 1.0)
        val points = listOf(20.0 to 30.0, 220.0 to 25.0, 230.0 to 170.0, 25.0 to 190.0)
        val correspondences = points.mapIndexed { i, p ->
            val q = VehicleKeypointHomography.project(h, p.first, p.second)
            VehicleTemplateCorrespondence("k$i", p.first, p.second, q.x, q.y)
        }
        val fit = VehicleKeypointHomography.estimate(correspondences, reprojectionThreshold = 1e-5)
        assertNotNull(fit)
        val result = fit!!
        val got = result.project(120.0, 90.0)
        val expected = VehicleKeypointHomography.project(h, 120.0, 90.0)
        assertEquals(expected.x, got.x, 1e-3)
        assertEquals(expected.y, got.y, 1e-3)
        assertEquals(4, result.inlierMask.count { it })
        assertTrue(result.medianReprojectionError < 1e-3)
    }

    @Test
    fun ransac_rejects_gross_outlier() {
        val correspondences = listOf(
            VehicleTemplateCorrespondence("a", 0.0, 0.0, 10.0, 20.0),
            VehicleTemplateCorrespondence("b", 100.0, 0.0, 110.0, 20.0),
            VehicleTemplateCorrespondence("c", 100.0, 100.0, 110.0, 120.0),
            VehicleTemplateCorrespondence("d", 0.0, 100.0, 10.0, 120.0),
            VehicleTemplateCorrespondence("e", 50.0, 50.0, 999.0, 999.0),
        )
        val fit = VehicleKeypointHomography.estimate(correspondences, reprojectionThreshold = 1.0)
        assertNotNull(fit)
        val result = fit!!
        assertEquals(4, result.inlierMask.count { it })
        assertTrue(!result.inlierMask[4])
        val projected = result.project(25.0, 75.0)
        assertTrue(hypot(projected.x - 35.0, projected.y - 95.0) < 1.0)
    }

    @Test
    fun collinear_correspondences_are_rejected() {
        val points = (0 until 4).map { i ->
            VehicleTemplateCorrespondence("k$i", i.toDouble(), i.toDouble(), (2 * i).toDouble(), (2 * i).toDouble())
        }
        assertEquals(null, VehicleKeypointHomography.estimate(points))
    }
}
