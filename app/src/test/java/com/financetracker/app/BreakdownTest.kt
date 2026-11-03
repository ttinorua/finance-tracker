package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.categoryBreakdown
import com.financetracker.app.util.mainCategoryBreakdown
import org.junit.Assert.assertEquals
import org.junit.Test

class BreakdownTest {
    private var nextId = 1L
    private fun tx(amount: Double, catId: Long?, cat: String?, main: String?, type: TransactionType = TransactionType.EXPENSE) =
        TransactionWithDetails(nextId++, amount, type, 1, "Lunar", catId, cat, main, "#2E7D32", 0, "x")

    private val txs = listOf(
        tx(300.0, 1, "Groceries", "Food"),
        tx(200.0, 1, "Groceries", "Food"),
        tx(120.0, 2, "Take away", "Food"),
        tx(900.0, 3, "Rent", "Home"),
        tx(40.0, null, null, null),
        tx(5000.0, 4, "Pay", "Income", TransactionType.INCOME)
    )

    @Test
    fun `main categories are totalled, biggest first, with their biggest categories named`() {
        val rows = mainCategoryBreakdown(txs)
        assertEquals(listOf("Income", "Home", "Food", "Uncategorized"), rows.map { it.label })
        val food = rows.first { it.label == "Food" }
        assertEquals(620.0, food.expense, 0.0)
        assertEquals(3, food.count)
        assertEquals(listOf("Groceries", "Take away"), food.topCategories)
        assertEquals(5000.0, rows.first().net, 0.0)
        assertEquals(-900.0, rows[1].net, 0.0)
    }

    @Test
    fun `categories inside one main category`() {
        val rows = categoryBreakdown(txs, "Food")
        assertEquals(listOf("Groceries", "Take away"), rows.map { it.label })
        assertEquals(500.0, rows[0].expense, 0.0)
        assertEquals(1L, rows[0].categoryId)
        assertEquals(2, rows[0].count)
        val none = categoryBreakdown(txs, "Uncategorized").single()
        assertEquals(null, none.categoryId)
        assertEquals("Uncategorized", none.label)
    }
}
