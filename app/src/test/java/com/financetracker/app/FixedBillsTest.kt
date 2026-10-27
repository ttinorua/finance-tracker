package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.advisor.FinanceAnalysis
import com.financetracker.app.util.anticipatedRecurringExpenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FixedBillsTest {

    private var nextId = 1L

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day)
    }.timeInMillis

    private fun expense(date: Long, amount: Double, note: String, categoryId: Long = 5L) = TransactionWithDetails(
        nextId++, amount, TransactionType.EXPENSE, 1L, "Checking", categoryId, "Shopping", "Shopping", "#000000", date, note
    )

    private val netflix = listOf(
        expense(utc(2026, 7, 3), 129.0, "Netflix"),
        expense(utc(2026, 8, 3), 129.0, "Netflix"),
        expense(utc(2026, 9, 3), 129.0, "Netflix")
    )

    @Test
    fun `a merchant marked fixed is anticipated even when its category is not fixed`() {
        val now = utc(2026, 10, 1)
        assertTrue(anticipatedRecurringExpenses(netflix, now = now).isEmpty())
        val merchant = FinanceAnalysis.merchantOf("Netflix")
        val items = anticipatedRecurringExpenses(netflix, now = now, fixedMerchants = setOf(merchant))
        assertEquals(1, items.size)
        assertEquals(129.0, items[0].amount, 0.001)
    }

    @Test
    fun `next due and monthly cost of a detected payment`() {
        val payment = FinanceAnalysis.recurringPayments(netflix, utc(2026, 9, 20)).single()
        assertEquals(FinanceAnalysis.Cadence.MONTHLY, payment.cadence)
        assertEquals(utc(2026, 10, 3), payment.nextDue)
        assertEquals(129.0, payment.monthlyCost, 0.001)
        assertEquals(5L, payment.categoryId)
    }

    @Test
    fun `a quarterly bill is spread over three months`() {
        val water = listOf(
            expense(utc(2026, 1, 10), 900.0, "Vandvaerk"),
            expense(utc(2026, 4, 10), 900.0, "Vandvaerk"),
            expense(utc(2026, 7, 10), 900.0, "Vandvaerk")
        )
        val payment = FinanceAnalysis.recurringPayments(water, utc(2026, 8, 1)).single()
        assertEquals(FinanceAnalysis.Cadence.QUARTERLY, payment.cadence)
        assertEquals(300.0, payment.monthlyCost, 0.001)
        assertEquals(utc(2026, 10, 10), payment.nextDue)
    }
}
