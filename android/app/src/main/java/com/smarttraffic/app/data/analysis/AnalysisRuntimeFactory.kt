package com.smarttraffic.app.data.analysis

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.smarttraffic.app.data.vision.AppearanceAugmentingDetector
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.data.vision.LiteRtObjectDetector
import com.smarttraffic.app.domain.analysis.ObjectDetector

/** Shared detector runtime construction for local and live analysis sessions. */
object AnalysisRuntimeFactory {
    data class DetectorRuntime(
        val detector: ObjectDetector,
        val accelerator: Accelerator,
        private val closeableDetector: LiteRtObjectDetector,
    ) : AutoCloseable {
        override fun close() = closeableDetector.close()
    }

    fun createDetector(
        context: Context,
        modelId: String,
        useAppearanceAssociation: Boolean,
    ): DetectorRuntime {
        val spec = DetectorModelRegistry.requireSpec(modelId)
        require(DetectorModelRegistry.isInstalled(context, spec)) {
            "Detector model is not installed: ${spec.assetPath}"
        }

        val (baseDetector, accelerator) = try {
            LiteRtObjectDetector(
                context = context,
                assetName = spec.assetPath,
                accelerator = Accelerator.GPU,
                inputSize = spec.inputSize,
                expectedOutput = spec.expectedOutput,
            ) to Accelerator.GPU
        } catch (_: Throwable) {
            LiteRtObjectDetector(
                context = context,
                assetName = spec.assetPath,
                accelerator = Accelerator.CPU,
                inputSize = spec.inputSize,
                expectedOutput = spec.expectedOutput,
            ) to Accelerator.CPU
        }

        val detector: ObjectDetector = if (useAppearanceAssociation) {
            AppearanceAugmentingDetector(baseDetector)
        } else baseDetector
        return DetectorRuntime(
            detector = detector,
            accelerator = accelerator,
            closeableDetector = baseDetector,
        )
    }
}
