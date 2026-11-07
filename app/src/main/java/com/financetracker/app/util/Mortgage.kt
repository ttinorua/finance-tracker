package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.util.advisor.AttentionChecks
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

    /** The interest alone: the annuity is worked out on this; bidrag is charged on top. */
    fun interestRateOf(loan: Loan): Double = (loan.interestRatePct ?: 0.0) / 100
    fun contributionRateOf(loan: Loan): Double = (loan.contributionRatePct ?: 0.0) / 100

    /** Months between payments: 3 for quarterly. */
    fun periodMonths(loan: Loan): Int = (12 / (loan.paymentsPerYear ?: 12).coerceIn(1, 12)).coerceAtLeast(1)

    /** The loan's end date, when the document or the person gave one. */
    fun endDateOf(loan: Loan): Long? = AttentionChecks.parseDate(loan.endDate)

    /** Whole months from [from] to [to] (0 when [to] is earlier). */
    fun monthsBetween(from: Long, to: Long): Int {
        val a = Calendar.getInstance(utc).apply { timeInMillis = from }
        val b = Calendar.getInstance(utc).apply { timeInMillis = to }
        val months = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + b.get(Calendar.MONTH) - a.get(Calendar.MONTH)
        return months.coerceAtLeast(0)
    }

    /**
     * What the loan costs per month (what to set aside): the cost per month given, else an older
     * build's per-payment amount spread out, else worked out from the debt and the end date.
     */
    fun plannedMonthly(loan: Loan, now: Long): Double? {
        scheduleMonthly(loan, now)?.let { return it }
        loan.monthlyPayment?.takeIf { it > 0 }?.let { return it }
        loan.paymentAmount?.takeIf { it > 0 }?.let { return it / periodMonths(loan) }
        val debt = loan.remainingDebt ?: return null
        val end = endDateOf(loan) ?: return null
        val months = monthsBetween(now, end).takeIf { it > 0 } ?: return null
        return paymentFor(debt, interestRateOf(loan), months) + debt * contributionRateOf(loan) / 12
    }

    /** From the lender's table: the next full payment (not a short first one), per month. */
    private fun scheduleMonthly(loan: Loan, now: Long): Double? {
        if (loan.schedule.isEmpty()) return null
        val upcoming = LoanSchedule.payments(loan).filter { it.date >= now - DAY }
        val full = upcoming.firstOrNull { it.index > 0 } ?: upcoming.firstOrNull() ?: return null
        return full.youPay / periodMonths(loan)
    }

    /** A full payment (a quarterly one is three months' worth). */
    fun plannedPayment(loan: Loan, now: Long): Double? = plannedMonthly(loan, now)?.times(periodMonths(loan))

    /** Months the payment on [due] covers: the first one only the months since the payout, the rest a full period. */
    fun monthsCovered(loan: Loan, due: Long): Int {
        val period = periodMonths(loan)
        val first = LoanSchedule.payments(loan).firstOrNull()?.date ?: AttentionChecks.parseDate(loan.firstPaymentDate) ?: return period
        val payout = AttentionChecks.parseDate(loan.payoutDate) ?: return period
        if (Math.abs(due - first) > DAY) return period
        return monthsBetween(payout, first).coerceIn(1, period)
    }

    /** What the payment on [due] costs: the months it covers times the cost per month (the first one's exact amount when given). */
    fun paymentOn(loan: Loan, due: Long, now: Long): Double? {
        LoanSchedule.payments(loan).firstOrNull { Math.abs(it.date - due) <= DAY }?.let { return it.youPay }
        val first = AttentionChecks.parseDate(loan.firstPaymentDate)
        if (first != null && Math.abs(due - first) <= DAY) loan.firstPaymentAmount?.takeIf { it > 0 }?.let { return it }
        return plannedMonthly(loan, now)?.times(monthsCovered(loan, due))
    }

    /** The next payment on or after today and its amount. */
    fun nextPayment(loan: Loan, now: Long): Pair<Long, Double>? {
        val due = nextPaymentDate(loan, now) ?: return null
        return paymentOn(loan, due, now)?.let { due to it }
    }

    /** The next payment date on or after today, from the first payment date and the rhythm. */
    fun nextPaymentDate(loan: Loan, now: Long): Long? {
        // The lender's table has the real dates (e.g. the 11th of Mar/Jun/Sep/Dec).
        if (loan.schedule.isNotEmpty()) return LoanSchedule.nextPayment(loan, now)?.date
        val first = AttentionChecks.parseDate(loan.firstPaymentDate) ?: return null
        val today = now - Math.floorMod(now, DAY)
        val period = periodMonths(loan)
        var k = 0
        while (k < 1200) {
            // Always from the first date, so 31 Dec → 31 Mar → 30 Jun → 30 Sep → 31 Dec doesn't drift.
            val d = Calendar.getInstance(utc).apply { timeInMillis = first; add(Calendar.MONTH, k * period) }.timeInMillis
            if (d >= today) return d
            k++
        }
        return null
    }

    /**
     * Loans paid from [accountId] that the shared bills should plan for: the cost per month, a full
     * payment, the next due date (next month when no date is known yet) and what that one costs.
     */
    fun plannedBills(loans: List<Loan>, accountId: Long?, now: Long): List<PlannedBill> =
        if (accountId == null) emptyList()
        else loans.filter { it.paidFromAccountId == accountId }.mapNotNull { loan ->
            val full = plannedPayment(loan, now)?.takeIf { it > 0 } ?: return@mapNotNull null
            val next = nextPayment(loan, now)
            PlannedBill(
                name = loan.name.ifBlank { "Mortgage" },
                merchant = loan.paymentMerchant,
                amount = full,
                everyMonths = periodMonths(loan),
                nextDue = next?.first ?: FinanceAnalysis.monthStart(now, 1),
                nextAmount = next?.second ?: full,
                nextCovers = next?.first?.let { monthsCovered(loan, it) } ?: periodMonths(loan)
            )
        }

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


    /**
     * The loan's first payment once it arrives, to link it by itself: a payment from the account the
     * loan is paid from, to a lender, near the planned amount (within a third), from 3 weeks before
     * the first payment date on. Null until there is one.
     */
    fun findFirstPayment(loan: Loan, transactions: List<TransactionWithDetails>, now: Long): String? {
        if (loan.paymentMerchant.isNotBlank()) return null
        // A loan shared from the partner's phone without the account here: that phone links it.
        if (loan.paidFromAccountId == null && loan.sharedBy.isNotBlank()) return null
        val firstDate = LoanSchedule.payments(loan).firstOrNull()?.date ?: AttentionChecks.parseDate(loan.firstPaymentDate)
        val planned = (firstDate?.let { paymentOn(loan, it, now) } ?: plannedPayment(loan, now)) ?: return null
        val from = firstDate?.minus(21 * DAY) ?: loan.debtAsOf ?: return null
        val lender = loan.lender.lowercase().split(Regex("[^a-zæøå0-9]+")).filter { it.length >= 4 && it != "bank" }
        return transactions.firstOrNull { tx ->
            tx.type == TransactionType.EXPENSE && tx.date >= from &&
                (loan.paidFromAccountId == null || tx.accountId == loan.paidFromAccountId) &&
                kotlin.math.abs(tx.amount - planned) <= planned / 3 &&
                FinanceAnalysis.merchantOf(tx.note).let { m -> lender.any { it in m } || LENDER_WORDS.any { it in m } }
        }?.let { FinanceAnalysis.merchantOf(it.note) }
    }

    fun status(loan: Loan, transactions: List<TransactionWithDetails>, now: Long): MortgageStatus {
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
            val months = prev?.let { ((p.date - it) / (30.44 * DAY)).roundToInt().coerceIn(1, 12) }
                ?: if (loan.paymentsPerYear != null) monthsCovered(loan, p.date) else guessMonths(all)
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

        val period = if (loan.paymentsPerYear != null) periodMonths(loan) else guessMonths(all)
        // The cost per month as given until there are two payments (the first may be a short one).
        val monthly = when {
            all.size >= 2 -> all.last().amount / period
            else -> plannedMonthly(loan, now) ?: all.lastOrNull()?.let { it.amount / monthsCovered(loan, it.date) } ?: 0.0
        }
        val debt = running
        // The loan's own end date when known; else worked out: the annuity is on the interest
        // alone, and bidrag on the debt comes on top of it.
        val monthsLeft = endDateOf(loan)?.let { monthsBetween(now, it) }
            ?: monthsToPayOff(debt, interestRateOf(loan), monthly - debt * contribution / 12)
        val estimated = MortgageStatus(
            debt = debt,
            monthlyPayment = monthly,
            year = MortgageYear(paidSum, interestSum, contributionSum, repaymentSum),
            monthsLeft = monthsLeft,
            lastPaymentDate = all.lastOrNull()?.date,
            paymentsSinceExact = after.size
        )
        return fromSchedule(loan, estimated, now)
    }

    /**
     * With the lender's table, the debt, this year's split and the months left come from it
     * instead of estimates, until a rate reset has passed (the table's figures after that are
     * only the lender's guess, so the payments themselves are followed again).
     */
    private fun fromSchedule(loan: Loan, estimated: MortgageStatus, now: Long): MortgageStatus {
        val rows = LoanSchedule.payments(loan)
        if (rows.isEmpty()) return estimated
        val last = LoanSchedule.lastPayment(loan, now)
        if (last?.estimate == true) return estimated
        val year = LoanSchedule.yearOf(now)
        val paid = rows.filter { it.date < now && LoanSchedule.yearOf(it.date) == year }
        val end = rows.last().date
        return estimated.copy(
            debt = last?.debtAfter ?: loan.originalAmount ?: estimated.debt,
            monthlyPayment = scheduleMonthly(loan, now) ?: estimated.monthlyPayment,
            year = MortgageYear(
                paid = paid.sumOf { it.youPay },
                interest = paid.sumOf { it.interest ?: it.interestAndFee },
                contribution = paid.sumOf { (it.fee ?: 0.0) - it.discount },
                repayment = paid.sumOf { it.principal }
            ),
            monthsLeft = monthsBetween(now, end),
            lastPaymentDate = estimated.lastPaymentDate ?: last?.date
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

    /**
     * Paying [extra] a month on top of the annuity part [monthly] (the payment without bidrag) at
     * [yearlyRate]. [monthsLeft] = the loan's real months left, when known; the months saved are
     * taken off that.
     */
    fun extraPayment(debt: Double, yearlyRate: Double, monthly: Double, extra: Double, monthsLeft: Int? = null): ExtraPaymentEffect? {
        val base = monthsToPayOff(debt, yearlyRate, monthly) ?: return null
        val faster = monthsToPayOff(debt, yearlyRate, monthly + extra) ?: return null
        val saved = (monthly * base - debt) - ((monthly + extra) * faster - debt)
        val earlier = base - faster
        return ExtraPaymentEffect(((monthsLeft ?: base) - earlier).coerceAtLeast(0), earlier, saved.coerceAtLeast(0.0))
    }

    /** The annuity part of the payment (without bidrag), per month. */
    fun annuityMonthly(loan: Loan, status: MortgageStatus): Double =
        status.monthsLeft?.takeIf { it > 0 }?.let { paymentFor(status.debt, interestRateOf(loan), it) }
            ?: (status.monthlyPayment - status.debt * contributionRateOf(loan) / 12)

    /** The monthly payment (with bidrag) at the interest rate plus [deltaPct] points, over the months left. */
    fun paymentAfterReset(loan: Loan, status: MortgageStatus, deltaPct: Double): Double? {
        val months = status.monthsLeft?.takeIf { it > 0 } ?: return null
        return paymentFor(status.debt, interestRateOf(loan) + deltaPct / 100, months) + status.debt * contributionRateOf(loan) / 12
    }

    fun yearlyRateOf(loan: Loan): Double = yearlyRate(loan)

    /** Friværdi: the home's value minus the debt (the whole, before any ownership share). */
    fun equity(loan: Loan, status: MortgageStatus): Double? = loan.homeValue?.let { it - status.debt }

    /** Words in a bank loan's name or type that say it's part of the home. */
    private val HOME_WORDS = listOf("bolig", "pantebrev", "home", "house", "hus", "ejerlejlighed")

    /** A bank loan whose name or type sounds like the home's (the suggestion for "Part of the home"). */
    fun soundsLikeHome(loan: Loan): Boolean =
        listOf(loan.name, loan.loanType).any { text -> HOME_WORDS.any { text.contains(it, ignoreCase = true) } }

    /** Part of the home: a mortgage, or a bank loan marked (or, when not chosen, sounding) so. */
    fun isHomeLoan(loan: Loan): Boolean =
        loan.kind == "mortgage" || (loan.kind == "bank_loan" && (loan.partOfHome ?: soundsLikeHome(loan)))

    /** The loan that holds the home's value and ownership share: the first mortgage, else the first home loan. */
    fun mainHomeLoan(homeLoans: List<Loan>): Loan? = homeLoans.firstOrNull { it.kind == "mortgage" } ?: homeLoans.firstOrNull()

    /** Friværdi of the whole home: its value minus every home loan's debt. */
    fun homeEquity(homeValue: Double?, debts: List<Double>): Double? = homeValue?.let { it - debts.sum() }

    fun afterTax(year: MortgageYear, loan: Loan? = null): Double =
        (year.interest + year.contribution) * (1 - (loan?.let(LoanSchedule::taxRate) ?: TAX_DEDUCTION_RATE))

    /** [now] plus [months] months, for the debt-free date. */
    fun dateAfter(now: Long, months: Int): Long = Calendar.getInstance(utc).apply { timeInMillis = now; add(Calendar.MONTH, months) }.timeInMillis
}
