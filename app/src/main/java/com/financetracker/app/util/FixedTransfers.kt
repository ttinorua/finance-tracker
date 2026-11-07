package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.advisor.FinanceAnalysis
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Fixed monthly transfers into the bills account: the same amount from each of you every month,
 * until something big changes. Fair the same way as the split (option B): all shared bills are
 * split by net income, and each person's transfer is their share minus the shared bills they
 * already pay from their own account. Rounded up to 100, so together the transfers always cover
 * what the bills account pays.
 *
 * A change is suggested, within the month, when the transfers are 500 kr or more off what's
 * needed, when a regular bill from the account hasn't been paid this month, or when an income is
 * lower than when the amounts were fixed.
 */
object FixedTransfers {

    /** What changes the amounts by at least this much (kr) is worth asking about. */
    const val THRESHOLD = 500.0
    /** An income this much lower than when the amounts were fixed counts as a cut. */
    const val INCOME_DROP = 0.10

    data class Plan(val mine: Double, val partner: Double) {
        val total: Double get() = mine + partner
    }

    /** A suggested change and why. */
    data class Change(val suggested: Plan, val reasons: List<String>)

    /** The fixed amounts as agreed, and the incomes they were based on. */
    data class Agreed(val plan: Plan, val since: String, val myIncome: Double, val partnerIncome: Double)

    fun ceil100(v: Double): Double = ceil(v.coerceAtLeast(0.0) / 100) * 100

    /**
     * Each person's transfer: their share of all shared bills (minus [leaveOut], e.g. bills that
     * stopped), minus the shared bills they pay from their own account.
     */
    fun suggest(status: SharedBillsStatus, share: Double = status.myShare, leaveOut: Double = 0.0): Plan {
        val myOwn = status.myBills.sumOf { it.monthly }
        val partnerOwn = status.partnerBills.sumOf { it.monthly }
        val all = (status.monthlyBills - leaveOut).coerceAtLeast(0.0)
        return Plan(ceil100(all * share - myOwn), ceil100(all * (1 - share) - partnerOwn))
    }

    /** This month's salary, once it has arrived (0 before). */
    fun salaryThisMonth(transactions: List<TransactionWithDetails>, now: Long): Double {
        val from = FinanceAnalysis.monthStart(now)
        return transactions.filter {
            it.type == TransactionType.INCOME && it.date >= from && isSalaryCategory(it.mainCategoryName, it.categoryName)
        }.sumOf { it.amount }
    }

    /**
     * Whether to suggest new amounts this month, and what. Null when nothing big changed, or when
     * [keptMonth] says the person already chose to keep the amounts this month.
     *
     * @param mySalaryNow this month's salary (0 until it arrives)
     * @param partnerIncomeNow the partner's net income as the app knows it now
     */
    fun check(
        agreed: Agreed,
        status: SharedBillsStatus,
        mySalaryNow: Double,
        partnerIncomeNow: Double,
        partnerName: String,
        thisMonth: String,
        keptMonth: String?,
        money: (Double) -> String
    ): Change? {
        if (keptMonth == thisMonth) return null
        val reasons = mutableListOf<String>()

        // A regular bill not paid this month: the suggestion assumes it has ended.
        val missing = status.missingThisMonth
        missing.forEach { reasons += "${it.name} wasn't paid this month (−${money(it.monthly)}). Ended, or just late?" }

        // An income cut: the split follows this month's income instead of the agreed one.
        val myCut = mySalaryNow > 0 && agreed.myIncome > 0 && mySalaryNow < agreed.myIncome * (1 - INCOME_DROP)
        val partnerCut = partnerIncomeNow > 0 && agreed.partnerIncome > 0 && partnerIncomeNow < agreed.partnerIncome * (1 - INCOME_DROP)
        val share = if (!status.shareLocked && (myCut || partnerCut)) {
            SharedBills.myShare(if (myCut) mySalaryNow else agreed.myIncome, if (partnerCut) partnerIncomeNow else agreed.partnerIncome)
        } else status.myShare
        if (myCut) reasons += "Your salary this month is lower than usual (${money(mySalaryNow)} against ${money(agreed.myIncome)})."
        if (partnerCut) reasons += "$partnerName's income is lower than usual (${money(partnerIncomeNow)} against ${money(agreed.partnerIncome)})."
        if ((myCut || partnerCut) && share != status.myShare) {
            reasons += "The split moves to ${pct(share)} / ${pct(1 - share)}."
        }

        val suggested = suggest(status, share, missing.sumOf { it.monthly })
        // The amount: what the bills account needs against what the fixed transfers bring in.
        val needed = status.budgetMonthly - missing.sumOf { it.monthly }
        val gap = needed - agreed.plan.total
        val amountOff = abs(gap) >= THRESHOLD
        if (amountOff) {
            reasons.add(0, "The bills account needs ${money(abs(gap))} ${if (gap > 0) "more" else "less"} a month than your fixed transfers bring in.")
        }

        val worthAsking = amountOff || missing.isNotEmpty() || myCut || partnerCut
        if (!worthAsking || suggested == agreed.plan) return null
        return Change(suggested, reasons)
    }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"
}
