package com.financetracker.app.util.advisor

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlin.math.abs

/** How one category's spending in the period compares with a usual period of the same length. */
data class CategoryChange(val name: String, val spent: Double, val usual: Double) {
    val difference: Double get() = spent - usual
}

/**
 * The numbers behind an AI summary, worked out by the app (the AI only puts them into words).
 * "Usual" is the average of the [WINDOWS] periods of the same length just before this one.
 */
data class SummaryFacts(
    val spent: Double,
    /** null when there isn't enough history to compare with. */
    val usualSpent: Double?,
    val income: Double,
    val topCategories: List<Pair<String, Double>>,
    /** Biggest moves against usual, largest first. */
    val changes: List<CategoryChange>,
    /** Overall monthly budget minus this month's spending so far, when a budget is set. */
    val budgetLeft: Double?,
    val daysLeftInMonth: Int
) {
    /** Spending compared with usual, in percent (negative = less than usual). */
    val changePercent: Double? get() = usualSpent?.takeIf { it > 0 }?.let { (spent - it) / it * 100 }
}

object SummaryFactsCalculator {
    const val WINDOWS = 8
    private const val DAY = 24L * 60 * 60 * 1000

    /**
     * [counted] are the transactions that count toward totals (transfers left out, as on Home),
     * already limited to the account being summarized. [from]..[to) is the period.
     */
    fun compute(counted: List<TransactionWithDetails>, from: Long, to: Long, now: Long, overallBudget: Double?): SummaryFacts {
        val length = (to - from).coerceAtLeast(DAY)
        val expenses = counted.filter { it.type == TransactionType.EXPENSE }
        val inPeriod = expenses.filter { it.date in from until to }
        val spent = inPeriod.sumOf { it.amount }
        val income = counted.filter { it.type == TransactionType.INCOME && it.date in from until to }.sumOf { it.amount }

        val earliest = expenses.minOfOrNull { it.date }
        val windows = (1..WINDOWS).map { i -> (from - i * length) to (from - (i - 1) * length) }
            .filter { (start, _) -> earliest != null && start >= earliest - length }
        val usual = windows.takeIf { it.size >= 2 }?.let { list ->
            list.map { (start, end) -> expenses.filter { it.date in start until end }.sumOf { it.amount } }.average()
        }

        fun byCategory(list: List<TransactionWithDetails>) = list.groupBy { it.categoryName ?: "Uncategorized" }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val current = byCategory(inPeriod)
        val changes = if (windows.size < 2) emptyList() else {
            val history = expenses.filter { tx -> windows.any { (s, e) -> tx.date in s until e } }
            val usualBy = byCategory(history).mapValues { it.value / windows.size }
            (current.keys + usualBy.keys).map { CategoryChange(it, current[it] ?: 0.0, usualBy[it] ?: 0.0) }
                .filter { abs(it.difference) >= 1.0 }
                .sortedByDescending { abs(it.difference) }
                .take(4)
        }

        val monthStart = FinanceAnalysis.monthStart(now)
        val nextMonth = FinanceAnalysis.monthStart(now, 1)
        val spentThisMonth = expenses.filter { it.date in monthStart until nextMonth && it.date <= now }.sumOf { it.amount }
        return SummaryFacts(
            spent = spent,
            usualSpent = usual,
            income = income,
            topCategories = current.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            changes = changes,
            budgetLeft = overallBudget?.let { it - spentThisMonth },
            daysLeftInMonth = ((nextMonth - now) / DAY).toInt().coerceAtLeast(0)
        )
    }
}
