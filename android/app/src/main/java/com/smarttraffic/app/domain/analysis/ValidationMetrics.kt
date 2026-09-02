package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** Deterministic metrics used by Local Analysis to compare algorithms and configurations. */
data class SpeedValidationSummary(
    /** Number of finite reference/estimate pairs included in absolute-error metrics. */
    val count: Int,
    val maeKmh: Double,
    val rmseKmh: Double,
    val medianAbsoluteErrorKmh: Double,
    val p90AbsoluteErrorKmh: Double,
    val p95AbsoluteErrorKmh: Double,
    /** Fraction of valid pairs with a strictly positive reference inside the tolerance. */
    val within5Percent: Double,
    val within10Percent: Double,
    val within20Percent: Double,
    /** Number of input pairs excluded because either value was non-finite. */
    val excludedCount: Int = 0,
    /** Number of finite pairs with a strictly positive reference speed. */
    val positiveReferenceCount: Int = 0,
)

object ValidationMetrics {
    fun speed(referenceKmh: List<Double>, estimatedKmh: List<Double>): SpeedValidationSummary? {
        if (referenceKmh.size != estimatedKmh.size || referenceKmh.isEmpty()) return null

        val validPairs = referenceKmh.zip(estimatedKmh).filter { (reference, estimated) ->
            reference.isFinite() && estimated.isFinite()
        }
        if (validPairs.isEmpty()) return null

        val errors = validPairs.map { (reference, estimated) -> abs(estimated - reference) }
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

        val positiveReferencePairs = validPairs.filter { (reference, _) -> reference > 0.0 }
        fun within(relative: Double): Double {
            if (positiveReferencePairs.isEmpty()) return Double.NaN
            return positiveReferencePairs.count { (reference, estimated) ->
                abs(estimated - reference) / reference <= relative
            }.toDouble() / positiveReferencePairs.size
        }

        return SpeedValidationSummary(
            count = validPairs.size,
            maeKmh = mae,
            rmseKmh = rmse,
            medianAbsoluteErrorKmh = percentile(0.50),
            p90AbsoluteErrorKmh = percentile(0.90),
            p95AbsoluteErrorKmh = percentile(0.95),
            within5Percent = within(0.05),
            within10Percent = within(0.10),
            within20Percent = within(0.20),
            excludedCount = referenceKmh.size - validPairs.size,
            positiveReferenceCount = positiveReferencePairs.size,
        )
    }
}
