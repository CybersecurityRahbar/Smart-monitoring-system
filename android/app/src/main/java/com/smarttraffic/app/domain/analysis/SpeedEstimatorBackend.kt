package com.smarttraffic.app.domain.analysis

/**
 * Backend for metric speed estimation. The Kotlin implementation remains the reference path;
 * native implementations can accelerate the same robust estimator without changing pipeline semantics.
 */
interface SpeedEstimatorBackend {
    val name: String

    fun estimate(
        observations: List<TrackObservation>,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
    ): SpeedEstimate?
}

object KotlinSpeedEstimatorBackend : SpeedEstimatorBackend {
    override val name: String = "Kotlin reference"

    override fun estimate(
        observations: List<TrackObservation>,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
    ): SpeedEstimate? = RobustSpeedEstimator(
        minimumSamples = minimumSamples,
        minimumDurationMs = minimumDurationMs,
        maxPlausibleSpeedKmh = maxPlausibleSpeedKmh,
    ).estimate(observations)
}
