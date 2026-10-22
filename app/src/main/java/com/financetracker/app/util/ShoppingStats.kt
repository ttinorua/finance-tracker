package com.financetracker.app.util

import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItemDetails

/** The Shopping page's figures for one month, from the receipts. */
object ShoppingStats {

    data class Line(val key: String, val label: String, val total: Double, val count: Int, val categoryId: Long? = null, val mainCategory: String? = null, val colorHex: String? = null)

    /** [name] costs [now] a unit at [store], up from [before] the last time (on [beforeAt]). */
    data class PriceChange(val name: String, val store: String, val now: Double, val before: Double, val beforeAt: Long) {
        val percent: Double get() = (now - before) / before * 100
    }

    private const val MIN_RISE_PCT = 5.0
    private const val MIN_RISE = 1.0

    fun inRange(t: Long, range: Pair<Long, Long>) = t >= range.first && t < range.second

    /** Receipts paid from [accountId] (null = all accounts), going by the payment each receipt
     * matched. A receipt not matched to a payment only shows under All accounts. */
    fun onAccount(receipts: List<Receipt>, accountOfPayment: Map<Long, Long>, accountId: Long?): List<Receipt> =
        if (accountId == null) receipts else receipts.filter { r -> r.transactionId?.let { accountOfPayment[it] } == accountId }

    fun byStore(receipts: List<Receipt>, range: Pair<Long, Long>): List<Line> =
        receipts.filter { inRange(it.purchasedAt, range) }
            .groupBy { it.storeName.trim().lowercase() }
            .map { (key, list) -> Line(key, list.first().storeName.trim(), list.sumOf { it.total }, list.size) }
            .sortedByDescending { it.total }

    fun byCategory(items: List<ReceiptItemDetails>, range: Pair<Long, Long>): List<Line> =
        items.filter { inRange(it.purchasedAt, range) }
            .groupBy { it.categoryId }
            .map { (id, list) ->
                val first = list.first()
                Line("c$id", first.categoryName ?: "Other", list.sumOf { it.amount }, list.size, id, first.mainCategoryName, first.categoryColorHex)
            }
            .sortedByDescending { it.total }

    fun byItem(items: List<ReceiptItemDetails>, range: Pair<Long, Long>): List<Line> =
        items.filter { inRange(it.purchasedAt, range) }
            .groupBy { key(it.name) }
            .map { (key, list) ->
                val first = list.first()
                Line(key, first.name, list.sumOf { it.amount }, list.size, first.categoryId, first.mainCategoryName, first.categoryColorHex)
            }
            .sortedByDescending { it.total }

    /** Things bought in [range] that cost noticeably more a unit than the time before. */
    fun priceRises(items: List<ReceiptItemDetails>, range: Pair<Long, Long>): List<PriceChange> {
        val history = items.filter { it.quantity > 0 && it.amount > 0 }.groupBy { key(it.name) }
        return history.values.mapNotNull { purchases ->
            val sorted = purchases.sortedBy { it.purchasedAt }
            val latest = sorted.lastOrNull { inRange(it.purchasedAt, range) } ?: return@mapNotNull null
            val previous = sorted.lastOrNull { it.purchasedAt < latest.purchasedAt } ?: return@mapNotNull null
            // The shelf price (before the line's own discount), so an offer ending isn't a "rise".
            val now = (latest.amount + latest.discount) / latest.quantity
            val before = (previous.amount + previous.discount) / previous.quantity
            if (now - before >= MIN_RISE && (now - before) / before * 100 >= MIN_RISE_PCT) {
                PriceChange(latest.name, latest.storeName, now, before, previous.purchasedAt)
            } else {
                null
            }
        }.sortedByDescending { it.percent }
    }

    /** The same product written slightly differently ("ARLA LETMÆLK 1L", "Arla letmælk 1 l"). */
    internal fun key(name: String): String = name.lowercase().replace(Regex("[^\\p{L}0-9]"), "")
}
