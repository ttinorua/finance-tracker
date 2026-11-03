package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlin.math.abs

/** One row of a breakdown: a main category (its categories folded together) or a single
 * category. [categoryId] is set only for category rows (null for "Uncategorized"). */
data class BreakdownRow(
    val label: String,
    val mainCategory: String,
    val categoryId: Long?,
    val colorHex: String?,
    val income: Double,
    val expense: Double,
    val count: Int,
    /** Main category rows: its biggest categories, for the line under the name. */
    val topCategories: List<String> = emptyList()
) {
    /** Money in minus money out. */
    val net: Double get() = income - expense

    /** How big the row is, for sorting, bars and shares: income and spending both count. */
    val size: Double get() = income + expense
}

const val UNCATEGORIZED = "Uncategorized"

private fun TransactionWithDetails.main() = mainCategoryName ?: UNCATEGORIZED
private fun TransactionWithDetails.inAmount() = if (type == TransactionType.INCOME) amount else 0.0
private fun TransactionWithDetails.outAmount() = if (type == TransactionType.EXPENSE) amount else 0.0

/** [transactions] by main category, biggest first. */
fun mainCategoryBreakdown(transactions: List<TransactionWithDetails>): List<BreakdownRow> =
    transactions.groupBy { it.main() }.map { (main, txs) ->
        BreakdownRow(
            label = main,
            mainCategory = main,
            categoryId = null,
            colorHex = null,
            income = txs.sumOf { it.inAmount() },
            expense = txs.sumOf { it.outAmount() },
            count = txs.size,
            topCategories = txs.groupBy { it.categoryName ?: UNCATEGORIZED }
                .entries.sortedByDescending { (_, list) -> list.sumOf { abs(it.amount) } }
                .take(3).map { it.key }
        )
    }.sortedByDescending { it.size }

/** [transactions] in [mainCategory], by category, biggest first. */
fun categoryBreakdown(transactions: List<TransactionWithDetails>, mainCategory: String): List<BreakdownRow> =
    transactions.filter { it.main() == mainCategory }.groupBy { it.categoryId }.map { (id, txs) ->
        val first = txs.first()
        BreakdownRow(
            label = first.categoryName ?: UNCATEGORIZED,
            mainCategory = mainCategory,
            categoryId = id,
            colorHex = first.categoryColorHex,
            income = txs.sumOf { it.inAmount() },
            expense = txs.sumOf { it.outAmount() },
            count = txs.size
        )
    }.sortedByDescending { it.size }
