package com.financetracker.app.util

import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoanPayment
import com.financetracker.app.util.advisor.AttentionChecks
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * A loan's payment table as the lender printed it (e.g. Totalkredit's afdragsforløb). The table is
 * the source of truth: the app only adds what the lender leaves out (a fee discount such as
 * KundeKroner, the split between interest and fee, the tax effect) and checks the AI copied every
 * row right, since each row's debt must follow from the row before. [Mortgage] uses it in place
 * of its estimates when a loan has one.
 */
object LoanSchedule {

    private const val DAY = 24L * 60 * 60 * 1000
    /** Rounding slack when checking rows add up: the lender rounds to øre, the AI might not. */
    private const val TOLERANCE = 0.5
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** A row that doesn't add up. [expectedDebt] is what the debt should be, if the row before is right. */
    data class Issue(val index: Int, val date: String, val message: String, val expectedDebt: Double? = null)

    /** One payment with everything the app works out for it. */
    data class Payment(
        val index: Int,
        val date: Long,
        val interest: Double?,
        val fee: Double?,
        val interestAndFee: Double,
        val principal: Double,
        /** The payment in the lender's table. */
        val bankPayment: Double,
        /** The fee discount the table leaves out (0 when none). */
        val discount: Double,
        /** What is actually paid: [bankPayment] minus [discount]. */
        val youPay: Double,
        /** [youPay] after the tax deduction on interest and fee. */
        val afterTax: Double,
        val debtAfter: Double,
        /** After the first rate reset the lender's figures are only estimates. */
        val estimate: Boolean
    )

    data class YearTotal(
        val year: Int,
        val interest: Double,
        val fee: Double,
        val interestAndFee: Double,
        val principal: Double,
        val discount: Double,
        val payments: Int,
        /** How many of this year's payments are estimates. */
        val estimates: Int
    )

    fun check(loan: Loan): List<Issue> {
        val rows = loan.schedule
        if (rows.isEmpty()) return emptyList()
        val issues = mutableListOf<Issue>()
        rows.forEachIndexed { i, row ->
            if (parse(row.date) == null) issues += Issue(i, row.date, "The date couldn't be read.")
            if (abs(row.interestAndFee + row.principal - row.payment) > TOLERANCE) {
                issues += Issue(i, row.date, "Interest & fee plus repayment doesn't equal the payment.")
            }
            val before = if (i == 0) loan.originalAmount else rows[i - 1].debtAfter
            if (before != null && abs(before - row.principal - row.debtAfter) > TOLERANCE) {
                issues += Issue(i, row.date, "The debt left doesn't follow from the row before.", before - row.principal)
            }
        }
        loan.totalPayments?.let { total ->
            if (total != rows.size) {
                issues += Issue(rows.lastIndex, rows.last().date, "The document says $total payments, but ${rows.size} were read.")
            } else if (abs(rows.last().debtAfter) > TOLERANCE) {
                issues += Issue(rows.lastIndex, rows.last().date, "The last payment should leave no debt.")
            }
        }
        return issues
    }

    /**
     * Adds what a loan document says to a loan that's already set up (linked to its payment,
     * shared with the household, a home value…): the table and the terms from the document,
     * keeping everything the person set themselves.
     */
    fun withDocument(loan: Loan, read: Loan): Loan = loan.copy(
        schedule = read.schedule.ifEmpty { loan.schedule },
        totalPayments = read.totalPayments ?: loan.totalPayments,
        rateResets = read.rateResets.ifEmpty { loan.rateResets },
        discountName = read.discountName.ifBlank { loan.discountName },
        discountPct = read.discountPct ?: loan.discountPct,
        discountUntil = read.discountUntil.ifBlank { loan.discountUntil },
        taxDeductionPct = read.taxDeductionPct ?: loan.taxDeductionPct,
        setupCosts = read.setupCosts ?: loan.setupCosts,
        totalInterestAndFees = read.totalInterestAndFees ?: loan.totalInterestAndFees,
        totalRepayable = read.totalRepayable ?: loan.totalRepayable,
        aopPct = read.aopPct ?: loan.aopPct,
        interestRatePct = read.interestRatePct ?: loan.interestRatePct,
        contributionRatePct = read.contributionRatePct ?: loan.contributionRatePct,
        originalAmount = loan.originalAmount ?: read.originalAmount,
        paymentsPerYear = loan.paymentsPerYear ?: read.paymentsPerYear,
        payoutDate = loan.payoutDate.ifBlank { read.payoutDate },
        // The table's first date is the real one.
        firstPaymentDate = read.schedule.firstOrNull()?.date ?: loan.firstPaymentDate.ifBlank { read.firstPaymentDate },
        endDate = loan.endDate.ifBlank { read.endDate },
        nextRateReset = read.nextRateReset.ifBlank { loan.nextRateReset },
        earlyRepayment = read.earlyRepayment.ifBlank { loan.earlyRepayment }
    )

    /** Sets the debt on row [index] to [debt] (an [Issue]'s expected value). */
    fun withDebt(loan: Loan, index: Int, debt: Double): Loan =
        loan.copy(schedule = loan.schedule.mapIndexed { i, row -> if (i == index) row.copy(debtAfter = debt) else row })

    /** The share of interest and fee the person gets back through tax. */
    fun taxRate(loan: Loan): Double = loan.taxDeductionPct?.div(100) ?: Mortgage.TAX_DEDUCTION_RATE

    fun payments(loan: Loan): List<Payment> = loan.schedule.mapIndexedNotNull { i, row -> payment(loan, i, row) }

    private fun payment(loan: Loan, index: Int, row: LoanPayment): Payment? {
        val date = parse(row.date) ?: return null
        val interestPct = loan.interestRatePct?.takeIf { it > 0 }
        val feePct = loan.contributionRatePct?.takeIf { it >= 0 }
        val ratePct = if (interestPct != null && feePct != null) interestPct + feePct else null
        // Interest and fee are both a % of the same debt over the same days, so they split by their rates.
        val fee = ratePct?.let { row.interestAndFee * feePct!! / it }
        val discount = discount(loan, row, date, ratePct, index)
        val youPay = row.payment - discount
        return Payment(
            index = index, date = date,
            interest = fee?.let { row.interestAndFee - it }, fee = fee,
            interestAndFee = row.interestAndFee, principal = row.principal,
            bankPayment = row.payment, discount = discount, youPay = youPay,
            afterTax = youPay - (row.interestAndFee - discount) * taxRate(loan),
            debtAfter = row.debtAfter, estimate = isEstimate(loan, date)
        )
    }

    /** The discount on one payment: like interest it's a % a year of the debt, so it scales with
     * the interest and fee (which also covers a short first period). */
    private fun discount(loan: Loan, row: LoanPayment, date: Long, ratePct: Double?, index: Int): Double {
        val pct = loan.discountPct?.takeIf { it > 0 } ?: return 0.0
        AttentionChecks.parseDate(loan.discountUntil)?.let { if (date > it) return 0.0 }
        if (ratePct != null) return row.interestAndFee * pct / ratePct
        val debtBefore = if (index == 0) loan.originalAmount ?: (row.debtAfter + row.principal) else loan.schedule[index - 1].debtAfter
        return debtBefore * pct / 100 / (loan.paymentsPerYear ?: 4)
    }

    fun isEstimate(loan: Loan, date: Long): Boolean {
        val first = resetDates(loan).firstOrNull() ?: return false
        return date > first
    }

    /** The next payment on or after today. */
    fun nextPayment(loan: Loan, now: Long): Payment? = payments(loan).firstOrNull { it.date >= startOfDay(now) }

    /** The latest payment already due. */
    fun lastPayment(loan: Loan, now: Long): Payment? = payments(loan).lastOrNull { it.date < startOfDay(now) }

    fun paidCount(loan: Loan, now: Long): Int = payments(loan).count { it.date < startOfDay(now) }

    /** Debt left today: after the last payment due, else the amount borrowed. */
    fun debtNow(loan: Loan, now: Long): Double? = lastPayment(loan, now)?.debtAfter ?: loan.originalAmount ?: loan.remainingDebt

    /** Debt left on [date] (after the last payment before it). */
    fun debtOn(loan: Loan, date: Long): Double? = payments(loan).lastOrNull { it.date < date }?.debtAfter

    /** The next rate reset still to come: from the list, else the single date entered. */
    fun nextRateReset(loan: Loan, now: Long): Long? =
        resetDates(loan).firstOrNull { it >= startOfDay(now) }
            ?: AttentionChecks.parseDate(loan.nextRateReset)?.takeIf { it >= startOfDay(now) }

    fun resetDates(loan: Loan): List<Long> = loan.rateResets.mapNotNull(::parse).sorted()

    fun yearOf(time: Long): Int = Calendar.getInstance(UTC).apply { timeInMillis = time }.get(Calendar.YEAR)

    fun yearTotals(loan: Loan): List<YearTotal> = payments(loan)
        .groupBy { yearOf(it.date) }
        .map { (year, list) ->
            YearTotal(
                year, list.sumOf { it.interest ?: 0.0 }, list.sumOf { it.fee ?: 0.0 }, list.sumOf { it.interestAndFee },
                list.sumOf { it.principal }, list.sumOf { it.discount }, list.size, list.count { it.estimate }
            )
        }

    /** A short summary for the AI advisor, instead of every row. */
    fun summary(loan: Loan, now: Long): String? {
        if (loan.schedule.isEmpty()) return null
        val next = nextPayment(loan, now)
        val year = yearTotals(loan).firstOrNull { it.year == yearOf(now) + 1 }
        fun r(v: Double) = Math.round(v).toString()
        return buildString {
            append("${loan.schedule.size} payments ${loan.schedule.first().date} to ${loan.schedule.last().date}, ${paidCount(loan, now)} paid. ")
            debtNow(loan, now)?.let { append("Debt now ${r(it)}. ") }
            next?.let {
                append("Next ${loan.schedule[it.index].date}: table ${r(it.bankPayment)} (interest ${it.interest?.let(::r) ?: "?"}, fee ${it.fee?.let(::r) ?: "?"}, repayment ${r(it.principal)})")
                if (it.discount > 0) append(", discount ${r(it.discount)}, paid ${r(it.youPay)}")
                append(", after tax ${r(it.afterTax)}. ")
            }
            year?.let { append("${it.year}: interest & fee ${r(it.interestAndFee)}, repayment ${r(it.principal)}. ") }
            if (loan.rateResets.isNotEmpty()) append("Rows after the first rate reset are the lender's estimates.")
        }.trim()
    }

    fun parse(raw: String): Long? = AttentionChecks.parseDate(raw)

    private fun startOfDay(time: Long): Long = time - Math.floorMod(time, DAY)
}
