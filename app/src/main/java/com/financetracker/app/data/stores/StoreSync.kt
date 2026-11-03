package com.financetracker.app.data.stores

import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BankReservations
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.ai.CategorySuggester
import com.financetracker.app.data.ai.ParsedReceipt
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.receipts.Receipts
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.ReceiptMatcher
import com.financetracker.app.util.ReceiptSplits
import com.financetracker.app.util.ShoppingStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fetches receipts from the connected store accounts: the last 12 months when an account is
 * connected, then what's new after each bank sync. */
object StoreSync {
    private const val DAY = 86_400_000L
    private const val FIRST_FETCH_DAYS = 365L
    /** Receipts can show up in a store account a little late, so each fetch looks back a bit. */
    private const val OVERLAP_DAYS = 14L

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Accounts being fetched right now. */
    val running: StateFlow<Set<String>> = _running

    /** One fetch at a time: two at once would both renew the same sign-in, and a store that allows
     * each renewal only once then rejects the second and signs the account out. */
    private val lock = Mutex()

    /** What a finished fetch did: how many receipts were new, or what went wrong. */
    /** [skipped]: receipts left out because no payment from the person's own accounts matches them
     * (e.g. someone else in the household paid with their card on the shared store account). */
    data class FetchResult(val accountId: String, val store: Store, val added: Int, val problem: String?, val skipped: Int = 0)

    private val _finished = MutableSharedFlow<FetchResult>(extraBufferCapacity = 16)
    /** Each fetch as it finishes, so the screen can say what happened. */
    val finished: SharedFlow<FetchResult> = _finished

    /** A short line saying what a fetch did, e.g. "No new receipts from Netto+". */
    fun resultText(result: FetchResult): String = when {
        result.problem != null -> "${result.store.label}: ${result.problem}"
        result.added == 0 -> "No new receipts from ${result.store.label}"
        result.added == 1 -> "1 new receipt from ${result.store.label}"
        else -> "${result.added} new receipts from ${result.store.label}"
    } + if (result.problem == null && result.skipped > 0) {
        " · ${result.skipped} not paid from your bank account, left out"
    } else {
        ""
    }

    /** Fetches every connected account (or just [accountId]); returns how many receipts were new. */
    suspend fun syncAll(context: Context, repository: FinanceRepository, accountId: String? = null): Int = lock.withLock {
        var added = 0
        StoreAccounts.load(context).filter { accountId == null || it.id == accountId }.forEach { account ->
            added += sync(context, repository, account)
        }
        added
    }

    private suspend fun sync(context: Context, repository: FinanceRepository, account: StoreAccount): Int {
        val connector = StoreConnector.forStore(account.store) ?: return 0
        if (account.secret.isEmpty()) return 0
        _running.value = _running.value + account.id
        try {
            val now = System.currentTimeMillis()
            val since = if (account.lastSyncAt == 0L) now - FIRST_FETCH_DAYS * DAY else maxOf(account.lastSyncAt - OVERLAP_DAYS * DAY, now - FIRST_FETCH_DAYS * DAY)
            val (fetched, secret) = connector.fetch(account.secret, since)
            val fresh = fetched.filter { it.receipt.total > 0 && repository.findReceipt(account.store.id, it.receipt.externalId) == null }
            val (saved, skipped) = save(repository, account.store, fresh)
            StoreAccounts.update(context, account.id) {
                it.copy(
                    secret = secret,
                    lastSyncAt = now,
                    receiptCount = it.receiptCount + saved,
                    lastFetchAdded = saved,
                    lastReceiptAt = maxOf(it.lastReceiptAt, fetched.maxOfOrNull { r -> r.receipt.purchasedAt } ?: 0L),
                    problem = null
                )
            }
            _finished.tryEmit(FetchResult(account.id, account.store, saved, null, skipped))
            return saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val renewed = (e as? StoreFetchFailed)?.secret
            val failure = (e as? StoreFetchFailed)?.cause ?: e
            val problem = if (failure is StoreLoginExpired) "Sign in again" else "Couldn't fetch receipts (${failure.message ?: "no connection"})"
            StoreAccounts.update(context, account.id) { it.copy(secret = renewed ?: it.secret, problem = problem) }
            _finished.tryEmit(FetchResult(account.id, account.store, 0, problem))
        } finally {
            _running.value = _running.value - account.id
        }
        return 0
    }

    /**
     * Keeps only the receipts paid from the person's own bank accounts (a booked or reserved payment
     * matches), sorts their items into categories, matches each to its payment and saves it, split.
     * The others aren't saved; a receipt whose payment hasn't reached the bank yet is simply checked
     * again on the next fetch (each fetch looks back two weeks). Returns (saved, left out).
     */
    private suspend fun save(repository: FinanceRepository, store: Store, receipts: List<StoreReceipt>): Pair<Int, Int> {
        if (receipts.isEmpty()) return 0 to 0
        val transactions = paymentsForMatching(repository)
        val notYetBooked = notYetBooked()
        val own = receipts.filter {
            ReceiptMatcher.paidFromOwnAccount(
                it.receipt.total, it.receipt.purchasedAt, it.receipt.storeName, store.bankWords, transactions, notYetBooked
            )
        }
        val skipped = receipts.size - own.size
        if (own.isEmpty()) return 0 to skipped
        val categorised = ItemCategories.assign(repository, own)
        val taken = repository.observeTransactionIdsWithReceipt().first().toMutableSet()
        var count = 0
        for (receipt in categorised.sortedBy { it.purchasedAt }) {
            val match = ReceiptMatcher.best(receipt.total, receipt.purchasedAt, receipt.storeName, transactions, taken)
            match?.let { taken += it.id }
            Receipts.save(repository, receipt, store.id, match?.id, split = true)
            count++
        }
        return count to skipped
    }

    /** The person's payments, with a split payment counted whole (it shows as its parts). */
    private suspend fun paymentsForMatching(repository: FinanceRepository): List<TransactionWithDetails> {
        val all = repository.observeTransactions().first()
        return all.filter { !ReceiptSplits.isPart(it.id) } +
            all.filter { ReceiptSplits.isPart(it.id) }.groupBy { ReceiptSplits.originalId(it.id) }
                .map { (id, parts) -> parts.first().copy(id = id, amount = parts.sumOf { it.amount }) }
    }

    /** Payments the bank has reserved or scheduled but not booked yet. */
    private fun notYetBooked(): List<Pair<Long, Pair<Double, String>>> =
        (BankReservations.reservations.value + BankScheduledPayments.payments.value).map { it.date to (it.amount to it.note) }

    /**
     * Receipts already saved from the store connections that weren't paid from the person's own
     * bank account (e.g. fetched before only own receipts were kept, from a store account shared
     * with the household). Only receipts not linked to a payment are considered, dated within the
     * bank history the app has (older ones can't be checked), and only once their payment would have
     * shown: the bank has synced at least an hour after the purchase and reports card reservations,
     * or [BOOKING_DAYS] have passed (by then a card payment is booked). For the person to confirm.
     */
    suspend fun receiptsNotPaidByYou(repository: FinanceRepository, now: Long = System.currentTimeMillis()): List<Receipt> {
        val transactions = paymentsForMatching(repository)
        val historyStart = transactions.filter { it.type == TransactionType.EXPENSE }.minOfOrNull { it.date } ?: return emptyList()
        val notYetBooked = notYetBooked()
        val stores = Store.entries.associateBy { it.id }
        return repository.observeReceipts().first().filter { receipt ->
            val store = stores[receipt.source] ?: return@filter false
            receipt.transactionId == null &&
                paymentWouldShow(receipt.purchasedAt, now) &&
                receipt.purchasedAt > historyStart + DAY &&
                !ReceiptMatcher.paidFromOwnAccount(
                    receipt.total, receipt.purchasedAt, receipt.storeName, store.bankWords, transactions, notYetBooked
                )
        }.sortedByDescending { it.purchasedAt }
    }

    private const val BOOKING_DAYS = 5L

    /** Whether a payment for a purchase at [purchasedAt] would be known to the app by now. */
    internal fun paymentWouldShow(
        purchasedAt: Long,
        now: Long,
        reservationsSeen: Boolean = BankReservations.seenAny,
        reservationsUpdatedAt: Long = BankReservations.updatedAt
    ): Boolean =
        purchasedAt < now - BOOKING_DAYS * DAY ||
            (reservationsSeen && purchasedAt < reservationsUpdatedAt - 60 * 60 * 1000L)

    /** Starts a fetch in the background (it keeps going if the screen is closed). */
    fun enqueue(context: Context, accountId: String? = null) {
        val request = OneTimeWorkRequestBuilder<StoreSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(StoreSyncWorker.ACCOUNT to accountId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "store_sync" + (accountId?.let { "_$it" } ?: ""),
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

class StoreSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as FinanceApp).repository
        StoreSync.syncAll(applicationContext, repository, inputData.getString(ACCOUNT))
        return Result.success()
    }

    companion object {
        const val ACCOUNT = "account"
    }
}

/** Puts each fetched item in one of the user's expense categories: the category the same product
 * had on an earlier receipt (so moving an item once teaches it), else the AI's suggestion, else
 * none (it then counts in the payment's own category). */
internal object ItemCategories {
    suspend fun assign(repository: FinanceRepository, receipts: List<StoreReceipt>): List<ParsedReceipt> {
        val categories = repository.observeCategories().first().filter { it.type == TransactionType.EXPENSE }
        val known = repository.getAllReceiptItems().filter { it.categoryId != null }
            .sortedBy { it.id }
            .associate { ShoppingStats.key(it.name) to it.categoryId!! }
            .toMutableMap()
        val unknown = linkedMapOf<String, String>()
        receipts.forEach { r ->
            r.receipt.items.forEachIndexed { i, item ->
                val key = ShoppingStats.key(item.name)
                if (key !in known && key !in unknown) {
                    val hint = r.hints.getOrNull(i)
                    unknown[key] = "Supermarket item: ${item.name}" + (hint?.let { " ($it)" } ?: "")
                }
            }
        }
        if (unknown.isNotEmpty() && categories.isNotEmpty()) {
            unknown.entries.chunked(CategorySuggester.BATCH_SIZE).forEach { chunk ->
                val result = runCatching { CategorySuggester.suggestBatch(chunk.map { it.value }, categories).getOrNull() }.getOrNull()
                    ?: return@forEach
                chunk.forEachIndexed { i, (key, _) -> result.getOrNull(i)?.let { known[key] = it.id } }
            }
        }
        return receipts.map { r ->
            r.receipt.copy(items = r.receipt.items.map { it.copy(categoryId = known[ShoppingStats.key(it.name)]) })
        }
    }
}
