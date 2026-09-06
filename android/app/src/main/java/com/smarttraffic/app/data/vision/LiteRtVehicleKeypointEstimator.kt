package com.smarttraffic.app.data.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.smarttraffic.app.domain.analysis.Detection
import com.smarttraffic.app.domain.analysis.VehicleKeypoint
import com.smarttraffic.app.domain.analysis.VehicleKeypointEstimator
import kotlin.math.exp
import kotlin.math.max

/**
 * LiteRT backend for a single-vehicle YOLO classic pose export.
 *
 * Expected tensor contract for one class and 36 keypoints:
 *   input  = [1, 3, inputSize, inputSize], FP32 NCHW RGB
 *   output = [1, 5 + keypointCount*3, 8400]
 * where the five leading channels are xywh + object/class confidence and each keypoint is
 * x, y, confidence. The adapter validates the flattened tensor size instead of silently
 * accepting another pose layout.
 *
 * The caller supplies a vehicle detection. The estimator expands that crop slightly, runs pose
 * inference on the crop, and maps keypoints back into original-frame pixel coordinates. Pose
 * failure never changes the detector/tracker identity: an empty list simply means geometry is
 * unavailable for that observation.
 */
class LiteRtVehicleKeypointEstimator(
    context: Context,
    private val spec: VehiclePoseModelSpec,
    accelerator: Accelerator = Accelerator.CPU,
    private val confidenceThreshold: Float = 0.25f,
    private val cropMarginX: Float = 0.10f,
    private val cropMarginY: Float = 0.15f,
) : VehicleKeypointEstimator, AutoCloseable {
    private val model = CompiledModel.create(
        context.assets,
        spec.assetPath,
        CompiledModel.Options(accelerator),
        null,
    )
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()
    private val expectedInputElements = 3 * spec.inputSize * spec.inputSize
    private val channelsPerCandidate = 5 + spec.keypointCount * 3
    private val expectedOutputElements = channelsPerCandidate * spec.candidateCount

    init {
        require(spec.readyForInference) {
            "Vehicle-pose model ${spec.id} is not approved for inference"
        }
        require(confidenceThreshold in 0f..1f)
        require(cropMarginX >= 0f && cropMarginY >= 0f)
        require(inputBuffers.size == 1) {
            "Vehicle pose runtime expects one input tensor, got ${inputBuffers.size}"
        }
        require(outputBuffers.size == 1) {
            "Vehicle pose runtime expects one output tensor, got ${outputBuffers.size}"
        }
    }

    override suspend fun estimate(frame: Any, detection: Detection): List<VehicleKeypoint> {
        require(frame is Bitmap) { "LiteRtVehicleKeypointEstimator requires Bitmap frames" }
        val crop = cropVehicle(frame, detection, cropMarginX, cropMarginY)
        try {
            val prepared = LetterboxPreprocessor.prepare(crop.bitmap, spec.inputSize)
            require(prepared.chwRgb.size == expectedInputElements) {
                "Prepared pose input has ${prepared.chwRgb.size} elements; expected $expectedInputElements"
            }
            inputBuffers.single().writeFloat(prepared.chwRgb)
            val startNs = System.nanoTime()
            model.run(inputBuffers, outputBuffers)
            lastInferenceLatencyMs = (System.nanoTime() - startNs) / 1_000_000.0

            val raw = outputBuffers.single().readFloat()
            return parseSingleVehicle(
                raw = raw,
                spec = spec,
                inputSize = spec.inputSize,
                letterbox = prepared,
                cropLeft = crop.left,
                cropTop = crop.top,
                frameWidth = frame.width,
                frameHeight = frame.height,
                confidenceThreshold = confidenceThreshold,
            )
        } finally {
            if (!crop.ownsOriginal && !crop.bitmap.isRecycled) crop.bitmap.recycle()
        }
    }

    var lastInferenceLatencyMs: Double = 0.0
        private set

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
    }

    private data class Crop(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
        val ownsOriginal: Boolean,
    )

    private fun cropVehicle(
        frame: Bitmap,
        detection: Detection,
        marginX: Float,
        marginY: Float,
    ): Crop {
        val boxWidth = max(1f, detection.right - detection.left)
        val boxHeight = max(1f, detection.bottom - detection.top)
        val left = (detection.left - boxWidth * marginX).toInt().coerceIn(0, frame.width - 1)
        val top = (detection.top - boxHeight * marginY).toInt().coerceIn(0, frame.height - 1)
        val right = (detection.right + boxWidth * marginX).toInt().coerceIn(left + 1, frame.width)
        val bottom = (detection.bottom + boxHeight * marginY).toInt().coerceIn(top + 1, frame.height)
        return Crop(Bitmap.createBitmap(frame, left, top, right - left, bottom - top), left, top, ownsOriginal = false)
    }

    companion object {
        /**
         * Deterministic tensor parser exposed for JVM unit tests. It accepts the standard
         * channel-major Ultralytics pose layout and returns source-frame keypoints for the
         * highest-scoring candidate.
         */
        internal fun parseSingleVehicle(
            raw: FloatArray,
            spec: VehiclePoseModelSpec,
            inputSize: Int,
            letterbox: LetterboxPreprocessor.Result,
            cropLeft: Int,
            cropTop: Int,
            frameWidth: Int,
            frameHeight: Int,
            confidenceThreshold: Float,
        ): List<VehicleKeypoint> {
            val channels = 5 + spec.keypointCount * 3
            val expected = channels * spec.candidateCount
            require(raw.size == expected) {
                "Unsupported vehicle pose output element count=${raw.size}; expected $expected " +
                    "for [1,$channels,${spec.candidateCount}]"
            }
            require(inputSize > 0)
            require(frameWidth > 0 && frameHeight > 0)

            var bestCandidate = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (candidate in 0 until spec.candidateCount) {
                val rawScore = raw[4 * spec.candidateCount + candidate]
                val score = normalizeConfidence(rawScore)
                if (score < confidenceThreshold) continue
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }
            if (bestCandidate < 0) return emptyList()

            val outputCoordinatesLookNormalized = looksNormalized(
                raw = raw,
                candidate = bestCandidate,
                spec = spec,
            )

            return buildList(spec.keypointCount) {
                for (index in 0 until spec.keypointCount) {
                    val base = (5 + index * 3) * spec.candidateCount + bestCandidate
                    val rawX = raw[base]
                    val rawY = raw[base + spec.candidateCount]
                    val rawConfidence = raw[base + 2 * spec.candidateCount]
                    val pointConfidence = normalizeConfidence(rawConfidence)
                    if (!rawX.isFinite() || !rawY.isFinite() || pointConfidence < confidenceThreshold) continue

                    val modelX = if (outputCoordinatesLookNormalized) rawX * inputSize else rawX
                    val modelY = if (outputCoordinatesLookNormalized) rawY * inputSize else rawY
                    val cropX = (modelX - letterbox.padX) / letterbox.scale
                    val cropY = (modelY - letterbox.padY) / letterbox.scale
                    val sourceX = (cropLeft + cropX.toDouble()).coerceIn(0.0, frameWidth.toDouble())
                    val sourceY = (cropTop + cropY.toDouble()).coerceIn(0.0, frameHeight.toDouble())
                    add(
                        VehicleKeypoint(
                            name = spec.keypointNames[index],
                            x = sourceX,
                            y = sourceY,
                            confidence = pointConfidence,
                        )
                    )
                }
            }
        }

        private fun looksNormalized(
            raw: FloatArray,
            candidate: Int,
            spec: VehiclePoseModelSpec,
        ): Boolean {
            var maxAbs = 0f
            for (index in 0 until spec.keypointCount) {
                val base = (5 + index * 3) * spec.candidateCount + candidate
                maxAbs = max(maxAbs, kotlin.math.abs(raw[base]))
                maxAbs = max(maxAbs, kotlin.math.abs(raw[base + spec.candidateCount]))
                if (maxAbs > 1.5f) return false
            }
            return maxAbs <= 1.5f
        }

        private fun normalizeConfidence(value: Float): Float {
            if (!value.isFinite()) return 0f
            return if (value in 0f..1f) value else (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
        }
    }
}
