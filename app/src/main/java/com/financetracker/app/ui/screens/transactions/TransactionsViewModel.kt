package com.financetracker.app.ui.screens.transactions

import com.financetracker.app.data.prefs.ViewFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.SimilarTransactionsPrompt
import com.financetracker.app.util.findSimilarTransactions
import com.financetracker.app.util.periodRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

sealed interface TransactionListItem {
    data class MonthHeader(val label: String) : TransactionListItem
    data class DateHeader(val label: String, val balanceLabel: String?) : TransactionListItem
    data class Row(val tx: TransactionWithDetails, val runningBalance: Double?) : TransactionListItem
}

/** Local, user-driven filter state; kept separate from the DB-derived [TransactionsUiState]. */
data class TransactionFilterState(
    val selectedAccountId: Long? = null, // null = All accounts
    val periodOption: PeriodOption = PeriodOption.ALL_TIME,
    val customRange: Pair<Long, Long>? = null,
    val searchQuery: String = ""
)

data class TransactionsUiState(
    val allAccounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val filter: TransactionFilterState = TransactionFilterState(),
    val displayItems: List<TransactionListItem> = emptyList()
)

class TransactionsViewModel(private val repository: FinanceRepository) : ViewModel() {

    private val _search = MutableStateFlow("")

    /** Account and period are shared with the other tabs and remembered (see ViewFilter); the
     * search text belongs to this screen only. */
    private val _filter: StateFlow<TransactionFilterState> =
        combine(ViewFilter.accountId, ViewFilter.period, ViewFilter.customRange, _search) { accountId, period, range, query ->
            TransactionFilterState(accountId, period, range, query)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            TransactionFilterState(ViewFilter.accountId.value, ViewFilter.period.value, ViewFilter.customRange.value, "")
        )

    val filter: StateFlow<TransactionFilterState> = _filter

    private val _similarPrompt = MutableStateFlow<SimilarTransactionsPrompt?>(null)
    val similarPrompt: StateFlow<SimilarTransactionsPrompt?> = _similarPrompt.asStateFlow()

    // Built in the background, and only for the latest input: with thousands of transactions,
    // building the list on the main thread on every typed letter froze the app until Android
    // closed it. The search box reads [filter] directly, so typing stays instant.
    val uiState: StateFlow<TransactionsUiState> = combine(
        repository.observeTransactions(),
        repository.observeAccounts(),
        repository.observeCategories(),
        _filter
    ) { transactions, accounts, categories, filter ->
        Inputs(transactions, accounts, categories, filter)
    }.conflate().map { (transactions, accounts, categories, filter) ->
        TransactionsUiState(
            allAccounts = accounts,
            categories = categories,
            filter = filter,
            displayItems = buildDisplayItems(transactions, accounts, filter)
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /** Short name per bank text, and the name data it was worked out from. Only touched while
     * building the list, which runs one build at a time. */
    private val shortNames = HashMap<String, String?>()
    private var shortNamesFrom: Pair<Map<String, String>?, List<*>?> = null to null

    private data class Inputs(
        val transactions: List<TransactionWithDetails>,
        val accounts: List<Account>,
        val categories: List<Category>,
        val filter: TransactionFilterState
    )

    fun selectAccount(accountId: Long?) {
        ViewFilter.accountId.value = accountId
    }

    fun selectPeriod(option: PeriodOption) {
        ViewFilter.period.value = option
    }

    fun selectCustomRange(start: Long, endExclusive: Long) = ViewFilter.selectCustomRange(start, endExclusive)

    fun setSearchQuery(query: String) {
        _search.value = query
    }

    private fun buildDisplayItems(
        transactions: List<TransactionWithDetails>,
        accounts: List<Account>,
        filter: TransactionFilterState
    ): List<TransactionListItem> {
        val (from, to) = periodRange(filter.periodOption, filter.customRange)
        val query = filter.searchQuery.trim()
        // Short names ("McDonald's") worked out once per distinct bank text, not once per
        // transaction: most transactions share their text with others.
        // Kept between typed letters too, until a name changes (AI answer or the person's choice).
        val cachedNames = MerchantLogoLookup.names.value
        val overrides = MerchantOverrides.rules.value
        if (cachedNames !== shortNamesFrom.first || overrides !== shortNamesFrom.second) {
            shortNames.clear()
            shortNamesFrom = cachedNames to overrides
        }
        fun shortName(note: String): String? =
            if (shortNames.containsKey(note)) shortNames[note]
            else MerchantLogoLookup.nameFor(note, cachedNames, overrides).also { shortNames[note] = it }

        fun matchesSearch(tx: TransactionWithDetails): Boolean {
            if (query.isBlank()) return true
            if (tx.note.contains(query, ignoreCase = true)) return true
            // The short name shown in the list ("McDonald's") finds it too.
            if (shortName(tx.note)?.contains(query, ignoreCase = true) == true) return true
            if (tx.categoryName?.contains(query, ignoreCase = true) == true) return true
            if (tx.mainCategoryName?.contains(query, ignoreCase = true) == true) return true
            val signedAmount = (if (tx.type == TransactionType.EXPENSE) "-" else "") + Formatters.amount(tx.amount)
            if (signedAmount.contains(query, ignoreCase = true)) return true
            return Formatters.amount(tx.amount).contains(query, ignoreCase = true)
        }

        val runningBalanceByTxId: Map<Long, Double> = if (filter.selectedAccountId != null) {
            val account = accounts.firstOrNull { it.id == filter.selectedAccountId }
            val accountTx = transactions
                .filter { it.accountId == filter.selectedAccountId }
                .sortedWith(compareBy({ it.date }, { it.id }))
            var running = account?.initialBalance ?: 0.0
            val map = mutableMapOf<Long, Double>()
            accountTx.forEach { tx ->
                running += if (tx.type == TransactionType.INCOME) tx.amount else -tx.amount
                map[tx.id] = running
            }
            map
        } else {
            emptyMap()
        }

        val filtered = transactions
            .filter { filter.selectedAccountId == null || it.accountId == filter.selectedAccountId }
            .filter { it.date >= from && it.date < to }
            .filter(::matchesSearch)
            .sortedWith(compareByDescending<TransactionWithDetails> { it.date }.thenByDescending { it.id })

        val items = mutableListOf<TransactionListItem>()
        var lastMonthKey: Pair<Int, Int>? = null
        var lastDateKey: Long? = null

        filtered.forEach { tx ->
            val monthKey = yearMonthOf(tx.date)
            if (monthKey != lastMonthKey) {
                items += TransactionListItem.MonthHeader(Formatters.month(tx.date))
                lastMonthKey = monthKey
                lastDateKey = null
            }
            if (tx.date != lastDateKey) {
                val balance = runningBalanceByTxId[tx.id]
                items += TransactionListItem.DateHeader(
                    label = Formatters.fullDate(tx.date),
                    balanceLabel = balance?.let { Formatters.amount(it) }
                )
                lastDateKey = tx.date
            }
            items += TransactionListItem.Row(tx, runningBalanceByTxId[tx.id])
        }

        return items
    }

    private fun yearMonthOf(epochMillis: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
    }

    fun addTransaction(
        amount: Double,
        type: TransactionType,
        accountId: Long,
        categoryId: Long?,
        date: Long,
        note: String
    ) {
        viewModelScope.launch {
            repository.addTransaction(
                Transaction(
                    amount = amount,
                    type = type,
                    accountId = accountId,
                    categoryId = categoryId,
                    date = date,
                    note = note
                )
            )
            if (categoryId != null) LearnedCategoryRules.learn(note, categoryId)
        }
    }

    fun updateTransaction(
        id: Long,
        amount: Double,
        type: TransactionType,
        accountId: Long,
        categoryId: Long?,
        date: Long,
        note: String
    ) {
        viewModelScope.launch {
            val allBefore = repository.observeTransactions().first()
            val original = allBefore.firstOrNull { it.id == id }
            repository.updateTransaction(
                Transaction(
                    id = id,
                    amount = amount,
                    type = type,
                    accountId = accountId,
                    categoryId = categoryId,
                    date = date,
                    note = note
                )
            )
            if (categoryId != null) LearnedCategoryRules.learn(note, categoryId)
            if (original != null && categoryId != original.categoryId) {
                val similar = findSimilarTransactions(allBefore, original, categoryId)
                if (similar.isNotEmpty()) {
                    val categoryName = repository.observeCategories().first()
                        .firstOrNull { it.id == categoryId }?.name ?: "Uncategorized"
                    _similarPrompt.value = SimilarTransactionsPrompt(categoryId, categoryName, similar)
                }
            }
        }
    }

    /** Applies the pending [similarPrompt]'s new category to every transaction it listed. */
    fun applySimilarCategoryUpdate() {
        val prompt = _similarPrompt.value ?: return
        viewModelScope.launch {
            prompt.similar.forEach { tx ->
                repository.updateTransaction(
                    Transaction(
                        id = tx.id,
                        amount = tx.amount,
                        type = tx.type,
                        accountId = tx.accountId,
                        categoryId = prompt.newCategoryId,
                        date = tx.date,
                        note = tx.note
                    )
                )
            }
            _similarPrompt.value = null
        }
    }

    fun dismissSimilarPrompt() {
        _similarPrompt.value = null
    }

    fun deleteTransaction(transaction: TransactionWithDetails) {
        viewModelScope.launch {
            repository.deleteTransaction(
                Transaction(
                    id = transaction.id,
                    amount = transaction.amount,
                    type = transaction.type,
                    accountId = transaction.accountId,
                    categoryId = transaction.categoryId,
                    date = transaction.date,
                    note = transaction.note
                )
            )
        }
    }
}
