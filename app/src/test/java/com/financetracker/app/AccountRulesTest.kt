package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.findLikelyTransfers
import com.financetracker.app.util.pickMainAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private var nextId = 1L

    private fun tx(
        account: Long,
        amount: Double,
        type: TransactionType,
        daysAgo: Int = 1,
        main: String? = "Food",
        category: String? = "Groceries"
    ) = TransactionWithDetails(
        nextId++, amount, type, account, "Account $account", null, category, main, null, now - daysAgo * day, "note"
    )

    @Test
    fun `main account is where the salary lands`() {
        val txs = listOf(
            tx(1, 50.0, TransactionType.EXPENSE),
            tx(1, 60.0, TransactionType.EXPENSE),
            tx(2, 30000.0, TransactionType.INCOME, main = "Income", category = "Pay, benefits and pension")
        )
        assertEquals(2L, pickMainAccount(listOf(1L, 2L), txs, now))
    }

    @Test
    fun `without salary the busiest account is main`() {
        val txs = listOf(tx(1, 50.0, TransactionType.EXPENSE), tx(2, 60.0, TransactionType.EXPENSE), tx(2, 70.0, TransactionType.EXPENSE))
        assertEquals(2L, pickMainAccount(listOf(1L, 2L), txs, now))
    }

    @Test
    fun `old salary is ignored and no history falls back to the first account`() {
        val txs = listOf(tx(2, 30000.0, TransactionType.INCOME, daysAgo = 400, main = "Income", category = "Salary"))
        assertEquals(1L, pickMainAccount(listOf(1L, 2L), txs, now))
        assertNull(pickMainAccount(emptyList(), txs, now))
    }

    @Test
    fun `same amount out of one account and into another is a likely transfer`() {
        val out = tx(1, 5000.0, TransactionType.EXPENSE, daysAgo = 3)
        val into = tx(2, 5000.0, TransactionType.INCOME, daysAgo = 3)
        val pairs = findLikelyTransfers(listOf(out, into, tx(2, 4999.0, TransactionType.INCOME, daysAgo = 3)), now)
        assertEquals(1, pairs.size)
        assertEquals(out.id, pairs[0].out.id)
        assertEquals(into.id, pairs[0].into.id)
    }

    @Test
    fun `same account, far apart dates, salary and existing transfers are not suggested`() {
        val txs = listOf(
            tx(1, 100.0, TransactionType.EXPENSE, daysAgo = 2), tx(1, 100.0, TransactionType.INCOME, daysAgo = 2),
            tx(1, 200.0, TransactionType.EXPENSE, daysAgo = 10), tx(2, 200.0, TransactionType.INCOME, daysAgo = 5),
            tx(1, 300.0, TransactionType.EXPENSE, daysAgo = 2), tx(2, 300.0, TransactionType.INCOME, daysAgo = 2, main = "Income", category = "Salary"),
            tx(1, 400.0, TransactionType.EXPENSE, daysAgo = 2, main = "Other", category = "Other (Transfer)"),
            tx(2, 400.0, TransactionType.INCOME, daysAgo = 2)
        )
        assertTrue(findLikelyTransfers(txs, now).isEmpty())
    }

    @Test
    fun `each transaction is paired once`() {
        val txs = listOf(
            tx(1, 500.0, TransactionType.EXPENSE, daysAgo = 2), tx(3, 500.0, TransactionType.EXPENSE, daysAgo = 2),
            tx(2, 500.0, TransactionType.INCOME, daysAgo = 2)
        )
        assertEquals(1, findLikelyTransfers(txs, now).size)
    }
}
