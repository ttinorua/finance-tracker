package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlin.math.abs

/** Money that left one of your accounts and arrived in another: probably a transfer that isn't
 * categorized as one yet. */
data class LikelyTransfer(val out: TransactionWithDetails, val into: TransactionWithDetails) {
    /** Stable across refreshes, so a dismissed suggestion stays dismissed. */
    val key: String get() = "transfer:${out.id}:${into.id}"
}

/**
 * Pairs an expense on one account with an income of the same amount on another account within
 * [maxDaysApart] days, when neither is already categorized as a transfer. Each transaction is used
 * at most once, closest dates first. Salary is never a transfer. Only the last [days] days are
 * looked at, so old history doesn't flood the suggestions.
 *
 * Only a suggestion: the person confirms before anything is re-categorized.
 */
fun findLikelyTransfers(
    transactions: List<TransactionWithDetails>,
    now: Long,
    days: Int = 60,
    maxDaysApart: Int = 1
): List<LikelyTransfer> {
    val day = 24L * 60 * 60 * 1000
    val since = now - days * day
    val candidates = transactions.filter {
        it.date >= since && it.amount > 0 &&
            !isTransferCategory(it.mainCategoryName, it.categoryName) &&
            !isSalaryCategory(it.mainCategoryName, it.categoryName)
    }
    val ins = candidates.filter { it.type == TransactionType.INCOME }
    val outs = candidates.filter { it.type == TransactionType.EXPENSE }.sortedByDescending { it.date }
    val used = mutableSetOf<Long>()
    val result = mutableListOf<LikelyTransfer>()
    for (out in outs) {
        if (out.id in used) continue
        val match = ins
            .filter {
                it.id !in used && it.accountId != out.accountId &&
                    abs(it.amount - out.amount) < 0.005 && abs(it.date - out.date) <= maxDaysApart * day
            }
            .minByOrNull { abs(it.date - out.date) } ?: continue
        used += out.id
        used += match.id
        result += LikelyTransfer(out, match)
    }
    return result
}
