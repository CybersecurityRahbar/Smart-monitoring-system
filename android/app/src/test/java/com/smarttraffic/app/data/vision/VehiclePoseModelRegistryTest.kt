package com.smarttraffic.app.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePoseModelRegistryTest {
    @Test
    fun carfusion14_contract_matches_public_reference_schema() {
        val spec = VehiclePoseModelRegistry.CARFUSION14

        assertEquals(14, spec.keypointCount)
        assertEquals(5 + 14 * 3, spec.outputElements / spec.candidateCount)
        assertEquals(14, spec.keypointNames.size)
        assertEquals("right_front_wheel", spec.keypointNames.first())
        assertEquals("center", spec.keypointNames.last())
        assertFalse(spec.readyForInference)
    }

    @Test
    fun target36_contract_has_correct_classic_yolo_tensor_size_but_is_not_enabled() {
        val spec = VehiclePoseModelRegistry.TEMPLATE36

        assertEquals(36, spec.keypointCount)
        assertEquals(113, spec.outputElements / spec.candidateCount)
        assertEquals(113 * 8400, spec.outputElements)
        assertEquals(36, spec.keypointNames.size)
        assertFalse(spec.readyForInference)
    }

    @Test
    fun registry_finds_only_declared_models() {
        assertTrue(VehiclePoseModelRegistry.find("vehicle-keypoints-14") != null)
        assertTrue(VehiclePoseModelRegistry.find("vehicle-keypoints-36-template") != null)
        assertEquals(null, VehiclePoseModelRegistry.find("made-up-model"))
    }
}
