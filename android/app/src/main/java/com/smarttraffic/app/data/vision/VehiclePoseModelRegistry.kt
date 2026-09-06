package com.smarttraffic.app.data.vision

import android.content.Context

/**
 * Immutable metadata for a vehicle-pose artifact.
 *
 * A pose model is deliberately separate from the existing YOLO26n object detector. The
 * registry refuses to treat an unverified placeholder as a usable model, which prevents a
 * configuration-only `kpt_shape: [36, 3]` change from being mistaken for trained weights.
 */
data class VehiclePoseModelSpec(
    val id: String,
    val assetPath: String,
    val inputSize: Int,
    val keypointCount: Int,
    val keypointNames: List<String>,
    val classCount: Int = 1,
    val candidateCount: Int = 8400,
    val sourceUrl: String? = null,
    val license: String? = null,
    val trainingDataset: String? = null,
    val readyForInference: Boolean = false,
) {
    val outputElements: Int = (5 + keypointCount * 3) * candidateCount

    init {
        require(id.isNotBlank())
        require(assetPath.isNotBlank())
        require(inputSize > 0)
        require(keypointCount > 0)
        require(keypointNames.size == keypointCount) {
            "Pose model $id declares $keypointCount keypoints but ${keypointNames.size} names"
        }
        require(classCount > 0)
        require(candidateCount > 0)
    }
}

object VehiclePoseModelRegistry {
    /**
     * Reference-only CarFusion model contract. The public checkpoint is 14-point YOLO26-pose,
     * but this repository intentionally does not vendor the external binary here yet.
     */
    val CARFUSION14 = VehiclePoseModelSpec(
        id = "vehicle-keypoints-14",
        assetPath = "models/vehicle_keypoints_14.tflite",
        inputSize = 640,
        keypointCount = 14,
        keypointNames = listOf(
            "right_front_wheel",
            "left_front_wheel",
            "right_rear_wheel",
            "left_rear_wheel",
            "right_front_light",
            "left_front_light",
            "right_rear_light",
            "left_rear_light",
            "right_front_roof",
            "left_front_roof",
            "right_rear_roof",
            "left_rear_roof",
            "exhaust",
            "center",
        ),
        sourceUrl = "https://huggingface.co/kiselyovd/vehicle-keypoints",
        license = "MIT for repository/model artifact; CarFusion data has separate CMU research terms",
        trainingDataset = "CarFusion",
        readyForInference = false,
    )

    /**
     * Target contract for the 2026 calibration-free research path.
     *
     * IMPORTANT: the semantic index table from the paper's Figure 4 has not yet been verified
     * as a machine-readable annotation schema. Therefore these names are placeholders and this
     * spec cannot be enabled as an inference backend. For one-class Ultralytics classic pose,
     * the expected flattened output is [1, 113, 8400] = 5 + (36 * 3) channels per candidate.
     */
    val TEMPLATE36 = VehiclePoseModelSpec(
        id = "vehicle-keypoints-36-template",
        assetPath = "models/vehicle_keypoints_36.tflite",
        inputSize = 640,
        keypointCount = 36,
        keypointNames = List(36) { index -> "template_kp_%02d".format(index) },
        sourceUrl = null,
        license = null,
        trainingDataset = null,
        readyForInference = false,
    )

    private val specs = listOf(CARFUSION14, TEMPLATE36)

    fun find(id: String): VehiclePoseModelSpec? = specs.firstOrNull { it.id == id }

    fun requireSpec(id: String): VehiclePoseModelSpec =
        find(id) ?: error("No vehicle-pose model is registered for id=$id")

    fun isInstalled(context: Context, spec: VehiclePoseModelSpec): Boolean =
        runCatching { context.assets.open(spec.assetPath).use { } }.isSuccess

    fun requireReadyForInference(spec: VehiclePoseModelSpec, context: Context) {
        require(spec.readyForInference) {
            "Vehicle-pose model ${spec.id} is metadata-only until its keypoint schema and trained artifact are verified"
        }
        require(isInstalled(context, spec)) {
            "Vehicle-pose model is not installed: ${spec.assetPath}"
        }
    }
}
