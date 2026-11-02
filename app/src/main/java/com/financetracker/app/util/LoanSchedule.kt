package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoanPayment
import com.financetracker.app.util.advisor.AttentionChecks
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Works with a loan's payment table as the lender printed it (e.g. Totalkredit's afdragsforløb).
 * The table is the source of truth: the app only adds what the lender leaves out (a fee discount
 * such as KundeKroner, the interest/fee split, the tax effect) and checks that the AI copied
 * every row correctly, since each row's debt must follow from the row before.
 */
object LoanSchedule {

    private const val DAY = 24L * 60 * 60 * 1000
    /** Rounding slack when checking rows add up: the lender rounds to øre, the AI might not. */
    private const val TOLERANCE = 0.5

    /** A row that doesn't add up. [expectedDebt] is what the debt should be, if the row before is right. */
    data class Issue(val index: Int, val date: String, val message: String, val expectedDebt: Double? = null)

    /** One payment with everything the app works out for it. Amounts in the loan's currency. */
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
        /** What the user actually pays: [bankPayment] minus [discount]. */
        val youPay: Double,
        /** [youPay] after the tax deduction on interest and fee, if the tax rate is known. */
        val afterTax: Double?,
        val debtAfter: Double,
        /** After the first rate reset, the lender's figures are only estimates. */
        val estimate: Boolean
    )

    data class YearTotal(
        val year: Int,
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
                issues += Issue(i, row.date, "Interest & fee plus paid off doesn't equal the payment.")
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

    /** Fixes the debt on row [index] to [debt] (from an [Issue]'s expected value). */
    fun withDebt(loan: Loan, index: Int, debt: Double): Loan =
        loan.copy(schedule = loan.schedule.mapIndexed { i, row -> if (i == index) row.copy(debtAfter = debt) else row })

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
        val taxPct = loan.taxDeductionPct
            ?: row.paymentAfterTax?.takeIf { row.interestAndFee > 0 }?.let { (row.payment - it) / row.interestAndFee * 100 }
        val afterTax = taxPct?.let { youPay - (row.interestAndFee - discount) * it / 100 }
        return Payment(
            index = index, date = date,
            interest = fee?.let { row.interestAndFee - it }, fee = fee,
            interestAndFee = row.interestAndFee, principal = row.principal,
            bankPayment = row.payment, discount = discount, youPay = youPay, afterTax = afterTax,
            debtAfter = row.debtAfter, estimate = isEstimate(loan, date)
        )
    }

    /** The discount on this payment: like interest, it's a % a year of the debt, so it scales with
     * the interest and fee (that also covers a short first period). */
    private fun discount(loan: Loan, row: LoanPayment, date: Long, ratePct: Double?, index: Int): Double {
        val pct = loan.discountPct?.takeIf { it > 0 } ?: return 0.0
        AttentionChecks.parseDate(loan.discountUntil)?.let { if (date > it) return 0.0 }
        if (ratePct != null) return row.interestAndFee * pct / ratePct
        val debtBefore = if (index == 0) loan.originalAmount ?: (row.debtAfter + row.principal) else loan.schedule[index - 1].debtAfter
        return debtBefore * pct / 100 / loan.paymentMonths.size.takeIf { it > 0 }.let { it ?: 4 }
    }

    fun isEstimate(loan: Loan, date: Long): Boolean {
        val first = loan.rateResets.mapNotNull(::parse).minOrNull() ?: return false
        return date > first
    }

    fun nextPayment(loan: Loan, now: Long): Payment? = payments(loan).firstOrNull { it.date >= startOfDay(now) }

    /** The latest payment already due. */
    fun lastPayment(loan: Loan, now: Long): Payment? = payments(loan).lastOrNull { it.date < startOfDay(now) }

    fun paidCount(loan: Loan, now: Long): Int = payments(loan).count { it.date < startOfDay(now) }

    /** Debt left today: after the last payment due, or as entered. */
    fun debtNow(loan: Loan, now: Long): Double? =
        if (loan.schedule.isEmpty()) loan.remainingDebt else lastPayment(loan, now)?.debtAfter ?: loan.originalAmount ?: loan.remainingDebt

    /** Debt left on [date] (the last payment before it). */
    fun debtOn(loan: Loan, date: Long): Double? = payments(loan).lastOrNull { it.date < date }?.debtAfter

    /** Next due date: from the schedule, else from the payment day and months. */
    fun nextPaymentDate(loan: Loan, now: Long): Long? {
        nextPayment(loan, now)?.let { return it.date }
        if (loan.schedule.isNotEmpty()) return null
        val day = loan.paymentDay ?: return null
        val months = loan.paymentMonths.ifEmpty { (1..12).toList() }
        val today = startOfDay(now)
        val year = Calendar.getInstance(UTC).apply { timeInMillis = now }.get(Calendar.YEAR)
        return (year..year + 1).flatMap { y -> months.sorted().map { m -> dateOf(y, m, day) } }.firstOrNull { it >= today }
    }

    /** The next rate reset still to come: from the reset list, else the single date entered. */
    fun nextRateReset(loan: Loan, now: Long): Long? =
        loan.rateResets.mapNotNull(::parse).filter { it >= startOfDay(now) }.minOrNull()
            ?: AttentionChecks.parseDate(loan.nextRateReset)?.takeIf { it >= startOfDay(now) }

    fun resetDates(loan: Loan): List<Long> = loan.rateResets.mapNotNull(::parse).sorted()

    fun yearTotals(loan: Loan): List<YearTotal> = payments(loan)
        .groupBy { Calendar.getInstance(UTC).apply { timeInMillis = it.date }.get(Calendar.YEAR) }
        .map { (year, list) ->
            YearTotal(year, list.sumOf { it.interestAndFee }, list.sumOf { it.principal }, list.sumOf { it.discount }, list.size, list.count { it.estimate })
        }

    /**
     * Finds the bank transaction that paid [payment]: an expense from a few days before the due
     * date to just after the end of that month (the lender accepts payment until the last bank
     * day), close to what's expected. One that names the lender may differ more (the discount or
     * a rounding can change the amount).
     */
    fun findBankPayment(loan: Loan, payment: Payment, transactions: List<TransactionWithDetails>): TransactionWithDetails? {
        val from = payment.date - 5 * DAY
        val to = endOfMonth(payment.date) + 3 * DAY
        val lenderWord = loan.lender.lowercase().split(' ', ',', '.').firstOrNull { it.length >= 3 }
        return transactions
            .filter { it.type == TransactionType.EXPENSE && it.date in from..to }
            .mapNotNull { tx ->
                val amount = abs(tx.amount)
                val off = minOf(abs(amount - payment.youPay), abs(amount - payment.bankPayment)) / payment.bankPayment
                val named = lenderWord != null && tx.note.lowercase().contains(lenderWord)
                if (off <= (if (named) 0.15 else 0.02)) Triple(tx, named, off) else null
            }
            .sortedWith(compareBy({ !it.second }, { it.third }))
            .firstOrNull()?.first
    }

    /** True once the window for paying [payment] has closed. */
    fun windowClosed(payment: Payment, now: Long): Boolean = now > endOfMonth(payment.date) + 3 * DAY

    /** A short summary for the AI advisor, instead of all the rows. */
    fun summary(loan: Loan, now: Long): String? {
        if (loan.schedule.isEmpty()) return null
        val next = nextPayment(loan, now)
        val thisYear = Calendar.getInstance(UTC).apply { timeInMillis = now }.get(Calendar.YEAR)
        val year = yearTotals(loan).firstOrNull { it.year == thisYear + 1 }
        fun r(v: Double) = Math.round(v).toString()
        return buildString {
            append("${loan.schedule.size} payments ${loan.schedule.first().date} to ${loan.schedule.last().date}, ${paidCount(loan, now)} paid. ")
            debtNow(loan, now)?.let { append("Debt now ${r(it)}. ") }
            next?.let {
                append("Next ${loan.schedule[it.index].date}: table ${r(it.bankPayment)} (interest ${it.interest?.let(::r) ?: "?"}, fee ${it.fee?.let(::r) ?: "?"}, paid off ${r(it.principal)})")
                if (it.discount > 0) append(", discount ${r(it.discount)}, you pay ${r(it.youPay)}")
                it.afterTax?.let { t -> append(", after tax ${r(t)}") }
                append(". ")
            }
            year?.let { append("${it.year}: interest & fee ${r(it.interestAndFee)}, paid off ${r(it.principal)}. ") }
            resetDates(loan).takeIf { it.isNotEmpty() }?.let { append("Figures after the first rate reset are the lender's estimates.") }
        }.trim()
    }

    // ---------------------------------------------------------------- dates (UTC midnight, like transactions)

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun parse(raw: String): Long? = AttentionChecks.parseDate(raw)

    private fun dateOf(year: Int, month: Int, day: Int): Long = Calendar.getInstance(UTC).run {
        clear()
        set(year, month - 1, 1)
        set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
        timeInMillis
    }

    private fun startOfDay(time: Long): Long = time - Math.floorMod(time, DAY)

    private fun endOfMonth(time: Long): Long = Calendar.getInstance(UTC).run {
        timeInMillis = time
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        startOfDay(timeInMillis)
    }
}
