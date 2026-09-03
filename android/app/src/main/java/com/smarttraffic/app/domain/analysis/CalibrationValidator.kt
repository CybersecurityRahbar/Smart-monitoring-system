package com.smarttraffic.app.domain.analysis

import kotlin.math.abs

/** Explicit calibration quality gate shared by speed/rule layers and the calibration UI. */
data class CalibrationValidation(
    val accepted: Boolean,
    val reasons: List<String>,
)

object CalibrationValidator {
    fun validate(
        profile: CalibrationProfile,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null,
        maxReprojectionErrorPixels: Double = 2.0,
        maxReprojectionErrorTargetUnits: Double = 0.25,
        minimumInlierRatio: Double = 0.75,
    ): CalibrationValidation {
        val reasons = ArrayList<String>()
        if (profile.id.isBlank()) reasons += "Calibration id is empty"
        if (profile.imageWidth <= 0 || profile.imageHeight <= 0) reasons += "Image dimensions must be positive"
        if (expectedWidth != null && profile.imageWidth != expectedWidth) reasons += "Calibration width does not match source width"
        if (expectedHeight != null && profile.imageHeight != expectedHeight) reasons += "Calibration height does not match source height"
        if (profile.version <= 0) reasons += "Calibration version must be positive"
        if (profile.homography.size != 9 || profile.homography.any { !it.isFinite() }) {
            reasons += "Homography must contain exactly nine finite coefficients"
        } else if (abs(determinant3x3(profile.homography)) < 1e-12) {
            reasons += "Homography is singular"
        }

        val targetError = profile.reprojectionErrorTargetUnits
        val pixelError = profile.reprojectionErrorPixels
        if (targetError != null) {
            if (!targetError.isFinite() || targetError < 0.0) {
                reasons += "Target-unit reprojection error must be finite and non-negative"
            } else if (targetError > maxReprojectionErrorTargetUnits) {
                reasons += "Target-unit reprojection error exceeds configured gate"
            }
        } else if (pixelError != null) {
            if (!pixelError.isFinite() || pixelError < 0.0) {
                reasons += "Pixel reprojection error must be finite and non-negative"
            } else if (pixelError > maxReprojectionErrorPixels) {
                reasons += "Pixel reprojection error exceeds configured gate"
            }
        } else {
            reasons += "A measured reprojection error is required"
        }

        val ratio = profile.homographyInlierRatio
        if (ratio == null || !ratio.isFinite() || ratio !in 0.0..1.0) {
            reasons += "A finite inlier ratio in [0,1] is required"
        } else if (ratio < minimumInlierRatio) {
            reasons += "Homography inlier ratio is below configured gate"
        }

        val count = profile.homographyInlierCount
        if (count != null && count < 4) reasons += "At least four inliers are required"

        return CalibrationValidation(reasons.isEmpty(), reasons)
    }

    private fun determinant3x3(h: List<Double>): Double =
        h[0] * (h[4] * h[8] - h[5] * h[7]) -
            h[1] * (h[3] * h[8] - h[5] * h[6]) +
            h[2] * (h[3] * h[7] - h[4] * h[6])
}
