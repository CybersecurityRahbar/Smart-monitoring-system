package com.smarttraffic.app.data.nativecore

import com.smarttraffic.app.domain.analysis.CalibrationProfile
import com.smarttraffic.app.domain.analysis.GroundPoint
import com.smarttraffic.app.domain.analysis.GroundProjector

/** JNI-backed homography projection used by the production Android analysis path. */
class NativeGroundProjector : GroundProjector {
    override fun project(calibration: CalibrationProfile, pixelX: Double, pixelY: Double): GroundPoint {
        val projected = NativeTrafficCore.projectHomography(calibration.homography.toDoubleArray(), pixelX, pixelY)
            ?: error("Native homography projection returned no result")
        require(projected.size == 2) { "Native homography projection returned ${projected.size} values" }
        val x = projected[0]
        val y = projected[1]
        require(x.isFinite() && y.isFinite()) { "Native homography projection returned non-finite coordinates" }
        return GroundPoint(
            xMeters = x,
            yMeters = y,
            sourcePixelX = pixelX,
            sourcePixelY = pixelY,
        )
    }
}
