package com.smarttraffic.app.data.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import kotlin.math.ceil

data class DetectorBenchmarkResult(
    val modelAsset: String,
    val accelerator: String,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val medianLatencyMs: Double,
    val p95LatencyMs: Double,
    val minLatencyMs: Double,
    val maxLatencyMs: Double,
    val meanLatencyMs: Double,
    val estimatedFps: Double,
    val error: String? = null,
) {
    val successful: Boolean get() = error == null
}

object DetectorBenchmark {
    suspend fun run(
        context: Context,
        modelAsset: String,
        accelerator: Accelerator,
        frame: Bitmap,
        warmupRuns: Int = 5,
        measuredRuns: Int = 30,
    ): DetectorBenchmarkResult {
        require(warmupRuns >= 0) { "warmupRuns must be >= 0" }
        require(measuredRuns > 0) { "measuredRuns must be > 0" }

        return try {
            LiteRtObjectDetector(context, modelAsset, accelerator).use { detector ->
                repeat(warmupRuns) { detector.detect(frame, 0L, it.toLong()) }
                val samples = DoubleArray(measuredRuns)
                repeat(measuredRuns) { index ->
                    detector.detect(frame, index.toLong(), index.toLong())
                    samples[index] = detector.lastInferenceLatencyMs
                }
                val sorted = samples.sorted()
                val median = percentile(sorted, 0.50)
                val p95 = percentile(sorted, 0.95)
                DetectorBenchmarkResult(
                    modelAsset = modelAsset,
                    accelerator = accelerator.name,
                    warmupRuns = warmupRuns,
                    measuredRuns = measuredRuns,
                    medianLatencyMs = median,
                    p95LatencyMs = p95,
                    minLatencyMs = sorted.first(),
                    maxLatencyMs = sorted.last(),
                    meanLatencyMs = samples.average(),
                    estimatedFps = if (median > 0.0) 1000.0 / median else 0.0,
                )
            }
        } catch (t: Throwable) {
            DetectorBenchmarkResult(
                modelAsset = modelAsset,
                accelerator = accelerator.name,
                warmupRuns = warmupRuns,
                measuredRuns = measuredRuns,
                medianLatencyMs = 0.0,
                p95LatencyMs = 0.0,
                minLatencyMs = 0.0,
                maxLatencyMs = 0.0,
                meanLatencyMs = 0.0,
                estimatedFps = 0.0,
                error = t.message ?: t::class.java.simpleName,
            )
        }
    }

    private fun percentile(sorted: List<Double>, probability: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = probability.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = position.toInt()
        val upper = ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }
}
