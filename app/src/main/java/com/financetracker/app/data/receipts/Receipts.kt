package com.financetracker.app.data.receipts

import com.financetracker.app.data.ai.ParsedReceipt
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.ReceiptMatcher
import com.financetracker.app.util.ReceiptSplits
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Saving receipts and keeping them matched to their bank transactions. */
object Receipts {

    /** Receipts that still wait for their bank transaction are only matched automatically for
     * this long; after that the user can still pick one on the receipt. */
    private const val PENDING_DAYS = 14L

    /** Saves [parsed] (unless it's already there) and returns its id. [transactionId] is the bank
     * transaction the user confirmed, or null to wait for it to arrive with a bank sync. */
    suspend fun save(repository: FinanceRepository, parsed: ParsedReceipt, source: String, transactionId: Long?, split: Boolean): Long {
        repository.findReceipt(source, parsed.externalId)?.let { return it.id }
        val receipt = Receipt(
            source = source,
            externalId = parsed.externalId,
            storeName = parsed.storeName,
            purchasedAt = parsed.purchasedAt,
            total = parsed.total,
            discount = parsed.discount,
            transactionId = transactionId,
            split = split
        )
        val items = parsed.items.mapIndexed { i, item ->
            ReceiptItem(
                receiptId = 0,
                position = i,
                name = item.name,
                quantity = item.quantity,
                amount = item.amount,
                discount = item.discount,
                categoryId = item.categoryId
            )
        }
        return repository.addReceipt(receipt, items)
    }

    /** The payment a receipt most likely belongs to, and the ones to choose from instead: the
     * likely ones first, then other card payments around the date by closeness of amount.
     * [ownTransactionId] (the receipt's current match, if any) stays available. */
    suspend fun matchChoices(
        repository: FinanceRepository,
        total: Double,
        purchasedAt: Long,
        storeName: String,
        ownTransactionId: Long? = null
    ): Pair<TransactionWithDetails?, List<TransactionWithDetails>> {
        val all = repository.observeTransactions().first()
        // A split payment shows as its parts; for matching, the payment itself counts.
        val transactions = all.filter { !ReceiptSplits.isPart(it.id) } +
            all.filter { ReceiptSplits.isPart(it.id) }.groupBy { ReceiptSplits.originalId(it.id) }
                .map { (id, parts) -> parts.first().copy(id = id, amount = parts.sumOf { it.amount }) }
        val taken = repository.observeTransactionIdsWithReceipt().first().toSet() - setOfNotNull(ownTransactionId)
        val likely = ReceiptMatcher.candidates(total, purchasedAt, storeName, transactions, taken)
        val nearby = transactions.filter { tx ->
            tx.type == TransactionType.EXPENSE && tx.id !in taken && likely.none { it.id == tx.id } &&
                tx.date in (purchasedAt - 3 * DAY)..(purchasedAt + 8 * DAY)
        }.sortedBy { abs(it.amount - total) }.take(15)
        return ReceiptMatcher.best(total, purchasedAt, storeName, transactions, taken) to (likely + nearby)
    }

    private const val DAY = 86_400_000L

    /** Matches recent receipts that have no transaction yet, e.g. one shared from the store app
     * right after shopping, before the bank had booked the payment. Called after each bank sync. */
    suspend fun matchPending(repository: FinanceRepository, now: Long = System.currentTimeMillis()): Int {
        val receipts = repository.observeReceipts().first()
        val pending = receipts.filter { it.transactionId == null && it.purchasedAt > now - PENDING_DAYS * 86_400_000L }
        if (pending.isEmpty()) return 0
        val transactions = repository.observeTransactions().first()
        val taken = receipts.mapNotNull { it.transactionId }.toMutableSet()
        var matched = 0
        for (receipt in pending) {
            val tx = ReceiptMatcher.best(receipt.total, receipt.purchasedAt, receipt.storeName, transactions, taken) ?: continue
            repository.updateReceipt(receipt.copy(transactionId = tx.id))
            taken += tx.id
            matched++
        }
        return matched
    }
}
