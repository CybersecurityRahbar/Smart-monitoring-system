package com.smarttraffic.app.data.analysis

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.smarttraffic.app.data.vision.AppearanceAugmentingDetector
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.data.vision.LiteRtObjectDetector
import com.smarttraffic.app.data.vision.LiteRtVehicleKeypointEstimator
import com.smarttraffic.app.data.vision.VehiclePoseModelRegistry
import com.smarttraffic.app.domain.analysis.ObjectDetector
import com.smarttraffic.app.domain.analysis.VehicleKeypointConfig
import com.smarttraffic.app.domain.analysis.VehicleKeypointEstimator

/** Shared perception runtime construction for local and live analysis sessions. */
object AnalysisRuntimeFactory {
    data class DetectorRuntime(
        val detector: ObjectDetector,
        val accelerator: Accelerator,
        val keypoints: VehicleKeypointEstimator?,
        private val closeableDetector: LiteRtObjectDetector,
        private val closeableKeypoints: LiteRtVehicleKeypointEstimator?,
    ) : AutoCloseable {
        override fun close() {
            runCatching { closeableKeypoints?.close() }
            closeableDetector.close()
        }
    }

    fun createDetector(
        context: Context,
        modelId: String,
        useAppearanceAssociation: Boolean,
    ): DetectorRuntime = createDetector(
        context = context,
        modelId = modelId,
        useAppearanceAssociation = useAppearanceAssociation,
        useVehicleKeypoints = false,
        vehicleKeypointModelId = VehicleKeypointConfig.DEFAULT_MODEL_ID,
    )

    /**
     * GPU is preferred for sustained detector throughput. If the LiteRT GPU runtime rejects the
     * model/device configuration through a normal exception, the factory constructs the exact same
     * model on CPU. NNAPI is intentionally not used because Android deprecated NNAPI in API 35.
     */
    fun createDetector(
        context: Context,
        modelId: String,
        useAppearanceAssociation: Boolean,
        useVehicleKeypoints: Boolean,
        vehicleKeypointModelId: String,
    ): DetectorRuntime {
        val spec = DetectorModelRegistry.requireSpec(modelId)
        require(DetectorModelRegistry.isInstalled(context, spec)) {
            "Detector model is not installed: ${spec.assetPath}"
        }

        val baseDetector = createBaseDetector(context, spec, Accelerator.GPU)
            ?: createBaseDetector(context, spec, Accelerator.CPU)
            ?: error("Unable to initialize LiteRT detector on GPU or CPU")

        val detector: ObjectDetector = if (useAppearanceAssociation) {
            AppearanceAugmentingDetector(baseDetector.first)
        } else baseDetector.first

        var poseRuntime: LiteRtVehicleKeypointEstimator? = null
        var poseBackend: VehicleKeypointEstimator? = null
        try {
            if (useVehicleKeypoints) {
                val poseSpec = VehiclePoseModelRegistry.requireSpec(vehicleKeypointModelId)
                VehiclePoseModelRegistry.requireReadyForInference(poseSpec, context)
                poseRuntime = LiteRtVehicleKeypointEstimator(
                    context = context,
                    spec = poseSpec,
                    accelerator = baseDetector.second,
                )
                poseBackend = poseRuntime
            }
            return DetectorRuntime(
                detector = detector,
                accelerator = baseDetector.second,
                keypoints = poseBackend,
                closeableDetector = baseDetector.first,
                closeableKeypoints = poseRuntime,
            )
        } catch (error: Throwable) {
            runCatching { poseRuntime?.close() }
            runCatching { baseDetector.first.close() }
            throw error
        }
    }

    private fun createBaseDetector(
        context: Context,
        spec: DetectorModelRegistry.ModelSpec,
        accelerator: Accelerator,
    ): Pair<LiteRtObjectDetector, Accelerator>? = runCatching {
        LiteRtObjectDetector(
            context = context,
            assetName = spec.assetPath,
            accelerator = accelerator,
            inputSize = spec.inputSize,
            expectedOutput = spec.expectedOutput,
        ) to accelerator
    }.getOrNull()
}
