package com.smarttraffic.app.domain.analysis

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Numerically safer homography estimation with normalized coordinates and an optional
 * deterministic RANSAC stage for rejecting bad calibration correspondences.
 */
object HomographyEstimator {
    data class Point(val x: Double, val y: Double)

    data class Estimate(
        val coefficients: List<Double>,
        val meanError: Double,
        val maxError: Double,
        val inlierCount: Int = 0,
        val inlierRatio: Double = 0.0,
    )

    fun estimate(source: List<Point>, target: List<Point>): Estimate {
        validatePairs(source, target)
        val coefficients = normalizedDlt(source, target)
        val errors = source.indices.map { index -> reprojectionError(coefficients, source[index], target[index]) }
        return Estimate(coefficients, errors.average(), errors.maxOrNull() ?: 0.0, source.size, 1.0)
    }

    /**
     * Four-point hypotheses are sampled deterministically, scored by forward reprojection
     * error in target units, then refined from all selected inliers.
     */
    fun estimateRansac(
        source: List<Point>,
        target: List<Point>,
        reprojectionThreshold: Double = 0.25,
        iterations: Int = 1000,
        minimumInliers: Int = 4,
        seed: Int = 0x534D5452,
    ): Estimate {
        validatePairs(source, target)
        require(reprojectionThreshold.isFinite() && reprojectionThreshold > 0.0)
        require(iterations > 0)
        require(minimumInliers in 4..source.size)

        val rng = Random(seed)
        var bestIndices: List<Int>? = null
        var bestError = Double.POSITIVE_INFINITY

        repeat(iterations) {
            val sample = sampleFour(source.indices.toList(), rng)
            if (!nonDegenerate(sample.map(source::get)) || !nonDegenerate(sample.map(target::get))) return@repeat
            val h = runCatching {
                normalizedDlt(sample.map(source::get), sample.map(target::get))
            }.getOrNull() ?: return@repeat

            val errors = source.indices.map { reprojectionError(h, source[it], target[it]) }
            val inliers = errors.withIndex()
                .filter { it.value <= reprojectionThreshold }
                .map { it.index }
            if (inliers.size < minimumInliers) return@repeat
            val mean = inliers.map { errors[it] }.average()
            if (inliers.size > (bestIndices?.size ?: 0) ||
                (inliers.size == (bestIndices?.size ?: 0) && mean < bestError)) {
                bestIndices = inliers
                bestError = mean
            }
        }

        val selected = bestIndices ?: throw IllegalArgumentException("RANSAC could not find a valid homography")
        val refined = normalizedDlt(selected.map(source::get), selected.map(target::get))
        val errors = source.indices.map { reprojectionError(refined, source[it], target[it]) }
        val inlierErrors = selected.map { errors[it] }
        return Estimate(
            coefficients = refined,
            meanError = inlierErrors.average(),
            maxError = inlierErrors.maxOrNull() ?: 0.0,
            inlierCount = selected.size,
            inlierRatio = selected.size.toDouble() / source.size,
        )
    }

    private fun validatePairs(source: List<Point>, target: List<Point>) {
        require(source.size == target.size && source.size >= 4) { "At least four point pairs are required" }
        require(source.zip(target).all { (s, t) -> s.isFinite() && t.isFinite() }) {
            "Point correspondences must be finite"
        }
        require(nonDegenerate(source) && nonDegenerate(target)) { "Point configuration is degenerate" }
    }

    private fun normalizedDlt(source: List<Point>, target: List<Point>): List<Double> {
        val ns = normalizePoints(source)
        val nt = normalizePoints(target)
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in source.indices) {
            val x = ns.points[i].x
            val y = ns.points[i].y
            val u = nt.points[i].x
            val v = nt.points[i].y
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
        val hn = solveLinearSystem(ata, atb).toList() + 1.0
        val denormalized = multiply3x3(multiply3x3(inverse3x3(ns.transform), hn), nt.transform)
        val scale = denormalized[8]
        require(scale.isFinite() && abs(scale) > 1e-12) { "Invalid homography scale" }
        return denormalized.map { it / scale }
    }

    private data class Normalized(val points: List<Point>, val transform: List<Double>)

    private fun normalizePoints(points: List<Point>): Normalized {
        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        val meanDistance = points.map { hypot(it.x - cx, it.y - cy) }.average()
        require(meanDistance.isFinite() && meanDistance > 1e-12)
        val s = sqrt(2.0) / meanDistance
        return Normalized(
            points.map { Point((it.x - cx) * s, (it.y - cy) * s) },
            listOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0),
        )
    }

    private fun multiply3x3(a: List<Double>, b: List<Double>): List<Double> = List(9) { index ->
        val r = index / 3
        val c = index % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[c + 3] + a[r * 3 + 2] * b[c + 6]
    }

    private fun inverse3x3(h: List<Double>): List<Double> {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val i = h[7]; val j = h[8]
        val c00 = e * j - f * i
        val c01 = -(d * j - f * g)
        val c02 = d * i - e * g
        val c10 = -(b * j - c * i)
        val c11 = a * j - c * g
        val c12 = -(a * i - b * g)
        val c20 = b * f - c * e
        val c21 = -(a * f - c * d)
        val c22 = a * e - b * d
        val det = a * c00 + b * c01 + c * c02
        require(det.isFinite() && abs(det) > 1e-12) { "Homography transform is singular" }
        val q = 1.0 / det
        return listOf(
            c00 * q, c10 * q, c20 * q,
            c01 * q, c11 * q, c21 * q,
            c02 * q, c12 * q, c22 * q,
        )
    }

    private fun project(h: List<Double>, p: Point): Point {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        require(w.isFinite() && abs(w) > 1e-12) { "Homography projection is singular at point" }
        return Point(
            (h[0] * p.x + h[1] * p.y + h[2]) / w,
            (h[3] * p.x + h[4] * p.y + h[5]) / w,
        )
    }

    private fun reprojectionError(h: List<Double>, source: Point, target: Point): Double {
        val projected = project(h, source)
        return hypot(projected.x - target.x, projected.y - target.y)
    }

    private fun sampleFour(indices: List<Int>, rng: Random): List<Int> {
        val chosen = LinkedHashSet<Int>()
        while (chosen.size < 4) chosen += indices[rng.nextInt(indices.size)]
        return chosen.toList()
    }

    private fun nonDegenerate(points: List<Point>): Boolean {
        if (points.size < 4) return false
        var maxCross = 0.0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                for (k in j + 1 until points.size) {
                    val abx = points[j].x - points[i].x
                    val aby = points[j].y - points[i].y
                    val acx = points[k].x - points[i].x
                    val acy = points[k].y - points[i].y
                    maxCross = maxOf(maxCross, abs(abx * acy - aby * acx))
                }
            }
        }
        return maxCross > 1e-9
    }

    private fun Point.isFinite(): Boolean = x.isFinite() && y.isFinite()

    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) a[row][col] else b[row] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            require(abs(m[pivot][col]) > 1e-12) { "Point configuration is degenerate" }
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
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
