package com.smarttraffic.app.core

import android.content.Context
import com.smarttraffic.app.domain.analysis.TrafficRuleConfig

/** Shared persistence contract between the Rules screen and the analysis runner. */
object TrafficRulePreferences {
    private const val NAME = "traffic_rules"
    private const val LIMIT = "speed_limit"
    private const val CONFIDENCE = "min_confidence"
    private const val ENABLED = "enabled"
    private const val ACTION_CAPTURE = "action_capture"
    private const val ACTION_ALERT = "action_alert"
    private const val ACTION_PRESERVE = "action_preserve"

    fun load(context: Context): TrafficRuleConfig {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return TrafficRuleConfig(
            enabled = p.getBoolean(ENABLED, true),
            speedLimitKmh = p.getInt(LIMIT, 80).coerceAtLeast(1).toDouble(),
            minimumSpeedConfidence = p.getInt(CONFIDENCE, 70).coerceIn(0, 100) / 100f,
            captureOnViolation = p.getBoolean(ACTION_CAPTURE, true),
            createAlertOnViolation = p.getBoolean(ACTION_ALERT, true),
            preserveEvidence = p.getBoolean(ACTION_PRESERVE, true),
        )
    }

    fun save(context: Context, config: TrafficRuleConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(LIMIT, config.speedLimitKmh.toInt().coerceAtLeast(1))
            .putInt(CONFIDENCE, (config.minimumSpeedConfidence * 100f).toInt().coerceIn(0, 100))
            .putBoolean(ENABLED, config.enabled)
            .putBoolean(ACTION_CAPTURE, config.captureOnViolation)
            .putBoolean(ACTION_ALERT, config.createAlertOnViolation)
            .putBoolean(ACTION_PRESERVE, config.preserveEvidence)
            .apply()
    }
}
