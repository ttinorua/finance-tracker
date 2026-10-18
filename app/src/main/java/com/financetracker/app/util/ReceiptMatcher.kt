package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlin.math.abs

/**
 * Finds the bank transaction a receipt belongs to: an expense of the same amount (within 50 øre)
 * booked from a day before to three days after the purchase — banks often book card payments a
 * day or two later. Closest date first; a transaction whose text names the store wins a tie.
 */
object ReceiptMatcher {
    private const val DAY = 86_400_000L
    private const val AMOUNT_TOLERANCE = 0.5

    fun candidates(
        total: Double,
        purchasedAt: Long,
        storeName: String,
        transactions: List<TransactionWithDetails>,
        alreadyMatched: Set<Long>
    ): List<TransactionWithDetails> {
        val store = storeName.lowercase().split(Regex("[^\\p{L}0-9]+")).firstOrNull { it.length >= 3 }
        return transactions
            .filter { tx ->
                tx.type == TransactionType.EXPENSE && !ReceiptSplits.isPart(tx.id) && tx.id !in alreadyMatched &&
                    abs(tx.amount - total) <= AMOUNT_TOLERANCE &&
                    tx.date >= startOfDay(purchasedAt) - DAY && tx.date < startOfDay(purchasedAt) + 4 * DAY
            }
            .sortedWith(
                compareBy<TransactionWithDetails> { abs(startOfDay(it.date) - startOfDay(purchasedAt)) / DAY }
                    .thenBy { if (store != null && it.note.lowercase().contains(store)) 0 else 1 }
                    .thenBy { abs(it.amount - total) }
            )
    }

    /** The match to make without asking: the best candidate, unless another one is just as good. */
    fun best(
        total: Double,
        purchasedAt: Long,
        storeName: String,
        transactions: List<TransactionWithDetails>,
        alreadyMatched: Set<Long>
    ): TransactionWithDetails? {
        val list = candidates(total, purchasedAt, storeName, transactions, alreadyMatched)
        val first = list.firstOrNull() ?: return null
        val second = list.getOrNull(1) ?: return first
        val sameDay = startOfDay(first.date) == startOfDay(second.date)
        val sameAmount = abs(first.amount - second.amount) < 0.01
        return if (sameDay && sameAmount && first.note == second.note) null else first
    }

    /**
     * Whether a receipt from a store account was paid from the person's own bank account: one of
     * their expenses (booked, or still only reserved/pending at the bank) has about the same amount,
     * a date from the day before to four days after, and the store's name in its text ([storeWords],
     * e.g. "netto"). A store account shared with the household also holds receipts someone else
     * paid with their own card; those have no such payment and are left out.
     */
    fun paidFromOwnAccount(
        total: Double,
        purchasedAt: Long,
        storeName: String,
        storeWords: List<String>,
        transactions: List<TransactionWithDetails>,
        notYetBooked: List<Pair<Long, Pair<Double, String>>>
    ): Boolean {
        val words = (storeWords + listOfNotNull(storeName.lowercase().split(Regex("[^\\p{L}0-9]+")).firstOrNull { it.length >= 3 }))
            .map { it.lowercase() }.distinct()
        fun mentionsStore(note: String) = note.lowercase().let { text -> words.any { it in text } }
        fun inWindow(date: Long) = date >= startOfDay(purchasedAt) - DAY && date < startOfDay(purchasedAt) + 4 * DAY
        val booked = transactions.any { tx ->
            tx.type == TransactionType.EXPENSE && abs(tx.amount - total) <= AMOUNT_TOLERANCE &&
                inWindow(tx.date) && mentionsStore(tx.note)
        }
        return booked || notYetBooked.any { (date, payment) ->
            abs(payment.first - total) <= AMOUNT_TOLERANCE && inWindow(date) && mentionsStore(payment.second)
        }
    }

    private fun startOfDay(t: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = t
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
