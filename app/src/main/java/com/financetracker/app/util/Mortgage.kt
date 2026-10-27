package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.util.advisor.FinanceAnalysis
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Where this year's mortgage payments went. Afdrag is the part that lowers the debt. */
data class MortgageYear(val paid: Double, val interest: Double, val contribution: Double, val repayment: Double)

data class MortgageStatus(
    /** The debt now: the last exact figure, lowered by the afdrag in each payment since. */
    val debt: Double,
    /** What one payment costs per month (a quarterly payment divided by 3). */
    val monthlyPayment: Double,
    val year: MortgageYear,
    /** Months until the debt is paid at [monthlyPayment]; null when the payment doesn't cover the interest. */
    val monthsLeft: Int?,
    val lastPaymentDate: Long?,
    val paymentsSinceExact: Int
) {
    fun paidOffFraction(original: Double?): Double? = original?.takeIf { it > 0 }?.let { ((it - debt) / it).coerceIn(0.0, 1.0) }
}

data class ExtraPaymentEffect(val monthsLeft: Int, val monthsEarlier: Int, val interestSaved: Double)

/**
 * A Danish mortgage linked to its PBS payment. Each payment is split the way an annuity loan
 * splits it: interest and bidrag (contribution) on the remaining debt for the months it covers,
 * and the rest is afdrag, which lowers the debt. The figures are estimates between two exact
 * debts (typed in, or from the yearly årsopgørelse), which correct them.
 */
object Mortgage {
    private const val DAY = 24L * 60 * 60 * 1000
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Rentefradrag is worth roughly a third of the interest (and bidrag) in Denmark. */
    const val TAX_DEDUCTION_RATE = 0.33

    /** Lender words that point at a mortgage payment, to suggest which PBS payment to link. */
    private val LENDER_WORDS = listOf("totalkredit", "nykredit", "realkredit", "brf", "dlr", "jyske", "nordea kredit", "boligkredit", "ld kredit")

    private fun yearlyRate(loan: Loan) = ((loan.interestRatePct ?: 0.0) + (loan.contributionRatePct ?: 0.0)) / 100

    fun payments(loan: Loan, transactions: List<TransactionWithDetails>): List<TransactionWithDetails> =
        if (loan.paymentMerchant.isBlank()) emptyList()
        else transactions.filter { it.type == TransactionType.EXPENSE && FinanceAnalysis.merchantOf(it.note) == loan.paymentMerchant }
            .sortedBy { it.date }

    /** The repeating payment most likely to be this loan's: a lender's name, else the biggest bill. */
    fun suggestPayment(loan: Loan, recurring: List<FinanceAnalysis.RecurringPayment>): FinanceAnalysis.RecurringPayment? {
        val lender = loan.lender.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        return recurring.firstOrNull { p -> lender.any { it in p.merchant } }
            ?: recurring.firstOrNull { p -> LENDER_WORDS.any { it in p.merchant } }
    }

    fun status(loan: Loan, transactions: List<TransactionWithDetails>, now: Long): MortgageStatus {
        val r = yearlyRate(loan)
        val contribution = (loan.contributionRatePct ?: 0.0) / 100
        val all = payments(loan, transactions)
        val asOf = loan.debtAsOf ?: now
        val after = all.filter { it.date > asOf }

        // Payment by payment: payments after the exact figure lower the debt; this year's split
        // uses the debt each one was paid on (the exact figure for payments before it).
        val yearStart = Calendar.getInstance(utc).apply {
            timeInMillis = now; set(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var interestSum = 0.0
        var contributionSum = 0.0
        var repaymentSum = 0.0
        var paidSum = 0.0
        var running = (loan.remainingDebt ?: 0.0)
        var prev: Long? = null
        for (p in all) {
            val months = prev?.let { ((p.date - it) / (30.44 * DAY)).roundToInt().coerceIn(1, 12) } ?: guessMonths(all)
            val base = if (p.date > asOf) running else (loan.remainingDebt ?: 0.0)
            val i = base * ((loan.interestRatePct ?: 0.0) / 100) / 12 * months
            val c = base * contribution / 12 * months
            val a = (p.amount - i - c).coerceAtLeast(0.0)
            if (p.date > asOf) running = (running - a).coerceAtLeast(0.0)
            if (p.date >= yearStart) {
                paidSum += p.amount; interestSum += i; contributionSum += c; repaymentSum += a
            }
            prev = p.date
        }

        val monthly = when {
            all.size >= 2 -> all.last().amount / guessMonths(all)
            all.size == 1 -> all.last().amount
            else -> loan.monthlyPayment ?: 0.0
        }
        val debt = running
        return MortgageStatus(
            debt = debt,
            monthlyPayment = monthly,
            year = MortgageYear(paidSum, interestSum, contributionSum, repaymentSum),
            monthsLeft = monthsToPayOff(debt, r, monthly),
            lastPaymentDate = all.lastOrNull()?.date,
            paymentsSinceExact = after.size
        )
    }

    /** Months between payments: 3 for quarterly, else 1. */
    private fun guessMonths(payments: List<TransactionWithDetails>): Int {
        if (payments.size < 2) return 1
        val gaps = payments.zipWithNext { a, b -> (b.date - a.date) / DAY }.sorted()
        return if (gaps[gaps.size / 2] in 75..105) 3 else 1
    }

    /** Months to pay [debt] at [yearlyRate] paying [monthly]; null if it never gets paid. */
    fun monthsToPayOff(debt: Double, yearlyRate: Double, monthly: Double): Int? {
        if (debt <= 0) return 0
        if (monthly <= 0) return null
        val r = yearlyRate / 12
        if (r == 0.0) return kotlin.math.ceil(debt / monthly).toInt()
        val x = 1 - debt * r / monthly
        if (x <= 0) return null
        return kotlin.math.ceil(-ln(x) / ln(1 + r)).toInt()
    }

    /** The annuity payment for [debt] over [months] at [yearlyRate]. */
    fun paymentFor(debt: Double, yearlyRate: Double, months: Int): Double {
        if (months <= 0) return debt
        val r = yearlyRate / 12
        return if (r == 0.0) debt / months else debt * r / (1 - (1 + r).pow(-months))
    }

    fun extraPayment(debt: Double, yearlyRate: Double, monthly: Double, extra: Double): ExtraPaymentEffect? {
        val base = monthsToPayOff(debt, yearlyRate, monthly) ?: return null
        val faster = monthsToPayOff(debt, yearlyRate, monthly + extra) ?: return null
        val saved = (monthly * base - debt) - ((monthly + extra) * faster - debt)
        return ExtraPaymentEffect(faster, base - faster, saved.coerceAtLeast(0.0))
    }

    /** The monthly payment after a rate reset of [deltaPct] points, over the same months left. */
    fun paymentAfterReset(loan: Loan, status: MortgageStatus, deltaPct: Double): Double? {
        val months = status.monthsLeft ?: return null
        return paymentFor(status.debt, yearlyRate(loan) + deltaPct / 100, months)
    }

    fun yearlyRateOf(loan: Loan): Double = yearlyRate(loan)

    /** Friværdi: the home's value minus the debt (the whole, before any ownership share). */
    fun equity(loan: Loan, status: MortgageStatus): Double? = loan.homeValue?.let { it - status.debt }

    fun afterTax(year: MortgageYear): Double = (year.interest + year.contribution) * (1 - TAX_DEDUCTION_RATE)

    /** [now] plus [months] months, for the debt-free date. */
    fun dateAfter(now: Long, months: Int): Long = Calendar.getInstance(utc).apply { timeInMillis = now; add(Calendar.MONTH, months) }.timeInMillis
}
