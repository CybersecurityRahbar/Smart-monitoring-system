package com.smarttraffic.app.data.tracking

import kotlin.math.min

/**
 * Deterministic minimum-cost linear assignment (Hungarian algorithm).
 * Returns (rowIndex, columnIndex) pairs for the optimal rectangular assignment.
 */
internal object LinearAssignment {
    fun solve(cost: Array<DoubleArray>): List<Pair<Int, Int>> {
        if (cost.isEmpty() || cost[0].isEmpty()) return emptyList()
        require(cost.all { it.size == cost[0].size }) { "Cost matrix must be rectangular" }
        val rows = cost.size
        val cols = cost[0].size
        return if (rows <= cols) solveRowsToColumns(cost)
        else solveRowsToColumns(transpose(cost)).map { it.second to it.first }
    }

    private fun solveRowsToColumns(cost: Array<DoubleArray>): List<Pair<Int, Int>> {
        val n = cost.size
        val m = cost[0].size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                require(delta.isFinite()) { "Assignment matrix contains only non-finite candidate costs" }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = ArrayList<Pair<Int, Int>>(min(n, m))
        for (j in 1..m) {
            if (p[j] != 0) result += (p[j] - 1) to (j - 1)
        }
        return result
    }

    private fun transpose(input: Array<DoubleArray>): Array<DoubleArray> {
        val rows = input.size
        val cols = input[0].size
        return Array(cols) { c -> DoubleArray(rows) { r -> input[r][c] } }
    }
}
