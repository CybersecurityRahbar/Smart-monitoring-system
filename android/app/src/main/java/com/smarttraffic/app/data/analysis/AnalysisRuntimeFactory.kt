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
import com.smarttraffic.app.domain.analysis.VehicleKeypointRuntimeHolder

/**
 * Shared perception runtime construction for local and live analysis sessions.
 *
 * The production-safe default is CPU. The vehicle-pose stage is optional and independently
 * closable. Keeping it separate from the object detector means a missing/unready pose artifact
 * can never silently replace or corrupt YOLO26n detection/tracking.
 */
object AnalysisRuntimeFactory {
    data class DetectorRuntime(
        val detector: ObjectDetector,
        val accelerator: Accelerator,
        val keypoints: VehicleKeypointEstimator?,
        private val closeableDetector: LiteRtObjectDetector,
        private val closeableKeypoints: LiteRtVehicleKeypointEstimator?,
    ) : AutoCloseable {
        override fun close() {
            if (closeableKeypoints != null && VehicleKeypointRuntimeHolder.active === closeableKeypoints) {
                VehicleKeypointRuntimeHolder.active = null
            }
            runCatching { closeableKeypoints?.close() }
            closeableDetector.close()
        }
    }

    /** Backward-compatible factory for callers that only need object detection. */
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
     * Constructs the object detector and, when explicitly enabled, the vehicle-pose backend.
     *
     * Pose configuration is fail-fast: an enabled pose stage without a verified trained artifact
     * is a configuration error, never a fake-success path. At present the 36-point registry entry
     * is metadata-only, so normal existing analyses continue to operate with pose disabled.
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

        // Do not probe GPU implicitly. Native delegate failures are not guaranteed to be
        // catchable at the Kotlin layer, which can produce an "app keeps stopping" symptom.
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

        var poseRuntime: LiteRtVehicleKeypointEstimator? = null
        var poseBackend: VehicleKeypointEstimator? = null
        if (useVehicleKeypoints) {
            val poseSpec = VehiclePoseModelRegistry.requireSpec(vehicleKeypointModelId)
            VehiclePoseModelRegistry.requireReadyForInference(poseSpec, context)
            poseRuntime = LiteRtVehicleKeypointEstimator(
                context = context,
                spec = poseSpec,
                accelerator = Accelerator.CPU,
            )
            poseBackend = poseRuntime
            VehicleKeypointRuntimeHolder.active = poseRuntime
        }

        return try {
            DetectorRuntime(
                detector = detector,
                accelerator = Accelerator.CPU,
                keypoints = poseBackend,
                closeableDetector = baseDetector,
                closeableKeypoints = poseRuntime,
            )
        } catch (error: Throwable) {
            if (VehicleKeypointRuntimeHolder.active === poseRuntime) VehicleKeypointRuntimeHolder.active = null
            runCatching { poseRuntime?.close() }
            runCatching { baseDetector.close() }
            throw error
        }
    }
}
