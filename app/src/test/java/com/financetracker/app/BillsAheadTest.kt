package com.financetracker.app

import com.financetracker.app.util.BillsAhead
import com.financetracker.app.util.UpcomingBill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BillsAheadTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day)
    }.timeInMillis

    private val now = utc(2026, 11, 2)
    private val bills = listOf(
        UpcomingBill("Rent", 8000.0, utc(2026, 11, 1), 1),
        UpcomingBill("Solrød Kommune", 18330.0, utc(2027, 1, 5), 6),
        UpcomingBill("Home insurance", 4800.0, utc(2027, 3, 1), 12)
    )

    @Test
    fun `bills land in their own months and the lowest point is found`() {
        val f = BillsAhead.forecast(bills, 20000.0, 9000.0, now, 6)
        assertEquals(listOf(8000.0, 8000.0, 26330.0, 8000.0, 12800.0, 8000.0), f.months.map { it.out })
        assertEquals(4, f.lowestIndex) // March: insurance after January's big bill
        assertEquals(20000.0 + 3 * 9000 - 8000 * 2 - 26330, f.months[2].endBalance, 0.01)
        assertEquals(20000.0 + 6 * 9000 - f.totalOut, f.leftOver, 0.01)
    }

    @Test
    fun `runway is how long the balance lasts with nothing coming in`() {
        assertEquals(2, BillsAhead.forecast(bills, 20000.0, 9000.0, now, 6).runwayMonths)
        assertNull(BillsAhead.forecast(bills, 1_000_000.0, 0.0, now, 6).runwayMonths)
    }

    @Test
    fun `needed transfer keeps every month above the buffer`() {
        val f = BillsAhead.forecast(bills, 20000.0, 0.0, now, 6, buffer = 5000.0)
        val check = BillsAhead.forecast(bills, 20000.0, f.neededPerMonth, now, 6)
        check.months.forEach { assert(it.endBalance >= 5000.0 - 0.01) }
        val tooLittle = BillsAhead.forecast(bills, 20000.0, f.neededPerMonth - 100, now, 6)
        assert(tooLittle.months.any { it.endBalance < 5000.0 })
    }

    @Test
    fun `a what-if bill is added to its month, and overdue dates roll forward`() {
        val f = BillsAhead.forecast(bills, 20000.0, 9000.0, now, 6, extra = 4000.0 to 4)
        assertEquals(16800.0, f.months[4].out, 0.01)
        val late = BillsAhead.forecast(listOf(UpcomingBill("Water", 900.0, utc(2026, 7, 10), 3)), 0.0, 0.0, now, 3)
        // July → October → January: next due in the 3-month window is January.
        assertEquals(listOf(0.0, 0.0, 900.0), late.months.map { it.out })
    }

    @Test
    fun `how long the balance lasts and months to a goal`() {
        assertEquals(1000.0 + 200.0, BillsAhead.monthlyAverage(listOf(UpcomingBill("A", 1000.0, now, 1), UpcomingBill("B", 2400.0, now, 12))), 0.01)
        assertEquals(4.0, BillsAhead.lastsMonths(4800.0, 1200.0), 0.01)
        assertEquals(Double.POSITIVE_INFINITY, BillsAhead.lastsMonths(4800.0, 0.0), 0.0)
        assertEquals(0, BillsAhead.monthsToGoal(8000.0, 7200.0, 100.0))
        assertEquals(3, BillsAhead.monthsToGoal(5000.0, 7200.0, 800.0))
        assertEquals(null, BillsAhead.monthsToGoal(5000.0, 7200.0, 0.0))
    }

    @Test
    fun `this month only gets what is left of the transfers`() {
        val f = BillsAhead.forecast(bills, 20000.0, 9000.0, now, 3, firstMonthIn = 0.0)
        assertEquals(0.0, f.months[0].moneyIn, 0.01)
        assertEquals(9000.0, f.months[1].moneyIn, 0.01)
        assertEquals(18000.0, f.totalIn, 0.01)
    }
}
