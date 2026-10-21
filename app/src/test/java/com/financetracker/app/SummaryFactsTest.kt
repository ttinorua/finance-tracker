package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.advisor.SummaryFactsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SummaryFactsTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    private var nextId = 1L
    private fun tx(date: Long, amount: Double, category: String, type: TransactionType = TransactionType.EXPENSE) = TransactionWithDetails(
        id = nextId++, amount = amount, type = type, accountId = 1, accountName = "Main",
        categoryId = null, categoryName = category, mainCategoryName = "Food", categoryColorHex = null, date = date, note = category
    )

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `week compared with the weeks before it`() {
        val from = utc(2026, 10, 13)
        val to = from + 7 * day
        val history = (1..8).flatMap { w -> listOf(tx(from - w * 7 * day + day, 400.0, "Groceries"), tx(from - w * 7 * day + 2 * day, 100.0, "Fuel")) }
        val week = listOf(tx(from + day, 300.0, "Groceries"), tx(from + 2 * day, 250.0, "Fuel"), tx(from + 3 * day, 2000.0, "Salary", TransactionType.INCOME))
        val facts = SummaryFactsCalculator.compute(history + week, from, to, now = to - 1, overallBudget = 10_000.0)

        assertEquals(550.0, facts.spent, 0.01)
        assertEquals(500.0, facts.usualSpent!!, 0.01)
        assertEquals(10.0, facts.changePercent!!, 0.01)
        assertEquals(2000.0, facts.income, 0.01)
        assertEquals("Fuel", facts.changes.first().name)
        assertEquals(150.0, facts.changes.first().difference, 0.01)
        assertEquals(listOf("Groceries", "Fuel"), facts.topCategories.map { it.first })
    }

    @Test
    fun `no history means no comparison`() {
        val from = utc(2026, 10, 13)
        val facts = SummaryFactsCalculator.compute(listOf(tx(from + day, 300.0, "Groceries")), from, from + 7 * day, from + 7 * day - 1, null)
        assertNull(facts.usualSpent)
        assertNull(facts.budgetLeft)
        assertEquals(emptyList<Any>(), facts.changes)
    }

    @Test
    fun `budget left counts this month's spending so far`() {
        val now = utc(2026, 10, 20)
        val list = listOf(tx(utc(2026, 10, 2), 1000.0, "Rent"), tx(utc(2026, 9, 28), 5000.0, "Rent"), tx(utc(2026, 10, 18), 500.0, "Food"))
        val facts = SummaryFactsCalculator.compute(list, now - 7 * day, now, now, overallBudget = 4000.0)
        assertEquals(2500.0, facts.budgetLeft!!, 0.01)
        assertEquals(12, facts.daysLeftInMonth)
    }
}
