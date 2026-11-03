package com.financetracker.app

import com.financetracker.app.util.dailyBalances
import com.financetracker.app.util.percentChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceTrendTest {
    private val day = 86_400_000L
    private val today = 100 * day

    @Test
    fun `balances are worked back from today's figure`() {
        val movements = listOf(
            today to -200.0, // today: spent 200
            today - day to 1_000.0, // yesterday: salary
            today - 2 * day to -50.0
        )
        val points = dailyBalances(5_000.0, movements, today, days = 4)
        // 3 days ago, end of day: before the -50, the salary and the -200.
        assertEquals(listOf(4_250.0, 4_200.0, 5_200.0, 5_000.0), points)
    }

    @Test
    fun `future-dated transactions are undone from yesterday onwards`() {
        val points = dailyBalances(900.0, listOf(today + 3 * day to -100.0), today, days = 2)
        assertEquals(listOf(1_000.0, 900.0), points)
    }

    @Test
    fun `percent change`() {
        assertEquals(25.0, percentChange(listOf(800.0, 900.0, 1_000.0))!!, 1e-9)
        assertEquals(-50.0, percentChange(listOf(-200.0, -300.0))!!, 1e-9)
        assertNull(percentChange(listOf(0.0, 500.0)))
    }
}
