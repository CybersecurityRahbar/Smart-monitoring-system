package com.smarttraffic.app.domain.analysis

import kotlin.math.hypot
import kotlin.math.sqrt

/** A 2D image/template correspondence used for dynamic vehicle homography estimation. */
data class VehicleTemplateCorrespondence(
    val keypointName: String,
    val imageX: Double,
    val imageY: Double,
    val templateX: Double,
    val templateY: Double,
    val confidence: Double = 1.0,
) {
    init {
        require(imageX.isFinite() && imageY.isFinite())
        require(templateX.isFinite() && templateY.isFinite())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class HomographyProjection(val x: Double, val y: Double, val denominator: Double)

data class HomographyFit(
    val matrix: DoubleArray,
    val inlierMask: BooleanArray,
    val medianReprojectionError: Double,
    val maxReprojectionError: Double,
) {
    init {
        require(matrix.size == 9)
        require(inlierMask.isNotEmpty())
        require(medianReprojectionError.isFinite() && medianReprojectionError >= 0.0)
        require(maxReprojectionError.isFinite() && maxReprojectionError >= 0.0)
    }

    fun project(x: Double, y: Double): HomographyProjection =
        VehicleKeypointHomography.projectPoint(matrix, x, y)
}

/**
 * Lightweight normalized DLT + deterministic RANSAC implementation for 2D projective geometry.
 *
 * This class intentionally knows nothing about the 36-point semantic ordering. It consumes
 * correspondence pairs only, allowing the final paper-specific template index map to be plugged
 * in after it is independently verified.
 */
object VehicleKeypointHomography {
    fun estimate(
        correspondences: List<VehicleTemplateCorrespondence>,
        reprojectionThreshold: Double = 3.0,
        maxIterations: Int = 120,
        minimumInliers: Int = 4,
    ): HomographyFit? {
        val usable = correspondences
            .filter { it.confidence > 0.0 }
            .distinctBy { it.keypointName }
        require(reprojectionThreshold > 0.0)
        require(maxIterations >= 1)
        require(minimumInliers >= 4)
        if (usable.size < 4) return null

        var best: HomographyFit? = null
        val combinations = deterministicFourTuples(usable.size, maxIterations)
        for (indices in combinations) {
            val candidate = fitDlt(indices.map { usable[it] }) ?: continue
            val errors = usable.map { correspondence ->
                val projected = candidate.project(correspondence.imageX, correspondence.imageY)
                if (!projected.denominator.isFinite() || kotlin.math.abs(projected.denominator) < 1e-9) {
                    Double.POSITIVE_INFINITY
                } else {
                    hypot(projected.x - correspondence.templateX, projected.y - correspondence.templateY)
                }
            }
            val inliers = BooleanArray(errors.size) { errors[it] <= reprojectionThreshold }
            val inlierCount = inliers.count { it }
            if (inlierCount < minimumInliers) continue

            val refined = fitDlt(usable.indices.filter { inliers[it] }.map { usable[it] }) ?: continue
            val refinedErrors = usable.map { correspondence ->
                val projected = refinedProjection(refined, correspondence.imageX, correspondence.imageY)
                if (!projected.denominator.isFinite() || kotlin.math.abs(projected.denominator) < 1e-9) {
                    Double.POSITIVE_INFINITY
                } else {
                    hypot(projected.x - correspondence.templateX, projected.y - correspondence.templateY)
                }
            }
            val refinedInliers = BooleanArray(refinedErrors.size) { refinedErrors[it] <= reprojectionThreshold }
            val refinedCount = refinedInliers.count { it }
            if (refinedCount < minimumInliers) continue

            val inlierErrors = refinedErrors.filterIndexed { index, _ -> refinedInliers[index] }
            val fit = HomographyFit(
                matrix = refined,
                inlierMask = refinedInliers,
                medianReprojectionError = median(inlierErrors),
                maxReprojectionError = inlierErrors.maxOrNull() ?: Double.POSITIVE_INFINITY,
            )
            if (isBetter(fit, best)) best = fit
        }
        return best
    }

    fun projectPoint(matrix: DoubleArray, x: Double, y: Double): HomographyProjection {
        require(matrix.size == 9)
        val denominator = matrix[6] * x + matrix[7] * y + matrix[8]
        if (kotlin.math.abs(denominator) < 1e-12) {
            return HomographyProjection(Double.NaN, Double.NaN, denominator)
        }
        val projectedX = (matrix[0] * x + matrix[1] * y + matrix[2]) / denominator
        val projectedY = (matrix[3] * x + matrix[4] * y + matrix[5]) / denominator
        return HomographyProjection(projectedX, projectedY, denominator)
    }

    private fun refinedProjection(matrix: DoubleArray, x: Double, y: Double): HomographyProjection =
        projectPoint(matrix, x, y)

    private fun fitDlt(points: List<VehicleTemplateCorrespondence>): DoubleArray? {
        if (points.size < 4 || areDegenerate(points)) return null
        val imageNorm = normalize(points.map { Point2(it.imageX, it.imageY) }) ?: return null
        val templateNorm = normalize(points.map { Point2(it.templateX, it.templateY) }) ?: return null

        val normalMatrix = Array(8) { DoubleArray(8) }
        val normalVector = DoubleArray(8)
        for (index in points.indices) {
            val x = imageNorm.points[index].x
            val y = imageNorm.points[index].y
            val u = templateNorm.points[index].x
            val v = templateNorm.points[index].y
            val rows = arrayOf(
                doubleArrayOf(-x, -y, -1.0, 0.0, 0.0, 0.0, x * u, y * u),
                doubleArrayOf(0.0, 0.0, 0.0, -x, -y, -1.0, x * v, y * v),
            )
            val targets = doubleArrayOf(-u, -v)
            for (r in 0..1) {
                for (c in 0 until 8) {
                    normalVector[c] += rows[r][c] * targets[r]
                    for (d in 0 until 8) normalMatrix[c][d] += rows[r][c] * rows[r][d]
                }
            }
        }

        val h = solveLinearSystem(normalMatrix, normalVector) ?: return null
        val normalizedH = doubleArrayOf(
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1.0,
        )
        val denormalized = multiply3x3(
            templateNorm.inverse,
            multiply3x3(normalizedH, imageNorm.transform),
        )
        val scale = denormalized[8]
        if (!scale.isFinite() || kotlin.math.abs(scale) < 1e-12) return null
        return DoubleArray(9) { denormalized[it] / scale }
    }

    private data class Point2(val x: Double, val y: Double)
    private data class NormalizedPoints(
        val points: List<Point2>,
        val transform: DoubleArray,
        val inverse: DoubleArray,
    )

    private fun normalize(points: List<Point2>): NormalizedPoints? {
        if (points.isEmpty()) return null
        val meanX = points.map { it.x }.average()
        val meanY = points.map { it.y }.average()
        val meanDistance = points.map { hypot(it.x - meanX, it.y - meanY) }.average()
        if (!meanDistance.isFinite() || meanDistance < 1e-9) return null
        val scale = sqrt(2.0) / meanDistance
        val transform = doubleArrayOf(
            scale, 0.0, -scale * meanX,
            0.0, scale, -scale * meanY,
            0.0, 0.0, 1.0,
        )
        val inverseScale = 1.0 / scale
        val inverse = doubleArrayOf(
            inverseScale, 0.0, meanX,
            0.0, inverseScale, meanY,
            0.0, 0.0, 1.0,
        )
        val normalized = points.map { Point2(scale * (it.x - meanX), scale * (it.y - meanY)) }
        return NormalizedPoints(normalized, transform, inverse)
    }

    private fun areDegenerate(points: List<VehicleTemplateCorrespondence>): Boolean {
        fun area(a: Point2, b: Point2, c: Point2): Double =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        val image = points.map { Point2(it.imageX, it.imageY) }
        val template = points.map { Point2(it.templateX, it.templateY) }
        var maxImageArea = 0.0
        var maxTemplateArea = 0.0
        for (i in image.indices) for (j in i + 1 until image.size) for (k in j + 1 until image.size) {
            maxImageArea = maxOf(maxImageArea, kotlin.math.abs(area(image[i], image[j], image[k])))
            maxTemplateArea = maxOf(maxTemplateArea, kotlin.math.abs(area(template[i], template[j], template[k])))
        }
        return maxImageArea < 1e-6 || maxTemplateArea < 1e-6
    }

    private fun solveLinearSystem(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val n = vector.size
        val a = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) matrix[row][col] else vector[row] } }
        for (pivot in 0 until n) {
            var bestRow = pivot
            var bestAbs = kotlin.math.abs(a[pivot][pivot])
            for (row in pivot + 1 until n) {
                val value = kotlin.math.abs(a[row][pivot])
                if (value > bestAbs) {
                    bestAbs = value
                    bestRow = row
                }
            }
            if (!bestAbs.isFinite() || bestAbs < 1e-12) return null
            if (bestRow != pivot) {
                val temp = a[pivot]
                a[pivot] = a[bestRow]
                a[bestRow] = temp
            }
            val pivotValue = a[pivot][pivot]
            for (column in pivot until n + 1) a[pivot][column] /= pivotValue
            for (row in 0 until n) {
                if (row == pivot) continue
                val factor = a[row][pivot]
                if (factor == 0.0) continue
                for (column in pivot until n + 1) a[row][column] -= factor * a[pivot][column]
            }
        }
        return DoubleArray(n) { a[it][n] }
    }

    private fun multiply3x3(left: DoubleArray, right: DoubleArray): DoubleArray {
        require(left.size == 9 && right.size == 9)
        return DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            left[row * 3] * right[column] +
                left[row * 3 + 1] * right[3 + column] +
                left[row * 3 + 2] * right[6 + column]
        }
    }

    private fun deterministicFourTuples(size: Int, maxIterations: Int): Sequence<IntArray> = sequence {
        var produced = 0
        for (a in 0 until size - 3) {
            for (b in a + 1 until size - 2) {
                for (c in b + 1 until size - 1) {
                    for (d in c + 1 until size) {
                        yield(intArrayOf(a, b, c, d))
                        produced++
                        if (produced >= maxIterations) return@sequence
                    }
                }
            }
        }
    }

    private fun isBetter(candidate: HomographyFit, current: HomographyFit?): Boolean {
        if (current == null) return true
        val candidateCount = candidate.inlierMask.count { it }
        val currentCount = current.inlierMask.count { it }
        return candidateCount > currentCount ||
            (candidateCount == currentCount && candidate.medianReprojectionError < current.medianReprojectionError)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) * 0.5 else sorted[middle]
    }
}
