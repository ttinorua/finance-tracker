package com.financetracker.app.util

import com.financetracker.app.data.db.entity.ReceiptItemDetails
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A bank transaction split by its receipt: instead of one "Netto 412.50" in Groceries, it counts
 * as Groceries 290.45, Household 82.00 and Sweets 40.05. The bank transaction itself is never
 * changed; the parts replace it in the list every screen and total is built from
 * ([com.financetracker.app.data.repository.FinanceRepository.observeTransactions]), so budgets,
 * Spending, Home and the household all count the parts.
 *
 * Parts get negative ids (see [partId]) so they never collide with real ones; [originalId] gets
 * back to the bank transaction.
 */
object ReceiptSplits {
    private const val MAX_PARTS = 100

    fun partId(transactionId: Long, index: Int): Long = -(transactionId * MAX_PARTS + index + 1)
    fun isPart(id: Long): Boolean = id < 0
    fun originalId(id: Long): Long = if (id < 0) (-id - 1) / MAX_PARTS else id

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0

    /** [transactions] with each split one replaced by its parts. */
    fun expand(transactions: List<TransactionWithDetails>, items: List<ReceiptItemDetails>): List<TransactionWithDetails> {
        val byTransaction = items.filter { it.split && it.transactionId != null }.groupBy { it.transactionId!! }
        if (byTransaction.isEmpty()) return transactions
        return transactions.flatMap { tx -> byTransaction[tx.id]?.let { split(tx, it) } ?: listOf(tx) }
    }

    /** [tx] as one part per category of its receipt [items]. The parts always add up to the
     * transaction's amount: a small difference (rounding) goes to the biggest part; a bigger
     * gap the receipt doesn't explain (e.g. a bottle deposit) stays in the transaction's own
     * category; and if the receipt adds up to more than was paid, the parts are scaled down. */
    fun split(tx: TransactionWithDetails, items: List<ReceiptItemDetails>): List<TransactionWithDetails> {
        data class Group(val categoryId: Long?, val name: String?, val main: String?, val colour: String?, var amount: Double)
        val groups = items.groupBy { it.categoryId ?: tx.categoryId }.map { (categoryId, list) ->
            val first = list.first()
            if (first.categoryId == null) {
                Group(tx.categoryId, tx.categoryName, tx.mainCategoryName, tx.categoryColorHex, list.sumOf { it.amount })
            } else {
                Group(categoryId, first.categoryName, first.mainCategoryName, first.categoryColorHex, list.sumOf { it.amount })
            }
        }.filter { it.amount > 0 }.sortedByDescending { it.amount }.toMutableList()
        if (groups.isEmpty()) return listOf(tx)

        val itemsTotal = groups.sumOf { it.amount }
        val gap = tx.amount - itemsTotal
        when {
            abs(gap) <= 1.0 -> Unit
            gap > 0 -> {
                val own = groups.firstOrNull { it.categoryId == tx.categoryId }
                if (own != null) own.amount += gap
                else groups += Group(tx.categoryId, tx.categoryName, tx.mainCategoryName, tx.categoryColorHex, gap)
            }
            else -> groups.forEach { it.amount = it.amount * tx.amount / itemsTotal }
        }
        groups.forEach { it.amount = round2(it.amount) }
        // Whatever rounding left over goes on the biggest part, so the parts add up exactly.
        val biggest = groups.maxBy { it.amount }
        biggest.amount = round2(biggest.amount + (tx.amount - groups.sumOf { it.amount }))

        if (groups.size == 1 && groups[0].categoryId == tx.categoryId) return listOf(tx)
        return groups.take(MAX_PARTS).mapIndexed { i, g ->
            tx.copy(
                id = partId(tx.id, i),
                amount = g.amount,
                categoryId = g.categoryId,
                categoryName = g.name,
                mainCategoryName = g.main,
                categoryColorHex = g.colour
            )
        }
    }
}
