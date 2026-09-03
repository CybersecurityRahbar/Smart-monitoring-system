package com.smarttraffic.app.domain.analysis

/** Configurable, deterministic traffic-rule policy evaluated only from validated results. */
data class TrafficRuleConfig(
    val enabled: Boolean = false,
    val speedLimitKmh: Double = 80.0,
    val minimumSpeedConfidence: Float = 0.70f,
    val violationMarginKmh: Double = 0.0,
    val captureOnViolation: Boolean = true,
    val createAlertOnViolation: Boolean = true,
    val preserveEvidence: Boolean = true,
) {
    init {
        require(speedLimitKmh > 0.0 && speedLimitKmh.isFinite())
        require(minimumSpeedConfidence in 0f..1f)
        require(violationMarginKmh >= 0.0 && violationMarginKmh.isFinite())
    }
}

data class TrafficEvent(
    val id: String,
    val trackId: Long,
    val timestampMs: Long,
    val type: String,
    val measuredSpeedKmh: Double,
    val thresholdKmh: Double,
    val confidence: Float,
    val calibrationId: String?,
    val calibrationVersion: Int?,
    val detectorModel: String,
    val tracker: String,
    val captureRequested: Boolean,
    val alertRequested: Boolean,
    val evidenceRequested: Boolean,
)

object TrafficRuleEngine {
    fun evaluate(
        tracks: List<Track>,
        speedEstimates: Map<Long, SpeedEstimate>,
        config: TrafficRuleConfig,
        detectorModel: String,
        tracker: String,
        calibration: CalibrationProfile?,
    ): List<TrafficEvent> {
        if (!config.enabled) return emptyList()
        require(calibration != null) { "Traffic rules require a validated physical calibration" }

        return speedEstimates.mapNotNull { (trackId, speed) ->
            if (!speed.kilometersPerHour.isFinite() || speed.confidence < config.minimumSpeedConfidence) return@mapNotNull null
            if (speed.kilometersPerHour <= config.speedLimitKmh + config.violationMarginKmh) return@mapNotNull null
            val track = tracks.firstOrNull { it.id == trackId } ?: return@mapNotNull null
            val timestampMs = track.observations.maxOfOrNull { it.timestampMs } ?: return@mapNotNull null
            TrafficEvent(
                id = "speed-${trackId}-${timestampMs}",
                trackId = trackId,
                timestampMs = timestampMs,
                type = "SPEEDING",
                measuredSpeedKmh = speed.kilometersPerHour,
                thresholdKmh = config.speedLimitKmh,
                confidence = speed.confidence,
                calibrationId = calibration.id,
                calibrationVersion = calibration.version,
                detectorModel = detectorModel,
                tracker = tracker,
                captureRequested = config.captureOnViolation,
                alertRequested = config.createAlertOnViolation,
                evidenceRequested = config.preserveEvidence,
            )
        }.sortedWith(compareBy<TrafficEvent> { it.timestampMs }.thenBy { it.trackId })
    }
}
