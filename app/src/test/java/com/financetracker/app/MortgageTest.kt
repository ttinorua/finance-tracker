package com.financetracker.app

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.util.Mortgage
import com.financetracker.app.util.advisor.FinanceAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MortgageTest {

    private var nextId = 1L

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(year, month - 1, day)
    }.timeInMillis

    private fun pay(date: Long, amount: Double = 11685.0) =
        TransactionWithDetails(nextId++, amount, TransactionType.EXPENSE, 9L, "Budgetkonto", 1L, "Mortgage", "Housing", "#000000", date, "Totalkredit PBS")

    private val loan = Loan(
        name = "House", lender = "Totalkredit", remainingDebt = 2_150_000.0, interestRatePct = 3.2,
        contributionRatePct = 0.85, debtAsOf = utc(2026, 9, 15), paymentMerchant = FinanceAnalysis.merchantOf("Totalkredit PBS")
    )

    @Test
    fun `each payment after the exact figure lowers the debt by its afdrag`() {
        val txs = listOf(pay(utc(2026, 9, 1)), pay(utc(2026, 10, 1)))
        val status = Mortgage.status(loan, txs, utc(2026, 10, 20))
        val interest = 2_150_000.0 * 0.0405 / 12
        assertEquals(2_150_000.0 - (11685.0 - interest), status.debt, 0.01)
        assertEquals(1, status.paymentsSinceExact)
        assertEquals(11685.0, status.monthlyPayment, 0.01)
    }

    @Test
    fun `this year's payments split into interest, bidrag and afdrag`() {
        val txs = (1..10).map { pay(utc(2026, it, 1)) }
        val year = Mortgage.status(loan, txs, utc(2026, 10, 20)).year
        assertEquals(116_850.0, year.paid, 0.01)
        assertEquals(year.paid, year.interest + year.contribution + year.repayment, 0.01)
        assertTrue(year.interest > year.contribution)
        assertEquals((year.interest + year.contribution) * 0.67, Mortgage.afterTax(year), 0.01)
    }

    @Test
    fun `payoff, extra payments and a rate reset`() {
        val r = 0.0405
        val months = Mortgage.monthsToPayOff(2_150_000.0, r, 11685.0)!!
        assertTrue(months in 286..290)
        val extra = Mortgage.extraPayment(2_150_000.0, r, 11685.0, 1000.0)!!
        assertTrue(extra.monthsEarlier in 30..40)
        assertTrue(extra.interestSaved > 100_000)
        assertNull(Mortgage.monthsToPayOff(2_150_000.0, r, 5000.0))
        val status = Mortgage.status(loan, emptyList(), utc(2026, 10, 20))
        // A reset changes the interest; the administration fee (bidrag) stays on top.
        val today = Mortgage.paymentAfterReset(loan, status.copy(monthsLeft = 288), 0.0)!!
        val higher = Mortgage.paymentAfterReset(loan, status.copy(monthsLeft = 288), 1.0)!!
        assertTrue(higher - today in 1000.0..1300.0)
    }

    // The owner's loan: 3,668,000 kr, 3.67% interest + 0.8744% bidrag, 4 payments a year, ends 30 Jun 2056.
    private val quarterly = Loan(
        name = "House mortgage", lender = "Totalkredit A/S (arranged through SJF Bank A/S)", kind = "mortgage",
        remainingDebt = 3_668_000.0, interestRatePct = 3.67, contributionRatePct = 0.8744, endDate = "2056-06-30",
        paymentsPerYear = 4, firstPaymentDate = "2026-12-31", paidFromAccountId = 9L, debtAsOf = utc(2026, 10, 30)
    )

    @Test
    fun `the debt-free date comes from the loan, and bidrag is on top of the annuity`() {
        val now = utc(2026, 10, 31)
        val status = Mortgage.status(quarterly, emptyList(), now)
        assertEquals(356, status.monthsLeft) // 29 years 8 months, as the bank says
        // Annuity on 3.67% plus bidrag on the debt: about 19,500 kr a month, 58,600 a quarter.
        assertTrue(status.monthlyPayment in 19_300.0..19_700.0)
        assertEquals(status.monthlyPayment * 3, Mortgage.plannedPayment(quarterly, now)!!, 0.01)
        val withAmount = quarterly.copy(paymentAmount = 58_791.0)
        assertEquals(19_597.0, Mortgage.plannedMonthly(withAmount, now)!!, 0.01)
    }

    @Test
    fun `quarterly payment dates follow the first one without drifting`() {
        assertEquals(utc(2026, 12, 31), Mortgage.nextPaymentDate(quarterly, utc(2026, 10, 31)))
        assertEquals(utc(2027, 6, 30), Mortgage.nextPaymentDate(quarterly, utc(2027, 4, 2)))
        assertEquals(utc(2027, 12, 31), Mortgage.nextPaymentDate(quarterly, utc(2027, 10, 1)))
    }

    @Test
    fun `the first payment links itself, and only a lender's payment near the amount`() {
        val now = utc(2027, 1, 2)
        fun tx(amount: Double, note: String, account: Long = 9L) =
            TransactionWithDetails(nextId++, amount, TransactionType.EXPENSE, account, "SJF Budget", 1L, "Mortgage", "Housing", "#000000", utc(2026, 12, 31), note)
        assertNull(Mortgage.findFirstPayment(quarterly, listOf(tx(2000.0, "Totalkredit"), tx(58_600.0, "Netto")), now))
        assertNull(Mortgage.findFirstPayment(quarterly, listOf(tx(58_600.0, "Totalkredit", account = 3L)), now))
        assertEquals(FinanceAnalysis.merchantOf("BS Totalkredit"), Mortgage.findFirstPayment(quarterly, listOf(tx(58_600.0, "BS Totalkredit")), now))
    }

    @Test
    fun `extra payments count from the loan's real end`() {
        val status = Mortgage.status(quarterly, emptyList(), utc(2026, 10, 31))
        val annuity = Mortgage.annuityMonthly(quarterly, status)
        val effect = Mortgage.extraPayment(status.debt, Mortgage.interestRateOf(quarterly), annuity, 1000.0, status.monthsLeft)!!
        assertTrue(effect.monthsEarlier in 25..45)
        assertEquals(356 - effect.monthsEarlier, effect.monthsLeft)
    }

    @Test
    fun `a lender's payment is suggested for linking`() {
        val recurring = FinanceAnalysis.recurringPayments(
            (6..9).map { pay(utc(2026, it, 1)) } +
                (6..9).map { TransactionWithDetails(nextId++, 299.0, TransactionType.EXPENSE, 9L, "B", 2L, "Internet", "Media", "#000000", utc(2026, it, 5), "Telenor") },
            utc(2026, 9, 20)
        )
        assertEquals(FinanceAnalysis.merchantOf("Totalkredit PBS"), Mortgage.suggestPayment(loan, recurring)?.merchant)
    }

    @Test
    fun `friværdi is the home value minus the debt`() {
        val status = Mortgage.status(loan.copy(homeValue = 3_400_000.0), emptyList(), utc(2026, 10, 20))
        assertEquals(1_250_000.0, Mortgage.equity(loan.copy(homeValue = 3_400_000.0), status)!!, 0.01)
    }
}
