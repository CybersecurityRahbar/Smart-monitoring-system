package com.smarttraffic.app.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorModelRegistryTest {
    @Test
    fun yolo26n_contract_is_pinned_to_official_android_litert_asset() {
        val spec = DetectorModelRegistry.YOLO26N

        assertEquals("yolo26n", spec.id)
        assertEquals("models/yolo26n.tflite", spec.assetPath)
        assertEquals(640, spec.inputSize)
        assertEquals(ExpectedOutput.YOLO_CLASSIC_8400, spec.expectedOutput)
        assertEquals(3, spec.inputChannels)
        assertEquals("NCHW", spec.inputLayout)
        assertEquals(80, spec.classCount)
        assertEquals(84 * 8400, spec.outputElements)
        assertEquals(
            "d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73",
            spec.sha256,
        )
        assertTrue(spec.sourceUrl!!.endsWith("/yolo26n_w8a32.tflite"))
    }

    @Test
    fun known_traffic_classes_match_coco_ids() {
        assertEquals("car", LiteRtObjectDetector.COCO_TRAFFIC_CLASSES[2])
        assertEquals("motorcycle", LiteRtObjectDetector.COCO_TRAFFIC_CLASSES[3])
        assertEquals("bus", LiteRtObjectDetector.COCO_TRAFFIC_CLASSES[5])
        assertEquals("truck", LiteRtObjectDetector.COCO_TRAFFIC_CLASSES[7])
    }
}
