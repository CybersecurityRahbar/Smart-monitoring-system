package com.smarttraffic.app.data.vision

import android.content.Context

/** Immutable metadata for a detector artifact; runtime code must not guess its tensor contract. */
data class DetectorModelSpec(
    val id: String,
    val assetPath: String,
    val inputSize: Int,
    val expectedOutput: ExpectedOutput,
    val inputChannels: Int = 3,
    val inputLayout: String = "NCHW",
    val classCount: Int = 80,
    val outputElements: Int = 84 * 8400,
    val sha256: String? = null,
    val sourceUrl: String? = null,
)

enum class ExpectedOutput {
    YOLO26_END_TO_END,
    YOLO_CLASSIC_8400,
}

object DetectorModelRegistry {
    /** Official Ultralytics Android LiteRT w8a32 asset from yolo-flutter-app v0.6.6. */
    val YOLO26N = DetectorModelSpec(
        id = "yolo26n",
        assetPath = "models/yolo26n.tflite",
        inputSize = 640,
        expectedOutput = ExpectedOutput.YOLO_CLASSIC_8400,
        inputChannels = 3,
        inputLayout = "NCHW",
        classCount = 80,
        outputElements = 84 * 8400,
        sha256 = "d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73",
        sourceUrl = "https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26n_w8a32.tflite",
    )

    private val specs = listOf(YOLO26N)

    fun find(id: String): DetectorModelSpec? = specs.firstOrNull { it.id == id }

    fun requireSpec(id: String): DetectorModelSpec =
        find(id) ?: error("No detector model is registered for id=$id")

    fun isInstalled(context: Context, spec: DetectorModelSpec): Boolean =
        runCatching { context.assets.open(spec.assetPath).use { } }.isSuccess
}
