package com.smarttraffic.app.data.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.smarttraffic.app.domain.analysis.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Real Android detector backend using LiteRT CompiledModel.
 *
 * Supported Ultralytics detection exports:
 * 1) YOLO26 end-to-end: [1,300,6] -> xyxy, confidence, classId.
 * 2) Traditional detection: [1,84,8400] -> xywh + class scores.
 *
 * The tensor contract is validated at runtime so an incompatible artifact cannot
 * silently produce plausible-looking but incorrect detections.
 */
class LiteRtObjectDetector(
    context: Context,
    private val assetName: String,
    accelerator: Accelerator = Accelerator.CPU,
    private val inputSize: Int = 640,
    private val classCount: Int = 80,
    private val classNames: Map<Int, String> = COCO_TRAFFIC_CLASSES,
    private val nmsIouThreshold: Float = 0.60f,
    private val expectedOutput: ExpectedOutput? = null,
) : com.smarttraffic.app.domain.analysis.ObjectDetector, AutoCloseable {
    private val model = CompiledModel.create(
        context.assets,
        assetName,
        CompiledModel.Options(accelerator),
        null,
    )
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    init {
        require(inputBuffers.size == 1) {
            "Smart Traffic detector expects one input tensor, got ${inputBuffers.size}"
        }
        require(outputBuffers.size == 1) {
            "Smart Traffic detector expects one output tensor, got ${outputBuffers.size}"
        }

        val input = inputBuffers.single()
        val inputShape = input.getShape().toList()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[1] == 3 &&
            inputShape[2] == inputSize && inputShape[3] == inputSize) {
            "Unsupported detector input shape=$inputShape; expected [1,3,$inputSize,$inputSize]"
        }
        require(input.getDataType().toString().contains("FLOAT32", ignoreCase = true)) {
            "Unsupported detector input type=${input.getDataType()}; this adapter currently requires FLOAT32"
        }

        validateOutputShape(outputBuffers.single().getShape().toList())
    }

    override suspend fun detect(frame: Any, timestampMs: Long, frameIndex: Long): List<Detection> {
        require(frame is Bitmap) { "LiteRtObjectDetector requires Bitmap frames" }
        val prepared = LetterboxPreprocessor.prepare(frame, inputSize)
        inputBuffers[0].writeFloat(prepared.chwRgb)

        val startNs = System.nanoTime()
        model.run(inputBuffers, outputBuffers)
        lastInferenceLatencyMs = (System.nanoTime() - startNs) / 1_000_000.0

        val output = outputBuffers.single()
        validateOutputShape(output.getShape().toList())
        return parseDetections(
            output.readFloat(), prepared,
            frame.width, frame.height, timestampMs, frameIndex,
        )
    }

    var lastInferenceLatencyMs: Double = 0.0
        private set

    private fun validateOutputShape(shape: List<Int>) {
        val accepted = setOf(listOf(1, 300, 6), listOf(1, classCount + 4, 8400))
        require(shape in accepted) {
            "Unsupported LiteRT detector output shape=$shape; expected [1,300,6] or [1,${classCount + 4},8400]"
        }
        when (expectedOutput) {
            ExpectedOutput.YOLO26_END_TO_END -> require(shape == listOf(1, 300, 6)) {
                "Model registry expects YOLO26 end-to-end output [1,300,6], actual=$shape"
            }
            ExpectedOutput.YOLO_CLASSIC_8400 -> require(shape == listOf(1, classCount + 4, 8400)) {
                "Model registry expects classic YOLO output [1,${classCount + 4},8400], actual=$shape"
            }
            null -> Unit
        }
    }

    private fun parseDetections(
        raw: FloatArray,
        letterbox: LetterboxPreprocessor.Result,
        originalWidth: Int,
        originalHeight: Int,
        timestampMs: Long,
        frameIndex: Long,
    ): List<Detection> {
        val endToEnd = raw.size == 300 * 6
        return if (endToEnd) {
            parseEndToEnd(raw, letterbox, originalWidth, originalHeight, timestampMs, frameIndex)
        } else {
            parseTraditional(raw, letterbox, originalWidth, originalHeight, timestampMs, frameIndex)
        }
    }

    private fun parseEndToEnd(
        raw: FloatArray,
        letterbox: LetterboxPreprocessor.Result,
        originalWidth: Int,
        originalHeight: Int,
        timestampMs: Long,
        frameIndex: Long,
    ): List<Detection> {
        val results = ArrayList<Detection>(32)
        for (i in 0 until 300) {
            val offset = i * 6
            val confidence = raw[offset + 4]
            val classId = raw[offset + 5].toInt()
            if (!confidence.isFinite() || confidence <= 0f || classId !in classNames) continue
            val left = toOriginalX(raw[offset], letterbox, originalWidth)
            val top = toOriginalY(raw[offset + 1], letterbox, originalHeight)
            val right = toOriginalX(raw[offset + 2], letterbox, originalWidth)
            val bottom = toOriginalY(raw[offset + 3], letterbox, originalHeight)
            if (right <= left || bottom <= top) continue
            results += Detection(classId, classNames.getValue(classId), confidence.coerceIn(0f, 1f),
                left, top, right, bottom, frameIndex, timestampMs)
        }
        return results
    }

    private fun parseTraditional(
        raw: FloatArray,
        letterbox: LetterboxPreprocessor.Result,
        originalWidth: Int,
        originalHeight: Int,
        timestampMs: Long,
        frameIndex: Long,
    ): List<Detection> {
        val candidates = ArrayList<Detection>(256)
        val numCandidates = 8400
        for (candidate in 0 until numCandidates) {
            val x = raw[candidate]
            val y = raw[numCandidates + candidate]
            val w = raw[2 * numCandidates + candidate]
            val h = raw[3 * numCandidates + candidate]
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue

            var bestClass = -1
            var bestConfidence = 0f
            for (classId in classNames.keys) {
                if (classId >= classCount) continue
                val score = raw[(4 + classId) * numCandidates + candidate]
                if (score > bestConfidence) {
                    bestConfidence = score
                    bestClass = classId
                }
            }
            if (bestClass < 0 || bestConfidence <= 0f) continue

            val left = toOriginalX((x - w * 0.5f) * inputSize, letterbox, originalWidth)
            val top = toOriginalY((y - h * 0.5f) * inputSize, letterbox, originalHeight)
            val right = toOriginalX((x + w * 0.5f) * inputSize, letterbox, originalWidth)
            val bottom = toOriginalY((y + h * 0.5f) * inputSize, letterbox, originalHeight)
            if (right <= left || bottom <= top) continue

            candidates += Detection(bestClass, classNames.getValue(bestClass), bestConfidence.coerceIn(0f, 1f),
                left, top, right, bottom, frameIndex, timestampMs)
        }
        return nonMaxSuppression(candidates, nmsIouThreshold)
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
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

private fun toOriginalX(x: Float, letterbox: LetterboxPreprocessor.Result, width: Int): Float =
    ((x - letterbox.padX) / letterbox.scale).coerceIn(0f, width.toFloat())

private fun toOriginalY(y: Float, letterbox: LetterboxPreprocessor.Result, height: Int): Float =
    ((y - letterbox.padY) / letterbox.scale).coerceIn(0f, height.toFloat())

private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
    val kept = ArrayList<Detection>()
    for ((_, group) in detections.groupBy { it.classId }) {
        val remaining = group.sortedByDescending { it.confidence }.toMutableList()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { candidate -> iou(best, candidate) > iouThreshold }
        }
    }
    return kept.sortedByDescending { it.confidence }
}

private fun iou(a: Detection, b: Detection): Float {
    val left = max(a.left, b.left)
    val top = max(a.top, b.top)
    val right = min(a.right, b.right)
    val bottom = min(a.bottom, b.bottom)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
    val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
    val union = areaA + areaB - intersection
    return if (union > 0f) intersection / union else 0f
}

internal object LetterboxPreprocessor {
    data class Result(
        val chwRgb: FloatArray,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    fun prepare(bitmap: Bitmap, size: Int): Result {
        require(size > 0) { "input size must be positive" }
        require(bitmap.width > 0 && bitmap.height > 0) { "bitmap dimensions must be positive" }
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val scaledWidth = max(1, (bitmap.width * scale).toInt())
        val scaledHeight = max(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val padX = (size - scaledWidth) / 2f
        val padY = (size - scaledHeight) / 2f

        val pixels = IntArray(size * size)
        java.util.Arrays.fill(pixels, 0xFF727272.toInt())
        val offset = padY.toInt() * size + padX.toInt()
        scaled.getPixels(pixels, offset, size, 0, 0, scaledWidth, scaledHeight)

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
