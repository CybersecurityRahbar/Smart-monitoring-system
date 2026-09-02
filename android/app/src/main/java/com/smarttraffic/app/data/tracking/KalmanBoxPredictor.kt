package com.smarttraffic.app.data.tracking

import kotlin.math.max

/** Scalar constant-velocity Kalman filter used for each bounding-box dimension. */
private class ScalarKalman(
    initialPosition: Double,
    private val processAccelerationStd: Double = 18.0,
    private val measurementStd: Double = 8.0,
) {
    private var position = initialPosition
    private var velocity = 0.0
    private var p00 = 100.0
    private var p01 = 0.0
    private var p10 = 0.0
    private var p11 = 100.0

    fun predict(dtSeconds: Double) {
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0) return
        position += velocity * dtSeconds
        val q = processAccelerationStd * processAccelerationStd
        val dt2 = dtSeconds * dtSeconds
        val dt3 = dt2 * dtSeconds
        val dt4 = dt2 * dt2
        p00 += dtSeconds * (p10 + p01) + dt2 * p11 + q * dt4 / 4.0
        p01 += dtSeconds * p11 + q * dt3 / 2.0
        p10 += dtSeconds * p11 + q * dt3 / 2.0
        p11 += q * dt2
    }

    fun update(measurement: Double) {
        if (!measurement.isFinite()) return
        val r = max(1e-6, measurementStd * measurementStd)
        val innovation = measurement - position
        val s = p00 + r
        if (!s.isFinite() || s <= 0.0) return
        val k0 = p00 / s
        val k1 = p10 / s
        position += k0 * innovation
        velocity += k1 * innovation

        val newP00 = (1.0 - k0) * p00
        val newP01 = (1.0 - k0) * p01
        val newP10 = p10 - k1 * p00
        val newP11 = p11 - k1 * p01
        p00 = max(1e-9, newP00)
        p01 = newP01
        p10 = newP10
        p11 = max(1e-9, newP11)
    }

    fun value(): Double = position
    fun speed(): Double = velocity
}

/** Kalman prediction for a vehicle bounding box center/size. */
internal class KalmanBoxPredictor(initial: ByteTrackBox) {
    private val cx = ScalarKalman(initial.centerX.toDouble())
    private val cy = ScalarKalman(initial.centerY.toDouble())
    private val width = ScalarKalman(initial.width.toDouble(), processAccelerationStd = 12.0, measurementStd = 6.0)
    private val height = ScalarKalman(initial.height.toDouble(), processAccelerationStd = 12.0, measurementStd = 6.0)

    fun predict(dtSeconds: Double): ByteTrackBox {
        cx.predict(dtSeconds)
        cy.predict(dtSeconds)
        width.predict(dtSeconds)
        height.predict(dtSeconds)
        return box()
    }

    fun update(observation: ByteTrackBox) {
        cx.update(observation.centerX.toDouble())
        cy.update(observation.centerY.toDouble())
        width.update(observation.width.toDouble())
        height.update(observation.height.toDouble())
    }

    fun box(): ByteTrackBox {
        val w = max(1f, width.value().toFloat())
        val h = max(1f, height.value().toFloat())
        val x = cx.value().toFloat()
        val y = cy.value().toFloat()
        return ByteTrackBox(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
    }
}

internal data class ByteTrackBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    constructor(detection: com.smarttraffic.app.domain.analysis.Detection) : this(
        detection.left, detection.top, detection.right, detection.bottom,
    )

    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}
