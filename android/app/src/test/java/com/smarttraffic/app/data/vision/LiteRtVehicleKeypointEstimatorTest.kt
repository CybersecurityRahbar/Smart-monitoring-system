package com.smarttraffic.app.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtVehicleKeypointEstimatorTest {
    @Test
    fun parser_maps_normalized_keypoints_from_letterboxed_crop_to_source_coordinates() {
        val spec = VehiclePoseModelRegistry.CARFUSION14
        val raw = FloatArray((5 + spec.keypointCount * 3) * spec.candidateCount)
        val candidate = 3
        raw[4 * spec.candidateCount + candidate] = 0.90f
        for (index in 0 until spec.keypointCount) {
            val base = (5 + index * 3) * spec.candidateCount + candidate
            raw[base] = (index + 10) / spec.inputSize.toFloat()
            raw[base + spec.candidateCount] = (index + 20) / spec.inputSize.toFloat()
            raw[base + 2 * spec.candidateCount] = 0.80f
        }

        val letterbox = LetterboxPreprocessor.Result(
            chwRgb = FloatArray(3 * spec.inputSize * spec.inputSize),
            scale = 2.0f,
            padX = 10.0f,
            padY = 20.0f,
        )
        val keypoints = LiteRtVehicleKeypointEstimator.parseSingleVehicle(
            raw = raw,
            spec = spec,
            inputSize = spec.inputSize,
            letterbox = letterbox,
            cropLeft = 100,
            cropTop = 200,
            frameWidth = 1920,
            frameHeight = 1080,
            confidenceThreshold = 0.25f,
        )

        assertEquals(spec.keypointCount, keypoints.size)
        val first = keypoints.first()
        assertEquals("right_front_wheel", first.name)
        assertEquals(100.0, first.x, 1e-4)
        assertEquals(200.0, first.y, 1e-4)
        assertEquals(0.80f, first.confidence, 1e-6f)
    }

    @Test
    fun parser_rejects_low_confidence_keypoints_without_rejecting_the_vehicle() {
        val spec = VehiclePoseModelRegistry.CARFUSION14
        val raw = FloatArray((5 + spec.keypointCount * 3) * spec.candidateCount)
        val candidate = 0
        raw[4 * spec.candidateCount + candidate] = 0.95f
        for (index in 0 until spec.keypointCount) {
            val base = (5 + index * 3) * spec.candidateCount + candidate
            raw[base] = 100f
            raw[base + spec.candidateCount] = 100f
            raw[base + 2 * spec.candidateCount] = if (index == 0) 0.10f else 0.90f
        }

        val keypoints = LiteRtVehicleKeypointEstimator.parseSingleVehicle(
            raw = raw,
            spec = spec,
            inputSize = spec.inputSize,
            letterbox = LetterboxPreprocessor.Result(FloatArray(3 * 640 * 640), 1f, 0f, 0f),
            cropLeft = 0,
            cropTop = 0,
            frameWidth = 1920,
            frameHeight = 1080,
            confidenceThreshold = 0.25f,
        )

        assertEquals(13, keypoints.size)
        assertTrue(keypoints.none { it.name == "right_front_wheel" })
    }
}
