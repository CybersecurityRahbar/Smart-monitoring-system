package com.smarttraffic.app.domain.analysis

import kotlin.math.exp
import kotlin.math.ln

/**
 * Temporal plate consensus for noisy per-frame OCR.
 *
 * Readings are grouped by vehicle track and normalized plate text. Support is accumulated
 * with exponential recency weighting based on actual presentation timestamps, so a single
 * high-confidence OCR frame cannot automatically dominate a consistent sequence.
 */
object PlateConsensus {
    fun resolve(
        readings: List<PlateReading>,
        halfLifeMs: Long = 1200L,
        minimumSupport: Double = 0.20,
    ): List<PlateReading> {
        require(halfLifeMs > 0L)
        require(minimumSupport in 0.0..1.0)
        if (readings.isEmpty()) return emptyList()

        val output = ArrayList<PlateReading>()
        readings.groupBy { it.trackId }.forEach { (trackId, group) ->
            if (trackId == null) {
                output += group.maxWithOrNull(
                    compareBy<PlateReading> { it.confidence }.thenBy { it.timestampMs },
                )!!
                return@forEach
            }

            val latestTimestamp = group.maxOf { it.timestampMs }
            val normalized = group.groupBy { normalize(it.text) }
            val totalWeight = group.sumOf {
                recencyWeight(it.timestampMs, latestTimestamp, halfLifeMs) * it.confidence.coerceIn(0f, 1f)
            }
            val best = normalized.map { (text, candidates) ->
                val support = candidates.sumOf {
                    recencyWeight(it.timestampMs, latestTimestamp, halfLifeMs) * it.confidence.coerceIn(0f, 1f)
                }
                val peak = candidates.maxWithOrNull(
                    compareBy<PlateReading> { it.confidence }
                        .thenBy { it.timestampMs }
                        .thenBy { it.frameIndex },
                )!!
                Candidate(text, support, peak)
            }.maxWithOrNull(
                compareBy<Candidate> { it.support }.thenBy { it.peak.timestampMs },
            ) ?: return@forEach

            if (totalWeight <= 0.0 || best.support / totalWeight < minimumSupport) return@forEach
            output += best.peak.copy(text = best.text)
        }

        return output.sortedWith(
            compareBy<PlateReading> { it.trackId ?: Long.MAX_VALUE }
                .thenByDescending { it.timestampMs }
                .thenByDescending { it.frameIndex },
        )
    }

    private data class Candidate(
        val text: String,
        val support: Double,
        val peak: PlateReading,
    )

    private fun recencyWeight(timestampMs: Long, latestTimestampMs: Long, halfLifeMs: Long): Double {
        val ageMs = (latestTimestampMs - timestampMs).coerceAtLeast(0L)
        return exp(-ln(2.0) * ageMs / halfLifeMs.toDouble())
    }

    private fun normalize(text: String): String = text.uppercase().filter(Char::isLetterOrDigit)
}
