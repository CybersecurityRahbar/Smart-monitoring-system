package com.smarttraffic.app.data.nativecore

import com.smarttraffic.app.domain.analysis.KotlinSpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.SpeedEstimate
import com.smarttraffic.app.domain.analysis.SpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.atan2

/**
 * Native-first metric speed backend. Kotlin remains the reference fallback when the JNI/native
 * runtime cannot return a valid result. The native result now carries the same velocity/residual
 * fields used by the Kotlin estimator so parity can be tested directly.
 */
class NativeFirstSpeedEstimator : SpeedEstimatorBackend {
    override val name: String = "Native C++ (Kotlin fallback)"

    override fun estimate(
        observations: List<TrackObservation>,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
    ): SpeedEstimate? {
        val points = observations
            .asSequence()
            .filter { it.groundPoint != null && it.timestampMs >= 0L }
            .sortedBy { it.timestampMs }
            .mapNotNull { observation ->
                val ground = observation.groundPoint ?: return@mapNotNull null
                if (!ground.xMeters.isFinite() || !ground.yMeters.isFinite()) return@mapNotNull null
                Triple(ground.xMeters, ground.yMeters, observation.timestampMs)
            }
            .distinctBy { point -> Triple(point.third, point.first, point.second) }
            .toList()

        if (points.size < minimumSamples) return null
        val durationMs = points.last().third - points.first().third
        if (durationMs < minimumDurationMs) return null

        return try {
            val result = NativeTrafficCore.estimateRobustSpeed(
                xMeters = points.map { it.first }.toDoubleArray(),
                yMeters = points.map { it.second }.toDoubleArray(),
                timestampsMs = points.map { it.third }.toLongArray(),
                minimumSamples = minimumSamples,
            )
            if (result == null || result.size < 7) {
                return fallback(observations, minimumSamples, minimumDurationMs, maxPlausibleSpeedKmh)
            }

            val metersPerSecond = result[0]
            val confidence = result[1]
            val errorKmh = result[2]
            val sampleCount = result[3].toInt()
            val velocityX = result[4]
            val velocityY = result[5]
            val positionResidual = result[6]
            if (!metersPerSecond.isFinite() || metersPerSecond < 0.0 ||
                metersPerSecond * 3.6 > maxPlausibleSpeedKmh ||
                !confidence.isFinite() || confidence !in 0.0..1.0 ||
                !errorKmh.isFinite() || errorKmh < 0.0 ||
                !velocityX.isFinite() || !velocityY.isFinite() ||
                !positionResidual.isFinite() || positionResidual < 0.0 || sampleCount < 1
            ) {
                return fallback(observations, minimumSamples, minimumDurationMs, maxPlausibleSpeedKmh)
            }

            val direction = if (kotlin.math.hypot(velocityX, velocityY) > 1e-9) {
                Math.toDegrees(atan2(velocityY, velocityX))
            } else null

            SpeedEstimate(
                metersPerSecond = metersPerSecond,
                kilometersPerHour = metersPerSecond * 3.6,
                confidence = confidence.toFloat(),
                sampleCount = sampleCount,
                durationMs = durationMs,
                velocityXMps = velocityX,
                velocityYMps = velocityY,
                directionDegrees = direction,
                positionResidualMeters = positionResidual,
                errorKmh = errorKmh,
            )
        } catch (_: Throwable) {
            fallback(observations, minimumSamples, minimumDurationMs, maxPlausibleSpeedKmh)
        }
    }

    private fun fallback(
        observations: List<TrackObservation>,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
    ): SpeedEstimate? = KotlinSpeedEstimatorBackend.estimate(
        observations,
        minimumSamples,
        minimumDurationMs,
        maxPlausibleSpeedKmh,
    )
}
