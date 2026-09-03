package com.smarttraffic.app.domain.analysis

/**
 * Backend for metric speed estimation. The Kotlin implementation remains the reference path;
 * native implementations can accelerate the same robust estimator without changing pipeline semantics.
 */
fun interface SpeedEstimatorBackend {
    fun estimate(
        observations: List<TrackObservation>,
        minimumSamples: Int,
        minimumDurationMs: Long,
        maxPlausibleSpeedKmh: Double,
    ): SpeedEstimate?
}

val KotlinSpeedEstimatorBackend = SpeedEstimatorBackend { observations, minimumSamples, minimumDurationMs, maxPlausibleSpeedKmh ->
    RobustSpeedEstimator(
        minimumSamples = minimumSamples,
        minimumDurationMs = minimumDurationMs,
        maxPlausibleSpeedKmh = maxPlausibleSpeedKmh,
    ).estimate(observations)
}
