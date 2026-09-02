package com.smarttraffic.app.data.vision

import android.content.Context

/** Explicit metadata for a detector artifact; runtime code should not guess its tensor contract. */
data class DetectorModelSpec(
    val id: String,
    val assetPath: String,
    val inputSize: Int,
    val expectedOutput: ExpectedOutput,
)

enum class ExpectedOutput {
    YOLO26_END_TO_END,
    YOLO_CLASSIC_8400,
}

object DetectorModelRegistry {
    val YOLO26N = DetectorModelSpec(
        id = "yolo26n",
        assetPath = "models/yolo26n.tflite",
        inputSize = 640,
        expectedOutput = ExpectedOutput.YOLO26_END_TO_END,
    )

    private val specs = listOf(YOLO26N)

    fun find(id: String): DetectorModelSpec? = specs.firstOrNull { it.id == id }

    fun requireSpec(id: String): DetectorModelSpec =
        find(id) ?: error("No detector model is registered for id=$id")

    fun isInstalled(context: Context, spec: DetectorModelSpec): Boolean =
        runCatching { context.assets.open(spec.assetPath).use { } }.isSuccess
}
