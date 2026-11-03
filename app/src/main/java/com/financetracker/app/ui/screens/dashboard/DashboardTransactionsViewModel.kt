package com.financetracker.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.DismissedRecurringExpenses
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.ScheduledPayment
import com.financetracker.app.data.prefs.toTransactionDetails
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.AnticipatedExpense
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.SimilarTransactionsPrompt
import com.financetracker.app.util.anticipatedRecurringExpenses
import com.financetracker.app.util.countsTowardTotals
import com.financetracker.app.util.transfersInCountAsIncome
import com.financetracker.app.util.effectiveReportingDate
import com.financetracker.app.util.findSimilarTransactions
import com.financetracker.app.util.periodRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardTransactionsUiState(
    val label: String = "",
    val transactions: List<TransactionWithDetails> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val showAnticipatedSections: Boolean = false,
    val anticipatedExpenses: List<AnticipatedExpense> = emptyList()
)

private data class DashboardTransactionsExtras(
    val accounts: List<Account>,
    val categories: List<Category>,
    val dismissedRecurring: Set<String>,
    val fixedCategoryIds: Set<Long>,
    val scheduledPayments: List<ScheduledPayment>
)

/**
 * Shows every transaction in the dashboard's current period matching [type] and/or
 * [categoryId] (either or both null means "no filter on that field"), further narrowed to
 * [accountId] when the Dashboard's own account filter has one selected (null means every
 * account) — the same scoping the Dashboard's own tiles/budget rows/category breakdown already
 * apply to the totals this popup is drilling into, so it never shows a different account's
 * transactions than what those numbers were actually computed from. [includeAnticipated] is
 * true only for the Dashboard's own Expenses tile — the one drill-down whose total the
 * "Anticipate recurring bills" setting actually changes, for This month or Next month alike;
 * Budget rows and the category breakdown also open this same screen for EXPENSE/This-Month, but
 * their own totals never include anticipated amounts, so showing the upcoming/posted split there
 * would be misleading.
 *
 * [uncategorizedOnly] is true only for the Dashboard's Uncategorized tile: it overrides both
 * [categoryId] (filters to literally uncategorized — `categoryId == null` — instead of "no
 * filter on category") and the period, showing the full all-time backlog regardless of
 * [periodOption]/[customRange], since an old uncategorized transaction is just as much a cleanup
 * item as a new one.
 */
class DashboardTransactionsViewModel(
    private val repository: FinanceRepository,
    type: TransactionType?,
    categoryId: Long?,
    label: String,
    periodOption: PeriodOption,
    customRange: Pair<Long, Long>?,
    includeAnticipated: Boolean,
    accountId: Long?,
    uncategorizedOnly: Boolean = false
) : ViewModel() {

    val uiState: StateFlow<DashboardTransactionsUiState> = combine(
        repository.observeTransactions(),
        combine(
            BudgetSettings.shiftSalaryToNextMonth,
            BudgetSettings.excludeTransfersFromSpending,
            BudgetSettings.anticipateRecurringBills
        ) { shiftSalary, excludeTransfers, anticipateRecurring ->
            Triple(shiftSalary, excludeTransfers, anticipateRecurring)
        },
        combine(
            repository.observeAccounts(),
            repository.observeCategories(),
            DismissedRecurringExpenses.dismissed,
            FixedExpenseCategories.fixedCategoryIds.combine(FixedMerchants.merchants) { ids, _ -> ids },
            BankScheduledPayments.payments
        ) { accounts, categories, dismissedRecurring, fixedCategoryIds, scheduledPayments ->
            DashboardTransactionsExtras(accounts, categories, dismissedRecurring, fixedCategoryIds, scheduledPayments)
        }
    ) { transactions, settings, extras ->
        val (shiftSalary, excludeTransfers, anticipateRecurring) = settings
        val (accounts, categories, dismissedRecurring, fixedCategoryIds, scheduledPayments) = extras
        val accountScoped = if (accountId != null) transactions.filter { it.accountId == accountId } else transactions
        val (from, to) = if (uncategorizedOnly) periodRange(PeriodOption.ALL_TIME, null) else periodRange(periodOption, customRange)
        val filtered = accountScoped.filter { tx ->
            val effectiveDate =
                effectiveReportingDate(tx.date, tx.type, tx.mainCategoryName, tx.categoryName, shiftSalary)
            val inPeriod = effectiveDate >= from && effectiveDate < to
            val matchesType = type == null || tx.type == type
            val matchesCategory = if (uncategorizedOnly) tx.categoryId == null else (categoryId == null || tx.categoryId == categoryId)
            // Applied to the Income and Expenses drill-downs so they match their tiles; "All
            // Transactions" (type == null) stays unfiltered to match the net balance.
            val countsIfRelevant = type == null ||
                countsTowardTotals(tx.type, tx.mainCategoryName, tx.categoryName, excludeTransfers, transfersInAreIncome = transfersInCountAsIncome(accountId, MainAccountSettings.mainAccountId.value))
            inPeriod && matchesType && matchesCategory && countsIfRelevant
        }.sortedByDescending { it.date }

        val monthsAhead = when (periodOption) {
            PeriodOption.THIS_MONTH -> 0
            PeriodOption.NEXT_MONTH -> 1
            else -> null
        }
        val showAnticipated = includeAnticipated && anticipateRecurring && monthsAhead != null
        val anticipated = if (showAnticipated) {
            val categoriesById = categories.associateBy { it.id }
            anticipatedRecurringExpenses(
                accountScoped,
                monthsAhead = monthsAhead!!,
                dismissedKeys = dismissedRecurring,
                fixedCategoryIds = fixedCategoryIds,
                scheduled = scheduledPayments
                    .filter { accountId == null || it.accountId == accountId }
                    .map { it.toTransactionDetails(categoriesById) },
                excludeTransfers = excludeTransfers,
                fixedMerchants = FixedMerchants.merchants.value
            )
        } else {
            emptyList()
        }

        DashboardTransactionsUiState(
            label = label,
            transactions = filtered,
            accounts = accounts,
            categories = categories,
            showAnticipatedSections = showAnticipated,
            anticipatedExpenses = anticipated
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardTransactionsUiState(label = label))

    private val _similarPrompt = MutableStateFlow<SimilarTransactionsPrompt?>(null)
    val similarPrompt: StateFlow<SimilarTransactionsPrompt?> = _similarPrompt.asStateFlow()

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
                Transaction(amount = amount, type = type, accountId = accountId, categoryId = categoryId, date = date, note = note)
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
                Transaction(id = id, amount = amount, type = type, accountId = accountId, categoryId = categoryId, date = date, note = note)
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

    /** Removes an anticipated (not-yet-posted) expense from "Upcoming expenses" — there's no
     * real transaction to delete, so this just remembers not to anticipate that specific bill
     * again until it actually posts or the month rolls over. See [DismissedRecurringExpenses]. */
    fun dismissAnticipated(expense: AnticipatedExpense) {
        DismissedRecurringExpenses.dismiss(expense.dismissKey)
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
