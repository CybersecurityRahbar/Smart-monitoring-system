package com.smarttraffic.app.data.nativecore

import com.smarttraffic.app.domain.analysis.KotlinSpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.SpeedEstimate
import com.smarttraffic.app.domain.analysis.SpeedEstimatorBackend
import com.smarttraffic.app.domain.analysis.TrackObservation
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Native-first metric speed backend. The Kotlin estimator is the reference fallback so a native
 * runtime/linker problem cannot turn a valid analysis into an untrusted zero-speed result.
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
            if (result == null || result.size < 4) return fallback(
                observations,
                minimumSamples,
                minimumDurationMs,
                maxPlausibleSpeedKmh,
            )

            val metersPerSecond = result[0]
            val confidence = result[1]
            val errorKmh = result[2]
            val sampleCount = result[3].toInt()
            if (!metersPerSecond.isFinite() || metersPerSecond < 0.0 ||
                metersPerSecond * 3.6 > maxPlausibleSpeedKmh ||
                !confidence.isFinite() || !errorKmh.isFinite() || sampleCount < 1
            ) {
                return fallback(observations, minimumSamples, minimumDurationMs, maxPlausibleSpeedKmh)
            }

            val dx = points.last().first - points.first().first
            val dy = points.last().second - points.first().second
            val direction = if (hypot(dx, dy) > 1e-9) {
                Math.toDegrees(atan2(dy, dx))
            } else null

            SpeedEstimate(
                metersPerSecond = metersPerSecond,
                kilometersPerHour = metersPerSecond * 3.6,
                confidence = confidence.coerceIn(0.0, 1.0).toFloat(),
                sampleCount = sampleCount,
                durationMs = durationMs,
                velocityXMps = null,
                velocityYMps = null,
                directionDegrees = direction,
                positionResidualMeters = null,
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
