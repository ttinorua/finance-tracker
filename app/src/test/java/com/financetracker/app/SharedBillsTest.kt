package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.BillCadence
import com.financetracker.app.util.CardPayment
import com.financetracker.app.util.Contributor
import com.financetracker.app.util.PartnerBill
import com.financetracker.app.util.PartnerReport
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.SharedBillsInput
import com.financetracker.app.util.SharedLine
import com.financetracker.app.util.advisor.FinanceAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SharedBillsTest {

    private var nextId = 1L
    private val bills = 9L
    private val mine = 1L
    private val now = utc(2026, 10, 28)

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day)
    }.timeInMillis

    private fun tx(account: Long, amount: Double, type: TransactionType, date: Long, note: String, main: String = "Housing", category: String = "Rent") =
        TransactionWithDetails(nextId++, amount, type, account, "Account $account", 1L, category, main, "#000000", date, note)

    private fun input(txs: List<TransactionWithDetails>, vararg changes: (SharedBillsInput) -> SharedBillsInput): SharedBillsInput {
        var i = SharedBillsInput(bills, txs, 5000.0, 30000.0, 20000.0, "Kjestine Grand", now = now, startedAt = utc(2026, 10, 1))
        changes.forEach { i = it(i) }
        return i
    }

    /** A new bills account: rent and electricity paid once, 4 weeks ago. */
    private fun newAccount() = listOf(
        tx(bills, 8000.0, TransactionType.EXPENSE, utc(2026, 10, 1), "Boligselskab"),
        tx(bills, 650.0, TransactionType.EXPENSE, utc(2026, 10, 3), "Norlys")
    )

    private fun netflix() = (5..10).map { tx(mine, 129.0, TransactionType.EXPENSE, utc(2026, it, 5), "Netflix", "Media", "Streaming") }

    @Test
    fun `shares follow net income and can be locked`() {
        assertEquals(0.6, SharedBills.myShare(30000.0, 20000.0), 0.0001)
        assertEquals(0.5, SharedBills.myShare(30000.0, 0.0), 0.0001)
        val locked = SharedBills.status(input(newAccount(), { it.copy(lockedMySharePct = 45.0) }))
        assertEquals(0.45, locked.myShare, 0.0001)
        assertTrue(locked.shareLocked)
    }

    @Test
    fun `everything paid out of a new bills account counts, at least a month`() {
        val s = SharedBills.status(input(newAccount()))
        assertEquals(8650.0, s.budgetMonthly, 0.01)
        assertTrue(s.budgetIsRough)
        assertEquals(5190.0, s.myTarget, 0.01)
        assertEquals(3460.0, s.partnerTarget, 0.01)
    }

    @Test
    fun `money moved back out of the bills account is not a bill`() {
        val txs = newAccount() + listOf(
            tx(bills, 1000.0, TransactionType.EXPENSE, utc(2026, 10, 10), "Til lønkonto"),
            tx(mine, 1000.0, TransactionType.INCOME, utc(2026, 10, 10), "Fra budget"),
            tx(bills, 500.0, TransactionType.EXPENSE, utc(2026, 10, 12), "Til Kjestine")
        )
        assertEquals(8650.0, SharedBills.status(input(txs)).budgetMonthly, 0.01)
    }

    @Test
    fun `ticked card bills and the partner's typed bills add to the total and count as paid`() {
        val spotify = PartnerBill("1", "Spotify", 179.0, BillCadence.MONTHLY)
        val s = SharedBills.status(input(newAccount() + netflix(), {
            it.copy(sharedMerchants = setOf(FinanceAnalysis.merchantOf("Netflix")), partnerBills = listOf(spotify))
        }))
        assertEquals(8650.0 + 129.0 + 179.0, s.monthlyBills, 0.01)
        assertEquals(129.0, s.myCardThisMonth, 0.01)
        assertEquals(179.0, s.partnerCardThisMonth, 0.01)
        assertEquals(s.myTarget - 129.0, s.myToTransfer, 0.01)
    }

    @Test
    fun `a ticked bill now paid from the bills account is counted once`() {
        val txs = newAccount() + netflix() + tx(bills, 129.0, TransactionType.EXPENSE, utc(2026, 10, 20), "Netflix")
        val s = SharedBills.status(input(txs, { it.copy(sharedMerchants = setOf(FinanceAnalysis.merchantOf("Netflix"))) }))
        assertTrue(s.myBills.isEmpty())
        assertEquals(listOf("Netflix"), s.movedToBudget)
    }

    @Test
    fun `a bill is the same whatever the bank puts in front of it`() {
        val telenor = (5..9).map { tx(mine, 719.6, TransactionType.EXPENSE, utc(2026, it, 6), "MCD Telenor", "Media", "Phone") }
        val txs = newAccount() + telenor + tx(bills, 719.6, TransactionType.EXPENSE, utc(2026, 10, 6), "MobilePay Telenor")
        val s = SharedBills.status(input(txs, { it.copy(sharedMerchants = setOf(FinanceAnalysis.merchantOf("MCD Telenor"))) }))
        assertTrue(s.myBills.isEmpty())
        assertEquals(1, s.movedToBudget.size)
        assertTrue(SharedBills.sameMerchant("bs ok el", "ok el"))
        assertFalse(SharedBills.sameMerchant("bs ok el", "elgiganten"))
    }

    @Test
    fun `direct debits are labelled as paid from your account`() {
        val kommune = (5..10).map { tx(mine, 3055.0, TransactionType.EXPENSE, utc(2026, it, 1), "BS Solrød Kommune", "Housing", "Tax") }
        val s = SharedBills.status(input(newAccount() + kommune, { it.copy(sharedMerchants = setOf(FinanceAnalysis.merchantOf("BS Solrød Kommune"))) }))
        assertEquals("your account (PBS)", s.myBills.single().via)
    }

    @Test
    fun `the partner's name wins over a same-amount payment from your account`() {
        val txs = newAccount() + listOf(
            tx(mine, 20000.0, TransactionType.EXPENSE, utc(2026, 10, 2), "Husleje andel"),
            tx(bills, 20000.0, TransactionType.INCOME, utc(2026, 10, 2), "Kjestine Grand og Ti", "Other", "Other (Transfer)"),
            tx(bills, 300.0, TransactionType.INCOME, utc(2026, 10, 5), "MobilePay 1234", "Other", "Other")
        )
        val s = SharedBills.status(input(txs))
        assertEquals(20000.0, s.partnerPaidInThisMonth, 0.01)
        assertEquals(0.0, s.myPaidInThisMonth, 0.01)
        assertEquals(1, s.unknown.size)
        val answered = SharedBills.status(input(txs, { it.copy(answers = mapOf(SharedBills.senderKey("MobilePay 1234") to Contributor.ME)) }))
        assertEquals(300.0, answered.myPaidInThisMonth, 0.01)
    }

    @Test
    fun `no bills means no target`() {
        val s = SharedBills.status(input(emptyList()))
        assertFalse(s.hasTarget)
        assertEquals(0.0, s.myTarget, 0.0)
    }

    @Test
    fun `balance counts from the start month against saved targets`() {
        val txs = newAccount() + tx(bills, 5000.0, TransactionType.INCOME, utc(2026, 9, 25), "Fra mig") +
            tx(mine, 5000.0, TransactionType.EXPENSE, utc(2026, 9, 25), "Til budget")
        val s = SharedBills.status(input(txs, {
            it.copy(startedAt = utc(2026, 9, 1), history = mapOf("2026-09" to (4000.0 to 3000.0)))
        }))
        assertEquals(2, s.monthsCounted)
        // September: paid 5,000 against 4,000; October: nothing yet against 5,190.
        assertEquals(5000.0 - 4000.0 - s.myTarget, s.myBalance, 0.01)
    }

    @Test
    fun `paying more than your share suggests a card bill to hand over`() {
        val txs = newAccount() + netflix() + tx(bills, 5320.0, TransactionType.INCOME, utc(2026, 10, 2), "Fra mig") +
            tx(mine, 5320.0, TransactionType.EXPENSE, utc(2026, 10, 2), "Til budget")
        val s = SharedBills.status(input(txs, { it.copy(sharedMerchants = setOf(FinanceAnalysis.merchantOf("Netflix"))) }))
        assertTrue(s.myAveragePerMonth >= 50)
        assertEquals("Netflix", s.handOver?.name)
    }

    @Test
    fun `the partner's phone replaces typed bills and income, and its transfers are certain`() {
        val report = PartnerReport(
            netIncome = 30000.0,
            bills = listOf(SharedLine("Internet", 299.0)),
            cardPayments = listOf(CardPayment("Internet", 299.0, utc(2026, 10, 4))),
            transfersOut = listOf(CardPayment("", 4000.0, utc(2026, 10, 3)))
        )
        val txs = newAccount() + tx(bills, 4000.0, TransactionType.INCOME, utc(2026, 10, 3), "Overførsel")
        val s = SharedBills.status(input(txs, {
            it.copy(partner = report, partnerBills = listOf(PartnerBill("1", "Spotify", 179.0, BillCadence.MONTHLY)))
        }))
        assertTrue(s.partnerConnected)
        assertEquals(0.5, s.myShare, 0.0001)
        assertEquals(8650.0 + 299.0, s.monthlyBills, 0.01)
        assertEquals(4000.0, s.partnerPaidInThisMonth, 0.01)
        assertEquals(299.0, s.partnerCardThisMonth, 0.01)
    }

    @Test
    fun `salary is averaged over the months you were paid`() {
        val txs = listOf(
            tx(mine, 30000.0, TransactionType.INCOME, utc(2026, 8, 28), "Løn", "Income", "Salary"),
            tx(mine, 36000.0, TransactionType.INCOME, utc(2026, 9, 28), "Løn", "Income", "Salary")
        )
        assertEquals(33000.0, SharedBills.averageNetSalary(txs, now), 0.01)
    }

    @Test
    fun `a big bill the account can't cover gets a warning`() {
        val history = mutableListOf<TransactionWithDetails>()
        for (m in 1..10) history += tx(bills, 7000.0, TransactionType.EXPENSE, utc(2026, m, 1), "Boligselskab")
        for (y in 2023..2026) history += tx(bills, 2400.0, TransactionType.EXPENSE, utc(y, 3, 1), "Tryg Forsikring")
        val payments = FinanceAnalysis.recurringPayments(history, utc(2026, 10, 15))
        val warnings = SharedBills.warnings(payments, 0.0, 7000.0, utc(2026, 10, 15))
        assertEquals(1, warnings.size)
        assertEquals(utc(2027, 3, 1), warnings[0].dueDate)
        assertTrue(SharedBills.warnings(payments, 7000.0, 7200.0, utc(2026, 10, 15)).isEmpty())
    }

    @Test
    fun `my side shares only income, ticked bills and transfer amounts`() {
        val txs = netflix() + tx(mine, 4000.0, TransactionType.EXPENSE, utc(2026, 10, 2), "Overførsel til budget") +
            tx(mine, 250.0, TransactionType.EXPENSE, utc(2026, 10, 3), "Restaurant", "Food", "Restaurants")
        val side = SharedBills.mySide(txs, bills, setOf(FinanceAnalysis.merchantOf("Netflix")), emptySet(), now)
        assertEquals(listOf("Netflix"), side.bills.map { it.name })
        assertEquals(6, side.cardPayments.size)
        assertEquals(listOf(4000.0), side.transfersOut.map { it.amount })
        assertNull(side.transfersOut.firstOrNull { it.amount == 250.0 })
    }

    @Test
    fun `a planned quarterly loan counts a third a month from day one, never twice`() {
        val loan = com.financetracker.app.util.PlannedBill("House mortgage", "bs totalkredit", 60_000.0, 3, utc(2026, 12, 31))
        val withLoan: (SharedBillsInput) -> SharedBillsInput = { it.copy(planned = listOf(loan)) }
        val before = SharedBills.status(input(newAccount(), withLoan))
        val without = SharedBills.status(input(newAccount()))
        assertEquals(without.monthlyBills + 20_000.0, before.monthlyBills, 0.01)
        assertTrue(before.budgetPayees.any { it.name == "House mortgage" && it.monthly == 20_000.0 })
        // The real payment arrives: still a third a month, shown as paid, not added again.
        val paid = newAccount() + tx(bills, 60_000.0, TransactionType.EXPENSE, utc(2026, 10, 20), "BS Totalkredit")
        val after = SharedBills.status(input(paid, withLoan))
        assertEquals(before.monthlyBills, after.monthlyBills, 0.01)
        assertTrue(SharedBills.paidThisMonth(input(paid, withLoan), false).any { it.amount == 60_000.0 })
        val upcoming = SharedBills.upcomingBills(input(paid, withLoan), false)
        assertEquals(1, upcoming.count { it.amount == 60_000.0 && it.everyMonths == 3 })
        // Saving for it: two months to go at the end of October, so 1 of 3 months set aside.
        assertEquals(1 to 20_000.0, com.financetracker.app.util.BillsAhead.setAside(loan, now))
    }
}
