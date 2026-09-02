package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** Deterministic metrics used by Local Analysis to compare algorithms and configurations. */
data class SpeedValidationSummary(
    val count: Int,
    val maeKmh: Double,
    val rmseKmh: Double,
    val medianAbsoluteErrorKmh: Double,
    val p90AbsoluteErrorKmh: Double,
    val p95AbsoluteErrorKmh: Double,
    val within5Percent: Double,
    val within10Percent: Double,
    val within20Percent: Double,
)

object ValidationMetrics {
    fun speed(referenceKmh: List<Double>, estimatedKmh: List<Double>): SpeedValidationSummary? {
        if (referenceKmh.size != estimatedKmh.size || referenceKmh.isEmpty()) return null
        val errors = referenceKmh.zip(estimatedKmh).map { (r, e) -> abs(e - r) }
        val mae = errors.average()
        val rmse = sqrt(errors.map { it * it }.average())
        val sorted = errors.sorted()
        fun percentile(p: Double): Double {
            val index = ((sorted.size - 1) * p).coerceIn(0.0, (sorted.size - 1).toDouble())
            val low = index.toInt()
            val high = (low + 1).coerceAtMost(sorted.lastIndex)
            val fraction = index - low
            return sorted[low] + (sorted[high] - sorted[low]) * fraction
        }
        fun within(relative: Double): Double = referenceKmh.zip(estimatedKmh).count { (r, e) ->
            r > 0.0 && abs(e - r) / r <= relative
        }.toDouble() / referenceKmh.size

        return SpeedValidationSummary(
            count = errors.size,
            maeKmh = mae,
            rmseKmh = rmse,
            medianAbsoluteErrorKmh = percentile(0.50),
            p90AbsoluteErrorKmh = percentile(0.90),
            p95AbsoluteErrorKmh = percentile(0.95),
            within5Percent = within(0.05),
            within10Percent = within(0.10),
            within20Percent = within(0.20),
        )
    }
}
