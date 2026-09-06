package com.smarttraffic.app.domain.analysis

/**
 * Configuration constants for the optional vehicle-pose stage.
 *
 * The feature remains opt-in through [AnalysisConfig.useVehicleKeypoints]. Keeping the model id
 * here avoids changing serialized/configuration call sites until a real trained 36-point artifact
 * is ready. The public 14-point model is the compatibility reference; the 36-point template is
 * the intended final target.
 */
object VehicleKeypointConfig {
    const val DEFAULT_MODEL_ID = "vehicle-keypoints-36-template"
    const val REFERENCE_14_MODEL_ID = "vehicle-keypoints-14"
    const val TARGET_KEYPOINT_COUNT = 36
}
