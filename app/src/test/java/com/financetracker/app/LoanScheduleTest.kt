package com.financetracker.app

import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoanPayment
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.util.LoanSchedule
import com.financetracker.app.util.Mortgage
import com.financetracker.app.util.advisor.AttentionChecks
import com.financetracker.app.util.advisor.AttentionInput
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

    private val loan = Loan(
        id = "m", name = "House mortgage", lender = "Totalkredit", kind = "mortgage", originalAmount = 3668000.0,
        interestRatePct = 3.666, contributionRatePct = 0.8744, paymentsPerYear = 4,
        schedule = rows, rateResets = listOf("2031-07-01", "2036-07-01"),
        discountName = "KundeKroner", discountPct = 0.25, discountUntil = "2029-12", taxDeductionPct = 25.83
    )
    /** The first five rows run on from each other; the later ones are a sample. */
    private val firstFive = loan.copy(schedule = rows.take(5))

    @Test
    fun `rows copied correctly have no issues`() {
        assertTrue(LoanSchedule.check(firstFive).isEmpty())
    }

    @Test
    fun `a misread debt is flagged with the value it should be, and can be fixed`() {
        val bad = firstFive.copy(schedule = rows.take(5).mapIndexed { i, r -> if (i == 2) r.copy(debtAfter = 3621611.10) else r })
        val issue = LoanSchedule.check(bad).first()
        assertEquals(2, issue.index)
        assertEquals(3621411.10, issue.expectedDebt!!, 0.01)
        assertTrue(LoanSchedule.check(LoanSchedule.withDebt(bad, 2, issue.expectedDebt!!)).isEmpty())
    }

    @Test
    fun `a payment that isn't interest plus repayment is flagged`() {
        val bad = firstFive.copy(schedule = rows.take(5).mapIndexed { i, r -> if (i == 1) r.copy(payment = 58842.05) else r })
        assertEquals(listOf(1), LoanSchedule.check(bad).map { it.index })
    }

    @Test
    fun `a missing row is caught by the payment count`() {
        assertTrue(LoanSchedule.check(firstFive.copy(totalPayments = 119)).single().message.contains("119"))
    }

    @Test
    fun `interest and fee split by their rates, with the discount and tax on top`() {
        val march = LoanSchedule.payments(loan)[1]
        // The document's own split for this payment: interest 33.510,19 and bidrag 7.992,48.
        assertEquals(33510.19, march.interest!!, 2.0)
        assertEquals(7992.48, march.fee!!, 2.0)
        // KundeKroner: 0,25% a year of ~3,656,000 for a quarter ≈ 2,285.
        assertEquals(2285.0, march.discount, 5.0)
        assertEquals(58824.05 - march.discount, march.youPay, 0.001)
        // Without the discount this is the table's own after-tax figure.
        assertEquals(48103.90 - march.discount * (1 - 0.2583), march.afterTax, 1.0)
    }

    @Test
    fun `the discount stops after its guaranteed date`() {
        val payments = LoanSchedule.payments(loan)
        assertTrue(payments.single { it.date == utc(2029, 12, 31) }.discount > 0)
        assertEquals(0.0, payments.single { it.date == utc(2030, 3, 31) }.discount, 0.0)
    }

    @Test
    fun `rows after the first rate reset are estimates`() {
        val payments = LoanSchedule.payments(loan)
        assertFalse(payments.single { it.date == utc(2031, 6, 30) }.estimate)
        assertTrue(payments.single { it.date == utc(2031, 9, 30) }.estimate)
    }

    @Test
    fun `next payment, payments made and debt follow today's date`() {
        assertEquals(utc(2026, 12, 31), LoanSchedule.nextPayment(loan, utc(2026, 11, 2))!!.date)
        assertEquals(0, LoanSchedule.paidCount(loan, utc(2026, 11, 2)))
        assertEquals(1, LoanSchedule.paidCount(loan, utc(2027, 1, 1)))
        assertEquals(3656212.61, LoanSchedule.debtNow(loan, utc(2027, 1, 1))!!, 0.0)
        assertEquals(utc(2031, 7, 1), LoanSchedule.nextRateReset(loan, utc(2026, 11, 2)))
        assertEquals(3318909.13, LoanSchedule.debtOn(loan, utc(2031, 7, 1))!!, 0.0)
    }

    @Test
    fun `year totals add up each year's payments`() {
        val y2027 = LoanSchedule.yearTotals(loan).single { it.year == 2027 }
        assertEquals(4, y2027.payments)
        assertEquals(70243.87, y2027.principal, 0.01)
        assertEquals(164823.75, y2027.interestAndFee, 0.01)
    }

    @Test
    fun `the table and new fields survive saving and household sharing`() {
        val back = LoansAndGoals.loanFromJson(JSONObject(LoansAndGoals.loanToJson(loan).toString()))
        assertEquals(loan, back)
    }

    @Test
    fun `the document reader's rows are read, skipping marker rows`() {
        val json = JSONObject(
            """{"name":"Mortgage","schedule":[["2031-06-11",37903.46,20227.46,58130.92,48340.44,3318909.13],["2031-06-30",null,null,null,null,null],["2031-09-11","37.732,19",20392.6,58124.79,null,3298516.53]],"rate_resets":["2031-07-01"]}"""
        )
        val read = LoansAndGoals.loanFromJson(json)
        assertEquals(2, read.schedule.size)
        assertEquals(37732.19, read.schedule[1].interestAndFee, 0.001)
        assertNull(read.schedule[1].paymentAfterTax)
    }

    @Test
    fun `adding a document keeps the mortgage's own settings and takes the real first date`() {
        val existing = Loan(
            id = "x", name = "Our home", kind = "mortgage", paymentMerchant = "totalkredit", paidFromAccountId = 7,
            sharedWithHousehold = true, homeValue = 4_500_000.0, monthlyPayment = 19590.0, paymentsPerYear = 4,
            firstPaymentDate = "2026-12-31", payoutDate = "2026-10-30"
        )
        val merged = LoanSchedule.withDocument(existing, loan.copy(id = "new", name = "Read name"))
        assertEquals("x", merged.id)
        assertEquals("Our home", merged.name)
        assertEquals("totalkredit", merged.paymentMerchant)
        assertEquals(7L, merged.paidFromAccountId)
        assertTrue(merged.sharedWithHousehold)
        assertEquals(rows, merged.schedule)
        assertEquals("2026-12-31", merged.firstPaymentDate)
        assertEquals(0.25, merged.discountPct!!, 0.0)
    }

    // ---------------------------------------------------------------- the Mortgage page uses the table

    @Test
    fun `the mortgage plans with the table's dates and amounts`() {
        val now = utc(2026, 11, 2)
        val (due, amount) = Mortgage.nextPayment(loan, now)!!
        assertEquals(utc(2026, 12, 31), due)
        assertEquals(LoanSchedule.payments(loan)[0].youPay, amount, 0.001)
        // Per month: the next full quarter (not the short first one), after the discount.
        assertEquals(LoanSchedule.payments(loan)[1].youPay / 3, Mortgage.plannedMonthly(loan, now)!!, 0.001)
    }

    @Test
    fun `the mortgage status takes debt and this year's split from the table`() {
        val now = utc(2027, 7, 1)
        val status = Mortgage.status(loan, emptyList(), now)
        assertEquals(3621411.10, status.debt, 0.001)
        assertEquals(17321.38 + 17480.13, status.year.repayment, 0.01)
        val p = LoanSchedule.payments(loan)
        assertEquals(p[1].youPay + p[2].youPay, status.year.paid, 0.01)
        assertEquals((p[1].fee!! - p[1].discount) + (p[2].fee!! - p[2].discount), status.year.contribution, 0.01)
        // After tax with the document's 25.83%, not the default 33%.
        assertEquals((status.year.interest + status.year.contribution) * (1 - 0.2583), Mortgage.afterTax(status.year, loan), 0.01)
    }

    @Test
    fun `payments are dated the last day of the month, weekends included, like the bank`() {
        val sep2028 = loan.copy(schedule = listOf(LoanPayment("2028-09-11", 40295.59, 18296.00, 58591.59, 48183.23, 3531577.72)))
        // 30 Sep 2028 is a Saturday; the owner's bank still shows the 30th.
        assertEquals(utc(2028, 9, 30), LoanSchedule.payments(sep2028).single().date)
    }

    @Test
    fun `the short first payment still counts as two months for the shared bills`() {
        val withPayout = loan.copy(payoutDate = "2026-10-30")
        assertEquals(2, Mortgage.monthsCovered(withPayout, utc(2026, 12, 31)))
        assertEquals(3, Mortgage.monthsCovered(withPayout, utc(2027, 3, 31)))
    }

    @Test
    fun `without a table the mortgage works as before`() {
        val plain = Loan(kind = "mortgage", monthlyPayment = 19590.0, paymentsPerYear = 4, firstPaymentDate = "2026-12-31")
        assertEquals(utc(2026, 12, 31), Mortgage.nextPaymentDate(plain, utc(2026, 11, 2)))
        assertEquals(19590.0, Mortgage.plannedMonthly(plain, utc(2026, 11, 2))!!, 0.0)
    }

    // ---------------------------------------------------------------- attention items

    private fun input(now: Long) = AttentionInput(
        now = now, currency = "DKK", transactions = emptyList(), accounts = listOf(Account(id = 1, name = "Main")),
        balances = mapOf(1L to 50000.0), categories = emptyList(), uncategorizedCategoryIds = emptySet(),
        excludeTransfers = true, shiftSalary = false, mainAccountId = 1, overallBudgets = emptyMap(),
        categoryBudgets = emptyMap(), fixedCategoryIds = emptySet(), goals = emptyList(), loans = listOf(loan),
        consents = emptyList(), lastSyncFailure = null, backupEnabled = false, backupLastSuccessAt = null, backupLastError = null
    )

    @Test
    fun `rate reset and discount end are flagged three months ahead`() {
        assertNotNull(AttentionChecks.run(input(utc(2031, 4, 15))).firstOrNull { it.key.startsWith("rate-reset:") })
        assertNull(AttentionChecks.run(input(utc(2031, 3, 15))).firstOrNull { it.key.startsWith("rate-reset:") })
        assertNotNull(AttentionChecks.run(input(utc(2029, 10, 15))).firstOrNull { it.key.startsWith("loan-discount:") })
    }
}
