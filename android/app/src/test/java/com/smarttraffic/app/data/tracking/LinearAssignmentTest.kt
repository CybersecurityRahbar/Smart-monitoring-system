package com.smarttraffic.app.data.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class LinearAssignmentTest {
    @Test
    fun findsGlobalMinimumRatherThanGreedyLocalMatch() {
        val costs = arrayOf(
            doubleArrayOf(0.10, 0.11),
            doubleArrayOf(0.09, 0.90),
        )

        val matches = LinearAssignment.solve(costs).toSet()

        assertEquals(setOf(0 to 1, 1 to 0), matches)
    }

    @Test
    fun handlesMoreRowsThanColumns() {
        val costs = arrayOf(
            doubleArrayOf(0.10),
            doubleArrayOf(0.90),
        )

        val matches = LinearAssignment.solve(costs)

        assertEquals(1, matches.size)
        assertEquals(0 to 0, matches.single())
    }
}
