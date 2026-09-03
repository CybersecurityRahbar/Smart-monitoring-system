package com.smarttraffic.app.data.tracking

import android.graphics.Bitmap
import android.graphics.Color
import com.smarttraffic.app.domain.analysis.Detection
import kotlin.math.sqrt

/**
 * Lightweight appearance signature used only as an association cue.
 * This is not a learned Re-ID embedding: it is a deterministic spatial RGB histogram.
 */
object AppearanceSignature {
    private const val GRID = 2
    private const val BINS_PER_CHANNEL = 8
    private const val DIMENSIONS = GRID * GRID * BINS_PER_CHANNEL * 3

    fun fromBitmap(bitmap: Bitmap, detection: Detection): FloatArray? {
        val left = detection.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top = detection.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right = detection.right.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = detection.bottom.coerceIn(0f, bitmap.height.toFloat()).toInt()
        if (right - left < 2 || bottom - top < 2) return null

        val out = FloatArray(DIMENSIONS)
        val width = right - left
        val height = bottom - top
        var samples = 0
        for (y in top until bottom step maxOf(1, height / 32)) {
            for (x in left until right step maxOf(1, width / 32)) {
                val pixel = bitmap.getPixel(x, y)
                val cellX = minOf(GRID - 1, ((x - left) * GRID) / width)
                val cellY = minOf(GRID - 1, ((y - top) * GRID) / height)
                val base = (cellY * GRID + cellX) * BINS_PER_CHANNEL * 3
                out[base + (Color.red(pixel) * BINS_PER_CHANNEL / 256)] += 1f
                out[base + BINS_PER_CHANNEL + (Color.green(pixel) * BINS_PER_CHANNEL / 256)] += 1f
                out[base + BINS_PER_CHANNEL * 2 + (Color.blue(pixel) * BINS_PER_CHANNEL / 256)] += 1f
                samples++
            }
        }
        if (samples == 0) return null
        val norm = sqrt(out.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (!norm.isFinite() || norm <= 0f) return null
        for (i in out.indices) out[i] /= norm
        return out
    }

    fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            if (!x.isFinite() || !y.isFinite()) return 0f
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }
}
