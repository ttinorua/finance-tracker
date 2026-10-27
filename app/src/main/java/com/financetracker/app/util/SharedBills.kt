package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.advisor.FinanceAnalysis
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Who a payment into the shared bills account came from. */
enum class Contributor { ME, PARTNER, NOT_A_SHARE }

/** One payment into the bills account and who it's counted for (null: the app doesn't know yet,
 * so it asks "Who sent this?"). */
data class SharedDeposit(val transaction: TransactionWithDetails, val from: Contributor?, val reason: String)

/** A quarterly or yearly bill coming up that the account won't have enough for. */
data class SharedBillWarning(val label: String, val amount: Double, val dueDate: Long, val shortBy: Double, val extraPerMonth: Double)

data class SharedBillsStatus(
    /** Your share of the bills, 0..1 (net incomes); the partner's is 1 - this. */
    val myShare: Double,
    /** The bills paid from the account, per month, with quarterly and yearly ones spread out. */
    val monthlyBills: Double,
    val myTarget: Double,
    val partnerTarget: Double,
    val bills: List<FinanceAnalysis.RecurringPayment>,
    val myPaidThisMonth: Double,
    val partnerPaidThisMonth: Double,
    val depositsThisMonth: List<SharedDeposit>,
    /** Paid in minus target, January through this month: positive = ahead. */
    val myYearBalance: Double,
    val partnerYearBalance: Double,
    val warnings: List<SharedBillWarning>
) {
    val partnerShare: Double get() = 1 - myShare
    val unknown: List<SharedDeposit> get() = depositsThisMonth.filter { it.from == null }
    val myShortThisMonth: Double get() = (myTarget - myPaidThisMonth).coerceAtLeast(0.0)
    val partnerShortThisMonth: Double get() = (partnerTarget - partnerPaidThisMonth).coerceAtLeast(0.0)
}

/**
 * The shared bills account: a joint account with no card, funded by both of you by transfer, that
 * pays the household's bills (PBS / Betalingsservice). The bills are split by net income, so each
 * of you pays in the same share of what you earn. Everything runs from one phone; the partner
 * doesn't need the app.
 *
 * Who paid in: a deposit that matches money leaving one of your own other accounts (same amount,
 * within a day) is yours; one whose text names your partner is theirs; any earlier answer to "Who
 * sent this?" ([answers], keyed by [senderKey]) wins over both.
 */
object SharedBills {
    private const val DAY = 24L * 60 * 60 * 1000
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** How a deposit's sender is remembered: the merchant words of its text. */
    fun senderKey(note: String): String = FinanceAnalysis.merchantOf(note)

    /** Your share by net income, 0..1; 50/50 when either income is unknown. */
    fun myShare(myNetIncome: Double, partnerNetIncome: Double): Double =
        if (myNetIncome <= 0 || partnerNetIncome <= 0) 0.5 else myNetIncome / (myNetIncome + partnerNetIncome)

    /** Average monthly salary over the last [months] full months, from every account. */
    fun averageNetSalary(transactions: List<TransactionWithDetails>, now: Long, months: Int = 3): Double {
        val from = FinanceAnalysis.monthStart(now, -months)
        val to = FinanceAnalysis.monthStart(now)
        return transactions.filter {
            it.type == TransactionType.INCOME && it.date in from until to && isSalaryCategory(it.mainCategoryName, it.categoryName)
        }.sumOf { it.amount } / months
    }

    private fun roundTo10(v: Double): Double = (v / 10).roundToLong() * 10.0

    fun status(
        billsAccountId: Long,
        transactions: List<TransactionWithDetails>,
        balance: Double,
        myNetIncome: Double,
        partnerNetIncome: Double,
        partnerName: String,
        answers: Map<String, Contributor>,
        now: Long
    ): SharedBillsStatus {
        val onAccount = transactions.filter { it.accountId == billsAccountId }
        val others = transactions.filter { it.accountId != billsAccountId }
        val bills = FinanceAnalysis.recurringPayments(
            onAccount.filter { !isTransferCategory(it.mainCategoryName, it.categoryName) }, now
        ).filter { it.cadence != FinanceAnalysis.Cadence.WEEKLY }
        val monthlyBills = bills.sumOf { it.monthlyCost }
        val share = myShare(myNetIncome, partnerNetIncome)
        val myTarget = roundTo10(monthlyBills * share)
        val partnerTarget = roundTo10(monthlyBills * (1 - share))

        val partnerWords = partnerName.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        fun classify(tx: TransactionWithDetails): SharedDeposit {
            answers[senderKey(tx.note)]?.let { return SharedDeposit(tx, it, "your answer") }
            val fromMine = others.any {
                it.type == TransactionType.EXPENSE && abs(it.amount - tx.amount) < 0.005 && abs(it.date - tx.date) <= DAY
            }
            if (fromMine) return SharedDeposit(tx, Contributor.ME, "from your own account")
            val text = tx.note.lowercase()
            if (partnerWords.isNotEmpty() && partnerWords.any { it in text }) return SharedDeposit(tx, Contributor.PARTNER, "matched by name")
            return SharedDeposit(tx, null, "unknown sender")
        }

        val deposits = onAccount.filter { it.type == TransactionType.INCOME }.map(::classify)
        val monthStart = FinanceAnalysis.monthStart(now)
        val thisMonth = deposits.filter { it.transaction.date >= monthStart }
        fun paid(list: List<SharedDeposit>, who: Contributor) = list.filter { it.from == who }.sumOf { it.transaction.amount }

        val yearStart = Calendar.getInstance(utc).apply {
            timeInMillis = now
            set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthsSoFar = Calendar.getInstance(utc).apply { timeInMillis = now }.get(Calendar.MONTH) + 1
        val thisYear = deposits.filter { it.transaction.date >= yearStart }

        return SharedBillsStatus(
            myShare = share,
            monthlyBills = monthlyBills,
            myTarget = myTarget,
            partnerTarget = partnerTarget,
            bills = bills.sortedByDescending { it.monthlyCost },
            myPaidThisMonth = paid(thisMonth, Contributor.ME),
            partnerPaidThisMonth = paid(thisMonth, Contributor.PARTNER),
            depositsThisMonth = thisMonth.sortedByDescending { it.transaction.date },
            myYearBalance = paid(thisYear, Contributor.ME) - myTarget * monthsSoFar,
            partnerYearBalance = paid(thisYear, Contributor.PARTNER) - partnerTarget * monthsSoFar,
            warnings = warnings(bills, balance, myTarget + partnerTarget, now)
        )
    }

    /**
     * Steps month by month for [horizonMonths]: each month the targets come in and every bill due
     * that month goes out. A quarterly or yearly bill that leaves the account below zero gets a
     * warning, with how much more a month (in total) until then would cover it.
     */
    fun warnings(
        bills: List<FinanceAnalysis.RecurringPayment>,
        balance: Double,
        monthlyIn: Double,
        now: Long,
        horizonMonths: Int = 6
    ): List<SharedBillWarning> {
        val result = mutableListOf<SharedBillWarning>()
        var running = balance
        val due = bills.associateWith { it.nextDue }.toMutableMap()
        for (m in 0 until horizonMonths) {
            val from = FinanceAnalysis.monthStart(now, m)
            val to = FinanceAnalysis.monthStart(now, m + 1)
            if (m > 0) running += monthlyIn
            for (bill in bills) {
                var date = due.getValue(bill)
                while (date < to) {
                    if (date >= from || m == 0) {
                        running -= bill.lastAmount
                        val big = bill.cadence == FinanceAnalysis.Cadence.QUARTERLY || bill.cadence == FinanceAnalysis.Cadence.YEARLY
                        if (big && running < 0 && result.none { it.label == bill.merchant }) {
                            val short = -running
                            result += SharedBillWarning(bill.merchant, bill.lastAmount, date, short, ceil(short / (m + 1) / 10) * 10)
                        }
                    }
                    date = nextAfter(date, bill.cadence)
                }
                due[bill] = date
            }
        }
        return result
    }

    private fun nextAfter(date: Long, cadence: FinanceAnalysis.Cadence): Long = Calendar.getInstance(utc).apply {
        timeInMillis = date
        when (cadence) {
            FinanceAnalysis.Cadence.WEEKLY -> add(Calendar.DAY_OF_MONTH, 7)
            FinanceAnalysis.Cadence.MONTHLY -> add(Calendar.MONTH, 1)
            FinanceAnalysis.Cadence.QUARTERLY -> add(Calendar.MONTH, 3)
            FinanceAnalysis.Cadence.YEARLY -> add(Calendar.YEAR, 1)
        }
    }.timeInMillis
}
