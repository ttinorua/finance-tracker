package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.advisor.FinanceAnalysis
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Who a payment into the shared bills account came from. */
enum class Contributor { ME, PARTNER, NOT_A_SHARE }

/** How often a typed-in bill is paid. */
enum class BillCadence(val label: String, val perYear: Int) {
    MONTHLY("monthly", 12),
    QUARTERLY("quarterly", 4),
    HALF_YEARLY("twice a year", 2),
    YEARLY("yearly", 1)
}

/** A shared bill the partner pays by card, typed in because the app can't see their accounts. */
data class PartnerBill(val id: String, val name: String, val amount: Double, val cadence: BillCadence) {
    val monthly: Double get() = amount * cadence.perYear / 12
}

/** One line of the bill total: a name and what it costs per month. */
data class SharedLine(val name: String, val monthly: Double)

/** One payment into the bills account and who it's counted for (null: the app doesn't know yet,
 * so it asks "Who sent this?"). */
data class SharedDeposit(val transaction: TransactionWithDetails, val from: Contributor?, val reason: String)

/** A quarterly or yearly bill coming up that the account won't have enough for. */
data class SharedBillWarning(val label: String, val amount: Double, val dueDate: Long, val shortBy: Double, val extraPerMonth: Double)

/** A card payment for a shared bill (from your accounts, or reported by the partner's phone). */
data class CardPayment(val name: String, val amount: Double, val date: Long)

/** What the partner's phone shares through the Household, when they've joined (2a–2c). */
data class PartnerReport(
    val netIncome: Double,
    val bills: List<SharedLine>,
    val cardPayments: List<CardPayment>,
    /** Money that left the partner's accounts as a transfer: matched against deposits. */
    val transfersOut: List<CardPayment>
)

/** A recurring bill on your own accounts that could be shared (ticked in Shared bills). */
data class SharedCandidate(val merchant: String, val monthly: Double, val label: String, val accountName: String?)

/** Everything [SharedBills.status] needs, gathered from the settings and the transactions. */
data class SharedBillsInput(
    val billsAccountId: Long,
    val transactions: List<TransactionWithDetails>,
    val balance: Double,
    val myNetIncome: Double,
    val partnerNetIncome: Double,
    val partnerName: String,
    val answers: Map<String, Contributor> = emptyMap(),
    /** Your recurring bills ticked as shared (merchant keys). */
    val sharedMerchants: Set<String> = emptySet(),
    /** Merchants you've marked fixed elsewhere: candidates for ticking even before they've repeated 3 times. */
    val fixedMerchants: Set<String> = emptySet(),
    val partnerBills: List<PartnerBill> = emptyList(),
    /** Your share locked in %, or null to follow the incomes. */
    val lockedMySharePct: Double? = null,
    /** When the split was set up; null = the bills account's first transaction. */
    val startedAt: Long? = null,
    /** Targets saved when each month ended ("yyyy-MM" → mine to partner's). */
    val history: Map<String, Pair<Double, Double>> = emptyMap(),
    /** The partner's own figures, when they share them through the Household. */
    val partner: PartnerReport? = null,
    val now: Long
)

data class SharedBillsStatus(
    val myShare: Double,
    val shareLocked: Boolean,
    val myIncome: Double,
    val partnerIncome: Double,
    val partnerConnected: Boolean,
    /** Paid out of the bills account per month (averaged over up to 12 months). */
    val budgetMonthly: Double,
    /** How much history the average rests on; under 3 months it's rough. */
    val budgetHistoryMonths: Double,
    val budgetPayees: List<SharedLine>,
    val myBills: List<SharedLine>,
    /** Ticked bills that are now paid from the bills account, so not counted twice. */
    val movedToBudget: List<String>,
    val partnerBills: List<SharedLine>,
    val monthlyBills: Double,
    val myTarget: Double,
    val partnerTarget: Double,
    val myCardThisMonth: Double,
    val partnerCardThisMonth: Double,
    val myPaidInThisMonth: Double,
    val partnerPaidInThisMonth: Double,
    val depositsThisMonth: List<SharedDeposit>,
    val startMonth: Long,
    /** Paid (transfers + shared card bills) minus targets since [startMonth]: positive = ahead. */
    val myBalance: Double,
    val partnerBalance: Double,
    val monthsCounted: Int,
    /** When you're paying more than your share: one of your card bills to hand over. */
    val handOver: SharedLine?,
    val warnings: List<SharedBillWarning>
) {
    val partnerShare: Double get() = 1 - myShare
    val hasTarget: Boolean get() = monthlyBills > 0
    val budgetIsRough: Boolean get() = budgetHistoryMonths < 3
    val unknown: List<SharedDeposit> get() = depositsThisMonth.filter { it.from == null }
    /** What to transfer this month: the target minus shared bills already paid by card. */
    val myToTransfer: Double get() = (myTarget - myCardThisMonth).coerceAtLeast(0.0)
    val partnerToTransfer: Double get() = (partnerTarget - partnerCardThisMonth).coerceAtLeast(0.0)
    val myShortThisMonth: Double get() = (myToTransfer - myPaidInThisMonth).coerceAtLeast(0.0)
    val partnerShortThisMonth: Double get() = (partnerToTransfer - partnerPaidInThisMonth).coerceAtLeast(0.0)
    /** Your average over or under per month since the start (positive = paying more than your share). */
    val myAveragePerMonth: Double get() = if (monthsCounted > 0) myBalance / monthsCounted else 0.0
}

/**
 * The shared bills account: a joint account with no card, funded by both of you by transfer, that
 * pays the household's bills (PBS / Betalingsservice). The bills are split by net income, so each
 * of you pays the same share of what you earn.
 *
 * The bill total is everything paid out of the account (averaged per month), plus the recurring
 * bills on your own accounts you tick as shared (paid by card, because the account has none), plus
 * bills your partner pays by card (typed in, or from their phone once they join the Household).
 * Card bills count as money already paid, so they lower what that person transfers.
 *
 * Who paid in: an earlier answer to "Who sent this?" wins; then a transfer the partner's phone
 * reported; then the partner's name in the text; then the same amount leaving one of your own
 * accounts within a day.
 */
object SharedBills {
    private const val DAY = 24L * 60 * 60 * 1000
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = utc }

    fun monthKey(time: Long): String = synchronized(monthFormat) { monthFormat.format(time) }

    /** How a deposit's sender is remembered: the merchant words of its text. */
    fun senderKey(note: String): String = FinanceAnalysis.merchantOf(note)

    /** Your share by net income, 0..1; 50/50 when either income is unknown. */
    fun myShare(myNetIncome: Double, partnerNetIncome: Double): Double =
        if (myNetIncome <= 0 || partnerNetIncome <= 0) 0.5 else myNetIncome / (myNetIncome + partnerNetIncome)

    /** Average monthly salary over the months you were paid in the last 12 full months, from every
     * account: a short month or one with feriepenge counts once, not as a whole year's trend. */
    fun averageNetSalary(transactions: List<TransactionWithDetails>, now: Long): Double {
        val from = FinanceAnalysis.monthStart(now, -12)
        val to = FinanceAnalysis.monthStart(now)
        val salaries = transactions.filter {
            it.type == TransactionType.INCOME && it.date in from until to && isSalaryCategory(it.mainCategoryName, it.categoryName)
        }
        val months = salaries.map { monthKey(it.date) }.toSet().size
        return if (months == 0) 0.0 else salaries.sumOf { it.amount } / months
    }

    private fun roundTo10(v: Double): Double = (v / 10).roundToLong() * 10.0

    private fun words(name: String) = name.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 3 }

    private fun sameMerchant(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && (a == b || a.startsWith(b) || b.startsWith(a))

    private fun label(merchant: String) = merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /**
     * Recurring bills on your accounts other than [billsAccountId] that could be shared: what the app
     * found repeating (not weekly, not groceries/fuel/parking), plus merchants you've marked fixed
     * even if they haven't repeated often enough yet. Per month, yearly and quarterly ones spread out.
     */
    fun candidates(transactions: List<TransactionWithDetails>, billsAccountId: Long, fixedMerchants: Set<String>, now: Long): List<SharedCandidate> {
        val mine = transactions.filter { it.accountId != billsAccountId && it.type == TransactionType.EXPENSE && !isTransferCategory(it.mainCategoryName, it.categoryName) }
        val found = FinanceAnalysis.recurringPayments(mine, now)
            .filter { it.cadence != FinanceAnalysis.Cadence.WEEKLY && !isHabitCategory(it.category) }
            .map { SharedCandidate(it.merchant, it.monthlyCost, "${label(it.merchant)} · ${it.cadence.label}", it.accountName) }
        val yearAgo = now - 365 * DAY
        val extra = mine.filter { it.date >= yearAgo }.groupBy { FinanceAnalysis.merchantOf(it.note) }
            .filter { (m, _) -> m in fixedMerchants && found.none { f -> f.merchant == m } }
            .map { (m, txs) -> SharedCandidate(m, txs.sumOf { it.amount } / 12, "${label(m)} · fixed", txs.last().accountName) }
        return (found + extra).sortedByDescending { it.monthly }
    }

    /**
     * What this phone shares with the partner's phone through the Household (2a–2c): your net
     * income, your shared card bills and their payments, and the amounts and dates of money you
     * moved out (to match against deposits into the bills account). Nothing else: no other
     * spending, notes or account details.
     */
    fun mySide(
        transactions: List<TransactionWithDetails>,
        billsAccountId: Long?,
        sharedMerchants: Set<String>,
        fixedMerchants: Set<String>,
        now: Long
    ): PartnerReport {
        val accountId = billsAccountId ?: -1L
        val since = now - 400 * DAY
        val bills = candidates(transactions, accountId, fixedMerchants + sharedMerchants, now).filter { it.merchant in sharedMerchants }
        val keys = bills.map { it.merchant }.toSet()
        val mine = transactions.filter { it.accountId != accountId && it.type == TransactionType.EXPENSE && it.date >= since }
        val transferWords = listOf("overf", "transfer", "budget")
        return PartnerReport(
            netIncome = averageNetSalary(transactions, now),
            bills = bills.map { SharedLine(label(it.merchant), it.monthly) },
            cardPayments = mine.filter { FinanceAnalysis.merchantOf(it.note) in keys }.map { CardPayment(label(FinanceAnalysis.merchantOf(it.note)), it.amount, it.date) },
            transfersOut = mine.filter { tx ->
                isTransferCategory(tx.mainCategoryName, tx.categoryName) || transferWords.any { it in tx.note.lowercase() }
            }.map { CardPayment("", it.amount, it.date) }
        )
    }

    fun status(input: SharedBillsInput): SharedBillsStatus {
        val now = input.now
        val partnerWords = words(input.partnerName)
        val onAccount = input.transactions.filter { it.accountId == input.billsAccountId }
        val others = input.transactions.filter { it.accountId != input.billsAccountId }

        // 1a + 1i: everything paid out of the bills account, except money moved back to you or the partner.
        fun isTransferBack(tx: TransactionWithDetails): Boolean =
            isTransferCategory(tx.mainCategoryName, tx.categoryName) ||
                (partnerWords.isNotEmpty() && partnerWords.any { it in tx.note.lowercase() }) ||
                others.any { it.type == TransactionType.INCOME && abs(it.amount - tx.amount) < 0.005 && abs(it.date - tx.date) <= DAY }
        val outflows = onAccount.filter { it.type == TransactionType.EXPENSE && !isTransferBack(it) }
        val firstOnAccount = onAccount.minOfOrNull { it.date }
        val windowStart = maxOf(firstOnAccount ?: now, now - 365 * DAY)
        val historyMonths = ((now - windowStart).toDouble() / (30.44 * DAY)).coerceAtLeast(0.0)
        val divisor = historyMonths.coerceAtLeast(1.0)
        val inWindow = outflows.filter { it.date >= windowStart }
        val budgetMonthly = inWindow.sumOf { it.amount } / divisor
        val budgetPayees = inWindow.groupBy { FinanceAnalysis.merchantOf(it.note) }
            .map { (m, txs) -> SharedLine(label(m.ifBlank { "Other" }), txs.sumOf { it.amount } / divisor) }
            .sortedByDescending { it.monthly }

        // 1a + 1b: your ticked card bills, minus any now paid from the bills account.
        val recentOnBudget = outflows.filter { it.date >= now - 120 * DAY }.map { FinanceAnalysis.merchantOf(it.note) }.toSet()
        val ticked = candidates(input.transactions, input.billsAccountId, input.fixedMerchants + input.sharedMerchants, now)
            .filter { it.merchant in input.sharedMerchants }
        val (moved, myBillsKept) = ticked.partition { c -> recentOnBudget.any { sameMerchant(it, c.merchant) } }
        val myBills = myBillsKept.map { SharedLine(label(it.merchant), it.monthly) }
        val myBillMerchants = myBillsKept.map { it.merchant }.toSet()

        // 1g / 2b: the partner's card bills, from their phone when connected, else typed in.
        val partnerBills = input.partner?.bills ?: input.partnerBills.map { SharedLine(it.name, it.monthly) }
        val monthlyBills = budgetMonthly + myBills.sumOf { it.monthly } + partnerBills.sumOf { it.monthly }

        // 1j / 2a: the % from net incomes, or locked.
        val partnerIncome = input.partner?.netIncome?.takeIf { it > 0 } ?: input.partnerNetIncome
        val share = input.lockedMySharePct?.let { (it / 100).coerceIn(0.0, 1.0) } ?: myShare(input.myNetIncome, partnerIncome)
        val myTarget = roundTo10(monthlyBills * share)
        val partnerTarget = roundTo10(monthlyBills * (1 - share))

        // Who paid in (1e: the partner's name wins over a same-amount match; 2c: their reported transfers are certain).
        val partnerTransfers = input.partner?.transfersOut.orEmpty()
        fun classify(tx: TransactionWithDetails): SharedDeposit {
            input.answers[senderKey(tx.note)]?.let { return SharedDeposit(tx, it, "your answer") }
            if (partnerTransfers.any { abs(it.amount - tx.amount) < 0.005 && abs(it.date - tx.date) <= 2 * DAY }) {
                return SharedDeposit(tx, Contributor.PARTNER, "from ${input.partnerName.ifBlank { "your partner" }}'s phone")
            }
            if (partnerWords.isNotEmpty() && partnerWords.any { it in tx.note.lowercase() }) return SharedDeposit(tx, Contributor.PARTNER, "matched by name")
            val fromMine = others.any { it.type == TransactionType.EXPENSE && abs(it.amount - tx.amount) < 0.005 && abs(it.date - tx.date) <= DAY }
            if (fromMine) return SharedDeposit(tx, Contributor.ME, "from your own account")
            return SharedDeposit(tx, null, "unknown sender")
        }
        val deposits = onAccount.filter { it.type == TransactionType.INCOME }.map(::classify)

        // Card payments for shared bills, per person.
        val myCard = others.filter { it.type == TransactionType.EXPENSE && FinanceAnalysis.merchantOf(it.note) in myBillMerchants }
            .map { CardPayment(it.note, it.amount, it.date) }
        fun partnerCardIn(from: Long, to: Long): Double = input.partner?.cardPayments?.filter { it.date in from until to }?.sumOf { it.amount }
            ?: input.partnerBills.sumOf { it.monthly }

        val monthStart = FinanceAnalysis.monthStart(now)
        val nextMonth = FinanceAnalysis.monthStart(now, 1)
        fun depositsIn(who: Contributor, from: Long, to: Long) = deposits.filter { it.from == who && it.transaction.date in from until to }.sumOf { it.transaction.amount }
        val myCardThisMonth = myCard.filter { it.date >= monthStart }.sumOf { it.amount }

        // 1h + 1l: since the month the split started, each month against the target saved for it.
        val start = FinanceAnalysis.monthStart(input.startedAt ?: firstOnAccount ?: now)
        var myBalance = 0.0
        var partnerBalance = 0.0
        var months = 0
        var m = start
        while (m < nextMonth && months < 120) {
            val end = FinanceAnalysis.monthStart(m, 1)
            val (myT, partnerT) = input.history[monthKey(m)] ?: (myTarget to partnerTarget)
            myBalance += depositsIn(Contributor.ME, m, end) + myCard.filter { it.date in m until end }.sumOf { it.amount } - myT
            partnerBalance += depositsIn(Contributor.PARTNER, m, end) + partnerCardIn(m, end) - partnerT
            months++
            m = end
        }

        // 1d: paying more than your share? Suggest one of your card bills to hand over.
        val overPerMonth = if (months > 0) myBalance / months else 0.0
        val handOver = if (overPerMonth >= 50) myBills.minByOrNull { abs(it.monthly - overPerMonth) }?.takeIf { it.monthly <= overPerMonth * 1.5 } else null

        val recurringOnAccount = FinanceAnalysis.recurringPayments(outflows, now).filter { it.cadence != FinanceAnalysis.Cadence.WEEKLY }
        return SharedBillsStatus(
            myShare = share,
            shareLocked = input.lockedMySharePct != null,
            myIncome = input.myNetIncome,
            partnerIncome = partnerIncome,
            partnerConnected = input.partner != null,
            budgetMonthly = budgetMonthly,
            budgetHistoryMonths = historyMonths,
            budgetPayees = budgetPayees,
            myBills = myBills,
            movedToBudget = moved.map { label(it.merchant) },
            partnerBills = partnerBills,
            monthlyBills = monthlyBills,
            myTarget = myTarget,
            partnerTarget = partnerTarget,
            myCardThisMonth = myCardThisMonth,
            partnerCardThisMonth = partnerCardIn(monthStart, nextMonth),
            myPaidInThisMonth = depositsIn(Contributor.ME, monthStart, nextMonth),
            partnerPaidInThisMonth = depositsIn(Contributor.PARTNER, monthStart, nextMonth),
            depositsThisMonth = deposits.filter { it.transaction.date >= monthStart }.sortedByDescending { it.transaction.date },
            startMonth = start,
            myBalance = myBalance,
            partnerBalance = partnerBalance,
            monthsCounted = months,
            handOver = handOver,
            warnings = warnings(recurringOnAccount, input.balance, myTarget + partnerTarget, now)
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
