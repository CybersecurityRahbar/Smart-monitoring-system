package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Small dependency-free homography estimator for four or more point correspondences.
 * The production/native implementation can later use OpenCV's calibrated/RANSAC path,
 * while this version keeps the geometry layer testable on every Android build.
 */
object HomographyEstimator {
    data class Point(val x: Double, val y: Double)
    data class Estimate(val coefficients: List<Double>, val meanError: Double, val maxError: Double)

    fun estimate(source: List<Point>, target: List<Point>): Estimate {
        require(source.size == target.size && source.size >= 4) { "At least four point pairs are required" }

        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in source.indices) {
            val x = source[i].x
            val y = source[i].y
            val u = target[i].x
            val v = target[i].y
            val rows = arrayOf(
                doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y) to u,
                doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y) to v,
            )
            rows.forEach { (a, b) ->
                for (r in 0 until 8) {
                    atb[r] += a[r] * b
                    for (c in 0 until 8) ata[r][c] += a[r] * a[c]
                }
            }
        }

        val h = solveLinearSystem(ata, atb)
        val coefficients = h.toList() + 1.0
        val errors = source.indices.map { index ->
            val p = project(coefficients, source[index])
            hypot(p.x - target[index].x, p.y - target[index].y)
        }
        return Estimate(coefficients, errors.average(), errors.maxOrNull() ?: 0.0)
    }

    private fun project(h: List<Double>, p: Point): Point {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        require(abs(w) > 1e-12) { "Homography projection is singular at point" }
        return Point(
            (h[0] * p.x + h[1] * p.y + h[2]) / w,
            (h[3] * p.x + h[4] * p.y + h[5]) / w,
        )
    }

    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) a[row][col] else b[row] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            require(abs(m[pivot][col]) > 1e-10) { "Point configuration is degenerate" }
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            val scale = m[col][col]
            for (j in col until n + 1) m[col][j] /= scale
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col]
                if (abs(factor) < 1e-14) continue
                for (j in col until n + 1) m[row][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }
}
