package com.smarttraffic.app.data.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.ObjectDetector
import kotlin.math.max
import kotlin.math.min

/**
 * First real on-device detector backend for Smart Traffic.
 *
 * Model weights stay outside source control. Place an exported LiteRT model
 * under app/src/main/assets/models/ and pass its asset name.
 * This adapter currently supports the current Ultralytics end-to-end [N, 6]
 * output: x1, y1, x2, y2, confidence, classId.
 */
class LiteRtObjectDetector(
    context: Context,
    private val assetName: String,
    accelerator: Accelerator = Accelerator.CPU,
    private val inputSize: Int = 640,
    private val classNames: Map<Int, String> = COCO_TRAFFIC_CLASSES,
) : ObjectDetector, AutoCloseable {
    private val model = CompiledModel.create(
        context.assets,
        assetName,
        CompiledModel.Options(accelerator),
    )

    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    init {
        require(inputBuffers.size == 1) {
            "Smart Traffic detector expects one input tensor, got ${inputBuffers.size}"
        }
        require(outputBuffers.isNotEmpty()) {
            "Smart Traffic detector requires at least one output tensor"
        }
    }

    override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> {
        require(frame is Bitmap) { "LiteRtObjectDetector requires Bitmap frames" }

        val originalWidth = frame.width
        val originalHeight = frame.height
        val prepared = LetterboxPreprocessor.prepare(frame, inputSize)
        inputBuffers[0].writeFloat(prepared.chwRgb)

        val startNs = System.nanoTime()
        model.run(inputBuffers, outputBuffers)
        val endNs = System.nanoTime()
        lastInferenceLatencyMs = (endNs - startNs) / 1_000_000.0

        val raw = outputBuffers[0].readFloat()
        return parseDetections(
            raw = raw,
            letterbox = prepared,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            timestampMs = timestampMs,
            frameIndex = frameIndex,
        )
    }

    var lastInferenceLatencyMs: Double = 0.0
        private set

    private fun parseDetections(
        raw: FloatArray,
        letterbox: LetterboxPreprocessor.Result,
        originalWidth: Int,
        originalHeight: Int,
        timestampMs: Long,
        frameIndex: Long,
    ): List<Detection> {
        if (raw.isEmpty() || raw.size % 6 != 0) {
            throw IllegalStateException(
                "Unsupported LiteRT detector output: ${raw.size} float values; expected [N,6] end-to-end output",
            )
        }

        val count = raw.size / 6
        val results = ArrayList<Detection>(count)
        for (i in 0 until count) {
            val offset = i * 6
            val confidence = raw[offset + 4]
            val classId = raw[offset + 5].toInt()
            if (!confidence.isFinite() || classId !in classNames || confidence <= 0f) continue

            val left = ((raw[offset] - letterbox.padX) / letterbox.scale)
                .coerceIn(0f, originalWidth.toFloat())
            val top = ((raw[offset + 1] - letterbox.padY) / letterbox.scale)
                .coerceIn(0f, originalHeight.toFloat())
            val right = ((raw[offset + 2] - letterbox.padX) / letterbox.scale)
                .coerceIn(0f, originalWidth.toFloat())
            val bottom = ((raw[offset + 3] - letterbox.padY) / letterbox.scale)
                .coerceIn(0f, originalHeight.toFloat())

            if (right <= left || bottom <= top) continue

            results += Detection(
                classId = classId,
                className = classNames.getValue(classId),
                confidence = confidence.coerceIn(0f, 1f),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                frameIndex = frameIndex,
                timestampMs = timestampMs,
            )
        }
        return results
    }

    override fun close() {
        model.destroy()
    }

    companion object {
        val COCO_TRAFFIC_CLASSES: Map<Int, String> = mapOf(
            2 to "car",
            3 to "motorcycle",
            5 to "bus",
            7 to "truck",
        )
    }
}

/** CPU-side RGB NCHW letterboxing. This can later be replaced by the C++
 * preprocessing path without changing the detector contract. */
internal object LetterboxPreprocessor {
    data class Result(
        val chwRgb: FloatArray,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    fun prepare(bitmap: Bitmap, size: Int): Result {
        require(size > 0) { "input size must be positive" }
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val scaledWidth = max(1, (bitmap.width * scale).toInt())
        val scaledHeight = max(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val padX = (size - scaledWidth) / 2f
        val padY = (size - scaledHeight) / 2f

        val pixels = IntArray(size * size)
        java.util.Arrays.fill(pixels, 0xFF727272.toInt())
        scaled.getPixels(
            pixels,
            padY.toInt() * size + padX.toInt(),
            size,
            0,
            0,
            scaledWidth,
            scaledHeight,
        )

        val plane = size * size
        val chw = FloatArray(plane * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            chw[i] = ((p ushr 16) and 0xFF) / 255f
            chw[plane + i] = ((p ushr 8) and 0xFF) / 255f
            chw[plane * 2 + i] = (p and 0xFF) / 255f
        }
        if (scaled !== bitmap) scaled.recycle()
        return Result(chw, scale, padX, padY)
    }
}
