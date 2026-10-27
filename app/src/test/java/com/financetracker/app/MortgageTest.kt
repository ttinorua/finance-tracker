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
        val higher = Mortgage.paymentAfterReset(loan.copy(monthlyPayment = 11685.0), status.copy(monthsLeft = months), 1.0)!!
        assertTrue(higher in 12700.0..13100.0)
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
