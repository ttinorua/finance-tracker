package com.financetracker.app.ui.screens.settings

import com.financetracker.app.data.prefs.ViewFilter
import com.financetracker.app.util.CategoryNames
import com.financetracker.app.data.bank.CategoryMerge
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.prefs.CategoryIconChoices
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.financetracker.app.data.ai.AiCategorizationWorker
import com.financetracker.app.data.ai.CategorizationProgress
import com.financetracker.app.data.ai.AiService
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.importexport.DuplicateTransactionFilter
import com.financetracker.app.data.prefs.BankReservations
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.EnableBankingPrefs
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.repository.FinanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountUi(val account: Account, val balance: Double)

data class SettingsUiState(
    val accounts: List<AccountUi> = emptyList(),
    val categories: List<Category> = emptyList()
)

class SettingsViewModel(private val repository: FinanceRepository, private val appContext: Context) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.observeAccounts(),
        repository.observeTransactions(),
        repository.observeCategories()
    ) { accounts, transactions, categories ->
        val accountUis = accounts.map { acc ->
            val delta = transactions.filter { it.accountId == acc.id }
                .sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
            AccountUi(acc, acc.initialBalance + delta)
        }
        SettingsUiState(accountUis, categories)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // Runs the AI categorization backfill as WorkManager-managed work (see AiCategorizationWorker)
    // rather than a plain coroutine, so a run that can take a long time survives navigating away,
    // the screen turning off, or the app being backgrounded — its state is read back from
    // WorkManager itself, not held only in this ViewModel's own memory.
    private val workManager = WorkManager.getInstance(appContext)

    private val categorizationWorkInfo: StateFlow<WorkInfo?> = workManager
        .getWorkInfosForUniqueWorkFlow(AiCategorizationWorker.UNIQUE_WORK_NAME)
        // A build from before this fix could leave more than one WorkInfo entry behind under
        // this same unique name (it used to replace its own still-running unique work from
        // inside doWork(), a known way to leave WorkManager's bookkeeping inconsistent) — always
        // prefer whichever entry is actually still active over just taking the list's first
        // entry, so this never ends up watching a dead, orphaned one while real work (if any)
        // runs under another.
        .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isCategorizing: StateFlow<Boolean> = categorizationWorkInfo
        .map { it != null && !it.state.isFinished }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val categorizationProgress: StateFlow<CategorizationProgress?> = categorizationWorkInfo
        .map { info ->
            if (info == null || info.state.isFinished) return@map null
            val done = info.progress.getInt(AiCategorizationWorker.KEY_DONE, -1)
            val total = info.progress.getInt(AiCategorizationWorker.KEY_TOTAL, -1)
            if (done >= 0 && total > 0) CategorizationProgress(done, total) else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _categorizationMessage = MutableStateFlow<String?>(null)
    val categorizationMessage: StateFlow<String?> = _categorizationMessage.asStateFlow()

    init {
        viewModelScope.launch {
            categorizationWorkInfo.collect { info ->
                // A batch that still has work left returns Result.retry(), which WorkManager
                // reports as ENQUEUED again (never SUCCEEDED) until the chain's actual last
                // batch finishes — so any terminal state seen here really is the run's end.
                if (info == null || !info.state.isFinished) return@collect
                _categorizationMessage.value = when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val categorized = info.outputData.getInt(AiCategorizationWorker.KEY_CATEGORIZED, 0)
                        val total = info.outputData.getInt(AiCategorizationWorker.KEY_TOTAL_CONSIDERED, 0)
                        if (total == 0) {
                            "Nothing to categorize — every transaction already has a category."
                        } else {
                            "Categorized $categorized of $total transaction(s)."
                        }
                    }
                    WorkInfo.State.FAILED -> "Categorization failed. Try again."
                    else -> _categorizationMessage.value
                }
                // Otherwise a finished run would keep reappearing every time this ViewModel is
                // recreated (e.g. reopening Settings), since WorkManager keeps finished work
                // around until pruned.
                workManager.pruneWork()
            }
        }
    }

    /** One-time AI backfill for every transaction that has no real category (mainly Enable
     * Banking's history, since it sends no category data). Can take a while for a large
     * history — progress is reported as it goes, and the run keeps going even if this screen
     * is closed. */
    fun categorizeWithAi() {
        if (isCategorizing.value) return
        if (!AiService.isConfigured) {
            _categorizationMessage.value = "Set up an AI first in AI assistant above."
            return
        }
        _categorizationMessage.value = null
        AiCategorizationWorker.clearPersistedState(appContext)
        // REPLACE (not KEEP): this call only ever happens from here, guarded by the
        // isCategorizing check above, so it can't race with a currently-running worker — it's a
        // deliberate, explicit restart, always giving a clean single work item instead of risking
        // reuse of a leftover/stuck one from a previous run.
        workManager.enqueueUniqueWork(
            AiCategorizationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            AiCategorizationWorker.buildRequest()
        )
    }

    /** Escape hatch for a run that's stuck (e.g. leftover state from a previous app version) —
     * cancels whatever's registered under this unique work name and clears its persisted total,
     * so the next "Categorize with AI" tap is guaranteed a clean start. */
    fun cancelCategorization() {
        workManager.cancelUniqueWork(AiCategorizationWorker.UNIQUE_WORK_NAME)
        AiCategorizationWorker.clearPersistedState(appContext)
        _categorizationMessage.value = null
    }

    fun dismissCategorizationMessage() {
        _categorizationMessage.value = null
    }

    fun addAccount(name: String, initialBalance: Double) {
        viewModelScope.launch { repository.upsertAccount(Account(name = name, initialBalance = initialBalance)) }
    }

    fun updateAccount(account: Account) {
        viewModelScope.launch { repository.updateAccount(account) }
    }

    fun deleteAccount(account: Account) {
        viewModelScope.launch {
            repository.deleteAccount(account)
            MainAccountSettings.onAccountRemoved(account.id)
            ViewFilter.onAccountRemoved(account.id)
            BankScheduledPayments.onAccountRemoved(account.id)
            BankReservations.onAccountRemoved(account.id)
        }
    }

    /** There's always a main account, so this only ever moves it; un-ticking the main one does nothing. */
    fun setMainAccount(account: Account, isMain: Boolean) {
        if (isMain) MainAccountSettings.setMainAccount(account.id)
    }

    /** Moves every transaction on [source] onto [target] — except ones [target] already has an
     * identical copy of (same date/amount/type/note, per [DuplicateTransactionFilter.keyOf]:
     * the exact scenario a full Enable Banking re-sync into a wrongly-created duplicate account
     * produces), which are dropped instead of moved, so merging two accounts that both hold the
     * same real history doesn't leave [target] with everything doubled. Also folds [source]'s
     * opening balance into [target]'s so the combined balance is unchanged, remaps any Enable
     * Banking account link pointing at [source] onto [target] (so a later sync recognizes that
     * account instead of recreating [source]), then deletes [source] — by then empty, so
     * [deleteAccount]'s transaction-cascading delete has nothing left to remove. Use this instead
     * of [deleteAccount] whenever two accounts turn out to be the same real-world account —
     * deleting [source] directly would cascade-delete every one of its transactions. */
    fun mergeAccounts(source: Account, target: Account) {
        viewModelScope.launch {
            val targetKeyCounts = repository.getTransactionsForAccount(target.id)
                .groupingBy { DuplicateTransactionFilter.keyOf(it) }
                .eachCount()
                .toMutableMap()

            repository.getTransactionsForAccount(source.id).forEach { tx ->
                val key = DuplicateTransactionFilter.keyOf(tx)
                val remaining = targetKeyCounts[key] ?: 0
                if (remaining > 0) {
                    // target already has an equivalent transaction — consume one of its matching
                    // count (so a genuine same-day/same-amount repeat present on both sides still
                    // merges 1:1 instead of being collapsed) and drop this one rather than move it.
                    targetKeyCounts[key] = remaining - 1
                    repository.deleteTransaction(tx)
                } else {
                    repository.updateTransaction(tx.copy(accountId = target.id))
                }
            }
            if (source.initialBalance != 0.0) {
                repository.updateAccount(target.copy(initialBalance = target.initialBalance + source.initialBalance))
            }
            EnableBankingPrefs.remapAccountLink(source.id, target.id)
            repository.deleteAccount(source)
            MainAccountSettings.onAccountRemoved(source.id, replacementId = target.id)
            ViewFilter.onAccountRemoved(source.id, replacementId = target.id)
            BankScheduledPayments.onAccountRemoved(source.id)
            BankReservations.onAccountRemoved(source.id)
        }
    }

    /** Recovery tool for an account left with doubled transactions — most commonly by
     * [mergeAccounts] before it became duplicate-aware, or any other cause that ends up importing
     * the same real history into the same account twice. Collapses every group of transactions
     * that share the same date/amount/type/note (per [DuplicateTransactionFilter.keyOf]) down to
     * one, keeping the single oldest-inserted copy of each and deleting the rest. Never touches
     * genuinely distinct transactions, only exact repeats within [account] itself. */
    fun deduplicateTransactions(account: Account) {
        viewModelScope.launch {
            val transactions = repository.getTransactionsForAccount(account.id)
            val duplicates = transactions
                .groupBy { DuplicateTransactionFilter.keyOf(it) }
                .values
                .flatMap { group -> if (group.size <= 1) emptyList() else group.sortedBy { it.createdAt }.drop(1) }
            duplicates.forEach { repository.deleteTransaction(it) }
        }
    }

    fun addCategory(mainCategory: String, name: String, type: TransactionType, icon: String? = null) {
        viewModelScope.launch {
            val categories = repository.getCategories()
            // Never a second copy of a category that's already there, under any main category.
            CategoryNames.existing(categories, name, type)?.let {
                _categoryMessage.value = "${it.mainCategory} › ${it.name} already exists, so no new category was added."
                return@launch
            }
            val main = CategoryNames.canonicalMain(categories, mainCategory)
            val id = repository.upsertCategory(Category(name = name.trim(), mainCategory = main, type = type))
            if (icon != null && id > 0) CategoryIconChoices.set(id, icon)
        }
    }

    private val _categoryMessage = MutableStateFlow<String?>(null)
    /** A one-off note for the Categories page (a refused duplicate, or what a merge did). */
    val categoryMessage: StateFlow<String?> = _categoryMessage.asStateFlow()
    fun clearCategoryMessage() { _categoryMessage.value = null }

    /** Duplicate categories right now, refreshed whenever the categories or transactions change. */
    val duplicateCategories: StateFlow<List<CategoryNames.Duplicate>> =
        combine(repository.observeCategories(), repository.observeTransactions()) { _, _ -> CategoryMerge.findDuplicates(repository) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun mergeDuplicateCategories() {
        viewModelScope.launch {
            val removed = CategoryMerge.mergeAll(repository)
            _categoryMessage.value = if (removed == 0) "No duplicates left to merge." else
                "Merged $removed duplicate ${if (removed == 1) "category" else "categories"}. Their transactions and budgets moved over."
        }
    }

    fun updateCategory(category: Category) {
        viewModelScope.launch { repository.updateCategory(category) }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch { repository.deleteCategory(category) }
    }
}
