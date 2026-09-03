package com.smarttraffic.app.domain.analysis

/**
 * Converts an image-space vehicle contact point into the calibrated road plane.
 * Implementations may use the Kotlin reference math or the native C++ backend.
 */
fun interface GroundProjector {
    fun project(calibration: CalibrationProfile, pixelX: Double, pixelY: Double): GroundPoint
}

val KotlinGroundProjector = GroundProjector { calibration, pixelX, pixelY ->
    HomographyProjector(calibration.homography).project(pixelX, pixelY)
}
