package com.smarttraffic.app.data.analysis

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.smarttraffic.app.data.vision.AppearanceAugmentingDetector
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.data.vision.LiteRtObjectDetector
import com.smarttraffic.app.domain.analysis.ObjectDetector

/**
 * Shared detector runtime construction for local and live analysis sessions.
 *
 * The production-safe default is CPU. GPU delegate initialization is a native operation and a
 * delegate/driver failure can terminate the Android process before Kotlin can catch an exception.
 * Until the target devices have passed an instrumented GPU smoke test, correctness and process
 * survival take priority over acceleration.
 */
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

        // Do not probe GPU implicitly. Native delegate failures are not guaranteed to be
        // catchable at the Kotlin layer (for example a vendor driver/native abort), which can
        // produce the exact "app keeps stopping" symptom when Run Real Analysis is pressed.
        val baseDetector = LiteRtObjectDetector(
            context = context,
            assetName = spec.assetPath,
            accelerator = Accelerator.CPU,
            inputSize = spec.inputSize,
            expectedOutput = spec.expectedOutput,
        )

        val detector: ObjectDetector = if (useAppearanceAssociation) {
            AppearanceAugmentingDetector(baseDetector)
        } else baseDetector
        return DetectorRuntime(
            detector = detector,
            accelerator = Accelerator.CPU,
            closeableDetector = baseDetector,
        )
    }
}
