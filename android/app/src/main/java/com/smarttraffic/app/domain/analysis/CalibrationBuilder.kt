package com.smarttraffic.app.domain.analysis

/** Builds a versioned calibration profile from measured image-to-ground correspondences. */
object CalibrationBuilder {
    fun build(
        id: String,
        imageWidth: Int,
        imageHeight: Int,
        imagePoints: List<HomographyEstimator.Point>,
        groundPointsMeters: List<HomographyEstimator.Point>,
        version: Int = 1,
        reprojectionThresholdMeters: Double = 0.25,
        iterations: Int = 1000,
    ): CalibrationProfile {
        require(id.isNotBlank()) { "Calibration id must not be blank" }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(version > 0) { "Calibration version must be positive" }
        require(imagePoints.size == groundPointsMeters.size && imagePoints.size >= 4) {
            "At least four image/ground correspondences are required"
        }

        val estimate = HomographyEstimator.estimateRansac(
            source = imagePoints,
            target = groundPointsMeters,
            reprojectionThreshold = reprojectionThresholdMeters,
            iterations = iterations,
            minimumInliers = 4,
        )
        val profile = CalibrationProfile(
            id = id,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            homography = estimate.coefficients,
            reprojectionErrorPixels = estimate.meanError,
            version = version,
            homographyInlierCount = estimate.inlierCount,
            homographyInlierRatio = estimate.inlierRatio,
        )
        val validation = CalibrationValidator.validate(profile)
        require(validation.accepted) {
            "Calibration failed quality gates: ${validation.reasons.joinToString("; ")}"
        }
        return profile
    }
}
