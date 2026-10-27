package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.util.countsTowardTotals
import com.financetracker.app.util.isTransferCategory
import com.financetracker.app.util.transfersInCountAsIncome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferFilterTest {

    @Test
    fun `matches the real taxonomy's Other (Transfer) category`() {
        assertTrue(isTransferCategory("Other", "Other (Transfer)"))
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(isTransferCategory("OTHER", "other (transfer)"))
    }

    @Test
    fun `does not match a different Other category`() {
        assertFalse(isTransferCategory("Other", "Other (Cash)"))
        assertFalse(isTransferCategory("Other", "Other expense"))
    }

    @Test
    fun `null category never matches`() {
        assertFalse(isTransferCategory(null, null))
    }

    private val income = TransactionType.INCOME
    private val expense = TransactionType.EXPENSE

    @Test
    fun `disabled setting always counts, even a transfer`() {
        assertTrue(countsTowardTotals(expense, "Other", "Other (Transfer)", excludeTransfers = false, transfersInAreIncome = false))
        assertTrue(countsTowardTotals(income, "Other", "Other (Transfer)", excludeTransfers = false, transfersInAreIncome = false))
    }

    @Test
    fun `across all accounts a transfer is excluded in either direction`() {
        assertFalse(countsTowardTotals(expense, "Other", "Other (Transfer)", excludeTransfers = true, transfersInAreIncome = false))
        assertFalse(countsTowardTotals(income, "Other", "Other (Transfer)", excludeTransfers = true, transfersInAreIncome = false))
    }

    @Test
    fun `when transfers in are income an incoming transfer counts`() {
        assertTrue(countsTowardTotals(income, "Other", "Other (Transfer)", excludeTransfers = true, transfersInAreIncome = true))
    }

    @Test
    fun `when transfers count for an account an outgoing transfer is its spending`() {
        assertTrue(countsTowardTotals(expense, "Other", "Other (Transfer)", excludeTransfers = true, transfersInAreIncome = true))
    }

    @Test
    fun `non-transfers always count`() {
        assertTrue(countsTowardTotals(expense, "Food", "Groceries", excludeTransfers = true, transfersInAreIncome = false))
        assertTrue(countsTowardTotals(income, "Income", "Pay, benefits and pension", excludeTransfers = true, transfersInAreIncome = false))
        assertTrue(countsTowardTotals(expense, null, null, excludeTransfers = true, transfersInAreIncome = false))
    }

    @Test
    fun `transfers in are income only for a single account that isn't the main one`() {
        assertTrue(transfersInCountAsIncome(selectedAccountId = 2L, mainAccountId = 1L))
        assertTrue(transfersInCountAsIncome(selectedAccountId = 2L, mainAccountId = null))
        assertFalse(transfersInCountAsIncome(selectedAccountId = 1L, mainAccountId = 1L))
        assertFalse(transfersInCountAsIncome(selectedAccountId = null, mainAccountId = 1L))
    }
}
