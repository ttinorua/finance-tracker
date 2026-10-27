package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.Contributor
import com.financetracker.app.util.SharedBills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SharedBillsTest {

    private var nextId = 1L
    private val bills = 9L
    private val mine = 1L

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day)
    }.timeInMillis

    private fun tx(account: Long, amount: Double, type: TransactionType, date: Long, note: String, main: String = "Housing", category: String = "Rent") =
        TransactionWithDetails(nextId++, amount, type, account, "Account $account", 1L, category, main, "#000000", date, note)

    /** Rent 7,000 monthly and insurance 2,400 yearly (due 1 March) from the bills account. */
    private fun history(): List<TransactionWithDetails> {
        val txs = mutableListOf<TransactionWithDetails>()
        for (m in 1..10) txs += tx(bills, 7000.0, TransactionType.EXPENSE, utc(2026, m, 1), "Boligselskab")
        for (y in 2023..2026) txs += tx(bills, 2400.0, TransactionType.EXPENSE, utc(y, 3, 1), "Tryg Forsikring")
        return txs
    }

    @Test
    fun `shares follow net income`() {
        assertEquals(0.6, SharedBills.myShare(30000.0, 20000.0), 0.0001)
        assertEquals(0.5, SharedBills.myShare(30000.0, 0.0), 0.0001)
    }

    @Test
    fun `targets spread the yearly bill and split by income`() {
        val now = utc(2026, 10, 15)
        val status = SharedBills.status(bills, history(), 5000.0, 30000.0, 20000.0, "Maria Jensen", emptyMap(), now)
        assertEquals(7200.0, status.monthlyBills, 0.01)
        assertEquals(4320.0, status.myTarget, 0.01)
        assertEquals(2880.0, status.partnerTarget, 0.01)
    }

    @Test
    fun `deposits are matched to me, the partner by name, or asked about`() {
        val now = utc(2026, 10, 15)
        val txs = history() + listOf(
            tx(mine, 4320.0, TransactionType.EXPENSE, utc(2026, 10, 2), "Til budgetkonto", "Other", "Other (Transfer)"),
            tx(bills, 4320.0, TransactionType.INCOME, utc(2026, 10, 2), "Fra lønkonto", "Other", "Other (Transfer)"),
            tx(bills, 2500.0, TransactionType.INCOME, utc(2026, 10, 3), "Overførsel M. JENSEN", "Other", "Other (Transfer)"),
            tx(bills, 300.0, TransactionType.INCOME, utc(2026, 10, 5), "MobilePay 1234", "Other", "Other")
        )
        val status = SharedBills.status(bills, txs, 5000.0, 30000.0, 20000.0, "Maria Jensen", emptyMap(), now)
        assertEquals(4320.0, status.myPaidThisMonth, 0.01)
        assertEquals(2500.0, status.partnerPaidThisMonth, 0.01)
        assertEquals(380.0, status.partnerShortThisMonth, 0.01)
        assertEquals(1, status.unknown.size)

        val answered = SharedBills.status(
            bills, txs, 5000.0, 30000.0, 20000.0, "Maria Jensen",
            mapOf(SharedBills.senderKey("MobilePay 1234") to Contributor.PARTNER), now
        )
        assertEquals(2800.0, answered.partnerPaidThisMonth, 0.01)
        assertTrue(answered.unknown.isEmpty())
    }

    @Test
    fun `a big bill the account can't cover gets a warning`() {
        val now = utc(2026, 10, 15)
        val payments = com.financetracker.app.util.advisor.FinanceAnalysis.recurringPayments(history(), now)
        // Nothing in the account and only enough coming in for rent: the March insurance is short.
        val warnings = SharedBills.warnings(payments, 0.0, 7000.0, now)
        assertEquals(1, warnings.size)
        assertEquals(utc(2027, 3, 1), warnings[0].dueDate)
        assertTrue(warnings[0].shortBy > 0)
        // With the full spread-out target coming in, there's a buffer by March.
        assertTrue(SharedBills.warnings(payments, 7000.0, 7200.0, now).isEmpty())
    }

    @Test
    fun `no partner words means nobody is matched by name`() {
        val now = utc(2026, 10, 15)
        val txs = history() + tx(bills, 100.0, TransactionType.INCOME, utc(2026, 10, 4), "Renter")
        val status = SharedBills.status(bills, txs, 0.0, 1.0, 1.0, "", emptyMap(), now)
        assertNull(status.depositsThisMonth.single().from)
    }
}
