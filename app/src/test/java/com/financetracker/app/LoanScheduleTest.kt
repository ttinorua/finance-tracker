package com.financetracker.app

import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoanPayment
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.util.LoanSchedule
import com.financetracker.app.util.advisor.AttentionChecks
import com.financetracker.app.util.advisor.AttentionInput
import com.financetracker.app.util.advisor.AttentionLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Rows and terms from a real Totalkredit F5 loan specification (Oct 2026). */
class LoanScheduleTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    private val rows = listOf(
        LoanPayment("2026-12-11", 28511.94, 11787.39, 40299.33, 32934.68, 3656212.61),
        LoanPayment("2027-03-11", 41502.67, 17321.38, 58824.05, 48103.90, 3638891.23),
        LoanPayment("2027-06-11", 41306.06, 17480.13, 58786.19, 48116.83, 3621411.10),
        LoanPayment("2027-09-11", 41107.63, 17640.34, 58747.97, 48129.86, 3603770.76),
        LoanPayment("2027-12-11", 40907.39, 17802.02, 58709.41, 48143.02, 3585968.74),
        LoanPayment("2029-12-11", 39237.97, 19149.95, 58387.92, 48252.74, 3437551.45),
        LoanPayment("2030-03-11", 39020.60, 19325.46, 58346.06, 48267.03, 3418225.99),
        LoanPayment("2031-06-11", 37903.46, 20227.46, 58130.92, 48340.44, 3318909.13),
        LoanPayment("2031-09-11", 37732.19, 20392.60, 58124.79, 48378.55, 3298516.53)
    )

    /** The first five rows run on from each other; later ones are a sample, so check only those five. */
    private val loan = Loan(
        id = "m", name = "House mortgage", lender = "Totalkredit", originalAmount = 3668000.0,
        interestRatePct = 3.666, contributionRatePct = 0.8744,
        schedule = rows, rateResets = listOf("2031-07-01", "2036-07-01"),
        paymentDay = 11, paymentMonths = listOf(3, 6, 9, 12),
        discountName = "KundeKroner", discountPct = 0.25, discountUntil = "2029-12", taxDeductionPct = 25.83
    )
    private val firstFive = loan.copy(schedule = rows.take(5))

    @Test
    fun `rows copied correctly have no issues`() {
        assertTrue(LoanSchedule.check(firstFive).isEmpty())
    }

    @Test
    fun `a misread debt is flagged with the value it should be, and can be fixed`() {
        val bad = firstFive.copy(schedule = rows.take(5).mapIndexed { i, r -> if (i == 2) r.copy(debtAfter = 3621611.10) else r })
        val issues = LoanSchedule.check(bad)
        val issue = issues.first()
        assertEquals(2, issue.index)
        assertEquals(3621411.10, issue.expectedDebt!!, 0.01)
        assertTrue(LoanSchedule.check(LoanSchedule.withDebt(bad, 2, issue.expectedDebt!!)).isEmpty())
    }

    @Test
    fun `a payment that isn't interest plus principal is flagged`() {
        val bad = firstFive.copy(schedule = rows.take(5).mapIndexed { i, r -> if (i == 1) r.copy(payment = 58842.05) else r })
        assertEquals(listOf(1), LoanSchedule.check(bad).map { it.index })
    }

    @Test
    fun `a missing row is caught by the payment count`() {
        val issues = LoanSchedule.check(firstFive.copy(totalPayments = 119))
        assertTrue(issues.single().message.contains("119"))
    }

    @Test
    fun `interest and fee split by their rates, with the discount and tax on top`() {
        val march = LoanSchedule.payments(loan)[1]
        // The document's own split for this payment: interest 33.510,19 and fee 7.992,48.
        assertEquals(33510.19, march.interest!!, 2.0)
        assertEquals(7992.48, march.fee!!, 2.0)
        // KundeKroner: 0,25% a year of ~3,656,000 for a quarter ≈ 2,285.
        assertEquals(2285.0, march.discount, 5.0)
        assertEquals(58824.05 - march.discount, march.youPay, 0.001)
        // Without the discount this would be the table's own after-tax figure.
        assertEquals(48103.90 - march.discount * (1 - 0.2583), march.afterTax!!, 1.0)
    }

    @Test
    fun `the discount stops after its guaranteed date`() {
        val payments = LoanSchedule.payments(loan)
        assertTrue(payments.single { it.date == utc(2029, 12, 11) }.discount > 0)
        assertEquals(0.0, payments.single { it.date == utc(2030, 3, 11) }.discount, 0.0)
    }

    @Test
    fun `rows after the first rate reset are estimates`() {
        val payments = LoanSchedule.payments(loan)
        assertFalse(payments.single { it.date == utc(2031, 6, 11) }.estimate)
        assertTrue(payments.single { it.date == utc(2031, 9, 11) }.estimate)
    }

    @Test
    fun `next payment, payments made and debt now follow today's date`() {
        assertEquals(utc(2026, 12, 11), LoanSchedule.nextPayment(loan, utc(2026, 11, 2))!!.date)
        assertEquals(0, LoanSchedule.paidCount(loan, utc(2026, 11, 2)))
        assertEquals(3668000.0, LoanSchedule.debtNow(loan, utc(2026, 11, 2))!!, 0.0)
        assertEquals(1, LoanSchedule.paidCount(loan, utc(2027, 1, 1)))
        assertEquals(3656212.61, LoanSchedule.debtNow(loan, utc(2027, 1, 1))!!, 0.0)
        assertEquals(utc(2031, 7, 1), LoanSchedule.nextRateReset(loan, utc(2026, 11, 2)))
        assertEquals(3318909.13, LoanSchedule.debtOn(loan, utc(2031, 7, 1))!!, 0.0)
    }

    @Test
    fun `a loan without a table still knows its next payment date`() {
        val plain = Loan(paymentDay = 11, paymentMonths = listOf(3, 6, 9, 12))
        assertEquals(utc(2026, 12, 11), LoanSchedule.nextPaymentDate(plain, utc(2026, 11, 2)))
        assertEquals(utc(2027, 3, 11), LoanSchedule.nextPaymentDate(plain, utc(2026, 12, 12)))
    }

    @Test
    fun `year totals add up each year's payments`() {
        val y2027 = LoanSchedule.yearTotals(loan).single { it.year == 2027 }
        assertEquals(4, y2027.payments)
        assertEquals(70243.87, y2027.principal, 0.01)
        assertEquals(164823.75, y2027.interestAndFee, 0.01)
    }

    private var nextId = 1L
    private fun tx(date: Long, amount: Double, note: String) = TransactionWithDetails(
        id = nextId++, amount = amount, type = TransactionType.EXPENSE, accountId = 1, accountName = "Main",
        categoryId = null, categoryName = null, mainCategoryName = null, categoryColorHex = null, date = date, note = note
    )

    @Test
    fun `the bank payment is matched by amount and lender`() {
        val december = LoanSchedule.payments(loan).first()
        val paid = tx(utc(2026, 12, 11), 38730.0, "TOTALKREDIT A/S")
        assertEquals(paid, LoanSchedule.findBankPayment(loan, december, listOf(tx(utc(2026, 12, 11), 450.0, "Netto"), paid)))
        assertNull(LoanSchedule.findBankPayment(loan, december, listOf(tx(utc(2026, 12, 11), 30000.0, "Totalkredit"))))
        assertNull(LoanSchedule.findBankPayment(loan, december, listOf(tx(utc(2027, 2, 11), 38730.0, "Totalkredit"))))
    }

    @Test
    fun `the schedule and new fields survive saving`() {
        val back = LoansAndGoals.loanFromJson(JSONObject(LoansAndGoals.loanToJson(loan).toString()))
        assertEquals(loan, back)
    }

    @Test
    fun `the document reader's rows are read, skipping marker rows`() {
        val json = JSONObject(
            """{"name":"Mortgage","schedule":[["2031-06-11",37903.46,20227.46,58130.92,48340.44,3318909.13],["2031-06-30",null,null,null,null,null],["2031-09-11","37.732,19",20392.6,58124.79,null,3298516.53]],"rate_resets":["2031-07-01"],"payment_months":[3,6,9,12]}"""
        )
        val read = LoansAndGoals.loanFromJson(json)
        assertEquals(2, read.schedule.size)
        assertEquals(37732.19, read.schedule[1].interestAndFee, 0.001)
        assertNull(read.schedule[1].paymentAfterTax)
        assertTrue(read.remindPayments)
    }

    // ---------------------------------------------------------------- attention items

    private fun input(now: Long, transactions: List<TransactionWithDetails> = emptyList(), loans: List<Loan> = listOf(loan)) = AttentionInput(
        now = now, currency = "DKK", transactions = transactions, accounts = listOf(Account(id = 1, name = "Main")),
        balances = mapOf(1L to 50000.0), categories = emptyList(), uncategorizedCategoryIds = emptySet(),
        excludeTransfers = true, shiftSalary = false, mainAccountId = 1, overallBudgets = emptyMap(),
        categoryBudgets = emptyMap(), fixedCategoryIds = emptySet(), goals = emptyList(), loans = loans,
        consents = emptyList(), lastSyncFailure = null, backupEnabled = false, backupLastSuccessAt = null, backupLastError = null
    )

    @Test
    fun `a payment reminder comes three days before`() {
        assertNull(AttentionChecks.run(input(utc(2026, 12, 5))).firstOrNull { it.key.startsWith("loan-payment:") })
        val item = AttentionChecks.run(input(utc(2026, 12, 8))).single { it.key.startsWith("loan-payment:") }
        assertTrue(item.title.contains("11 Dec 2026"))
        assertTrue(item.notify)
        assertTrue(AttentionChecks.run(input(utc(2026, 12, 8), loans = listOf(loan.copy(remindPayments = false)))).none { it.key.startsWith("loan-payment:") })
    }

    @Test
    fun `a payment missing from the bank is flagged once later transactions have synced`() {
        val later = tx(utc(2027, 1, 10), 300.0, "Netto")
        val missing = AttentionChecks.run(input(utc(2027, 1, 10), listOf(later))).single { it.key.startsWith("loan-missing:") }
        assertEquals(AttentionLevel.WARNING, missing.level)
        val paid = tx(utc(2026, 12, 11), 38730.0, "Totalkredit")
        assertTrue(AttentionChecks.run(input(utc(2027, 1, 10), listOf(paid, later))).none { it.key.startsWith("loan-missing:") })
        // No newer transactions: the bank may just not have synced yet.
        assertTrue(AttentionChecks.run(input(utc(2027, 1, 10))).none { it.key.startsWith("loan-missing:") })
    }

    @Test
    fun `rate reset and discount end are flagged three months ahead`() {
        assertNotNull(AttentionChecks.run(input(utc(2031, 4, 15))).firstOrNull { it.key.startsWith("rate-reset:") })
        assertNull(AttentionChecks.run(input(utc(2031, 3, 15))).firstOrNull { it.key.startsWith("rate-reset:") })
        assertNotNull(AttentionChecks.run(input(utc(2029, 10, 15))).firstOrNull { it.key.startsWith("loan-discount:") })
    }
}
