package com.financetracker.app.ui.screens.dashboard

import com.financetracker.app.data.prefs.ViewFilter
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.util.advisor.AttentionItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.financetracker.app.data.ai.AiService
import com.financetracker.app.data.ai.InsightCard
import com.financetracker.app.data.ai.InsightTone
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.CategorySpend
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.enablebanking.EnableBankingSyncWorker
import com.financetracker.app.data.prefs.AiInsightsCache
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.DismissedRecurringExpenses
import com.financetracker.app.data.prefs.EnableBankingPrefs
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.ScheduledPayment
import com.financetracker.app.data.prefs.toTransactionDetails
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.anticipatedRecurringExpenses
import com.financetracker.app.util.countsTowardTotals
import com.financetracker.app.util.dailyBalances
import com.financetracker.app.util.percentChange
import com.financetracker.app.util.todayUtcMidnight
import com.financetracker.app.util.transfersInCountAsIncome
import com.financetracker.app.util.effectiveReportingDate
import com.financetracker.app.util.periodRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoryBudgetStatus(
    val categoryId: Long,
    val categoryName: String,
    val mainCategory: String,
    val colorHex: String,
    val budget: Double,
    val spent: Double
)

data class BudgetStatus(
    val overallBudget: Double? = null,
    val overallSpent: Double = 0.0,
    val categoryStatuses: List<CategoryBudgetStatus> = emptyList()
)

data class DashboardUiState(
    val netBalance: Double = 0.0,
    val periodIncome: Double = 0.0,
    val periodExpense: Double = 0.0,
    val anticipateRecurringBillsEnabled: Boolean = false,
    val categoryBreakdown: List<CategorySpend> = emptyList(),
    val periodOption: PeriodOption = PeriodOption.THIS_MONTH,
    val customRange: Pair<Long, Long>? = null,
    val budgetStatus: BudgetStatus = BudgetStatus(),
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    /** All-time (not scoped to [periodOption]) count of transactions with no category at all,
     * respecting [selectedAccountId] — a cleanup backlog, not a spending metric, so an old
     * uncategorized transaction is just as worth surfacing as a new one. */
    val uncategorizedCount: Int = 0,
    /** The balance at the end of each of the last 30 days, ending with [netBalance]. */
    val balanceTrend: List<Double> = emptyList(),
    /** How much [netBalance] changed over [balanceTrend], in percent; null when it can't be said. */
    val balanceChange: Double? = null
)

private data class DashboardFilters(
    val periodOption: PeriodOption,
    val customRange: Pair<Long, Long>?,
    val selectedAccountId: Long?
)

private data class DashboardSettingsAndCategories(
    val shiftSalary: Boolean,
    val excludeTransfers: Boolean,
    val anticipateRecurring: Boolean,
    val categories: List<Category>,
    val overallBudgets: Map<Long?, Double>
)

private data class DashboardExtras(
    val shiftSalary: Boolean,
    val excludeTransfers: Boolean,
    val anticipateRecurring: Boolean,
    val categories: List<Category>,
    val overallBudgets: Map<Long?, Double>,
    val categoryBudgets: Map<Pair<Long, Long?>, Double>,
    val dismissedRecurring: Set<String>,
    val fixedCategoryIds: Set<Long>,
    val scheduledPayments: List<ScheduledPayment>
)

class DashboardViewModel(private val repository: FinanceRepository, appContext: Context) : ViewModel() {

    // Shared with Activity, Spending and Trends, and remembered (see ViewFilter).
    private val _periodOption = ViewFilter.period
    private val _customRange = ViewFilter.customRange
    private val _selectedAccountId = ViewFilter.accountId

    init {
        // Keeps "Needs your attention" current as transactions, budgets and goals change.
        viewModelScope.launch {
            combine(
                repository.observeTransactions(),
                BudgetLimits.overallBudgets,
                BudgetLimits.categoryBudgets,
                LoansAndGoals.goals,
                LoansAndGoals.loans
            ) { _, _, _, _, _ -> Unit }.collectLatest {
                delay(400)
                runCatching { AttentionMonitor.refresh(repository, seen = true) }
            }
        }
    }

    val attentionItems: StateFlow<List<AttentionItem>> = AttentionMonitor.items

    fun dismissAttention(key: String) = AttentionMonitor.dismiss(key)

    /** Whether a bank sync ([EnableBankingSyncWorker]) is currently running — read straight from
     * WorkManager (not tied to any particular screen's lifecycle) so the header's sync spinner
     * reflects the same run whether it was started by the automatic sync-on-app-open or a manual
     * "Sync now" in Settings. Mirrors [com.financetracker.app.ui.screens.settings.EnableBankingViewModel]'s
     * own isSyncing derivation so both surfaces never disagree about whether a sync is in flight. */
    private val syncWorkInfo: StateFlow<WorkInfo?> = WorkManager.getInstance(appContext)
        .getWorkInfosForUniqueWorkFlow(EnableBankingSyncWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isSyncing: StateFlow<Boolean> = syncWorkInfo
        .map { it != null && !it.state.isFinished }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** When the last bank sync completed — shown under the dashboard's title until the next one
     * finishes. Transactions/accounts/categories themselves refresh on their own the moment a
     * sync writes to the database, since [uiState] is built from Room [kotlinx.coroutines.flow.Flow]s
     * that emit on every change — no extra wiring needed for that part. */
    val lastSyncedAt: StateFlow<Long?> = EnableBankingPrefs.lastSyncedAt

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeTransactions(),
        repository.observeAccounts(),
        combine(_periodOption, _customRange, _selectedAccountId) { periodOption, customRange, selectedAccountId ->
            DashboardFilters(periodOption, customRange, selectedAccountId)
        },
        combine(
            combine(
                BudgetSettings.shiftSalaryToNextMonth,
                BudgetSettings.excludeTransfersFromSpending,
                BudgetSettings.anticipateRecurringBills,
                repository.observeCategories(),
                BudgetLimits.overallBudgets
            ) { shiftSalary, excludeTransfers, anticipateRecurring, categories, overallBudgets ->
                DashboardSettingsAndCategories(shiftSalary, excludeTransfers, anticipateRecurring, categories, overallBudgets)
            },
            BudgetLimits.categoryBudgets,
            DismissedRecurringExpenses.dismissed,
            FixedExpenseCategories.fixedCategoryIds,
            BankScheduledPayments.payments
        ) { partial, categoryBudgets, dismissedRecurring, fixedCategoryIds, scheduledPayments ->
            DashboardExtras(
                partial.shiftSalary,
                partial.excludeTransfers,
                partial.anticipateRecurring,
                partial.categories,
                partial.overallBudgets,
                categoryBudgets,
                dismissedRecurring,
                fixedCategoryIds,
                scheduledPayments
            )
        }
    ) { allTransactions, accounts, filters, extras ->
        val (periodOption, customRange, selectedAccountId) = filters
        val (
            shiftSalary, excludeTransfers, anticipateRecurring, categories,
            overallBudgets, allCategoryBudgets, dismissedRecurring, fixedCategoryIds, scheduledPayments
        ) = extras
        val overallBudget = resolveOverallBudget(overallBudgets, selectedAccountId)
        val categoryBudgets = resolveCategoryBudgets(allCategoryBudgets, selectedAccountId)
        val transactions = if (selectedAccountId != null) {
            allTransactions.filter { it.accountId == selectedAccountId }
        } else {
            allTransactions
        }
        val uncategorizedCount = transactions.count { it.categoryId == null }
        val netBalance = if (selectedAccountId != null) {
            val initial = accounts.firstOrNull { it.id == selectedAccountId }?.initialBalance ?: 0.0
            initial + transactions.sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
        } else {
            accounts.sumOf { it.initialBalance } +
                allTransactions.sumOf { if (it.type == TransactionType.INCOME) it.amount else -it.amount }
        }
        val balanceTrend = dailyBalances(
            netBalance,
            transactions.map { it.date to if (it.type == TransactionType.INCOME) it.amount else -it.amount },
            todayUtcMidnight()
        )
        val (from, to) = periodRange(periodOption, customRange)
        val inPeriod = transactions.filter {
            val effectiveDate =
                effectiveReportingDate(it.date, it.type, it.mainCategoryName, it.categoryName, shiftSalary)
            effectiveDate >= from && effectiveDate < to
        }

        val income = inPeriod.filter {
            it.type == TransactionType.INCOME &&
                countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome = transfersInCountAsIncome(selectedAccountId, MainAccountSettings.mainAccountId.value))
        }.sumOf { it.amount }
        val expenseTx = inPeriod.filter {
            it.type == TransactionType.EXPENSE &&
                countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome = transfersInCountAsIncome(selectedAccountId, MainAccountSettings.mainAccountId.value))
        }
        val expense = expenseTx.sumOf { it.amount }
        val monthsAhead = when (periodOption) {
            PeriodOption.THIS_MONTH -> 0
            PeriodOption.NEXT_MONTH -> 1
            else -> null
        }
        val anticipatedTotal = if (anticipateRecurring && monthsAhead != null) {
            val categoriesById = categories.associateBy { it.id }
            anticipatedRecurringExpenses(
                transactions,
                monthsAhead = monthsAhead,
                dismissedKeys = dismissedRecurring,
                fixedCategoryIds = fixedCategoryIds,
                scheduled = scheduledPayments
                    .filter { selectedAccountId == null || it.accountId == selectedAccountId }
                    .map { it.toTransactionDetails(categoriesById) },
                excludeTransfers = excludeTransfers
            ).sumOf { it.amount }
        } else {
            0.0
        }

        val breakdown = expenseTx
            .groupBy { it.categoryId }
            .map { (categoryId, txs) ->
                val sample = txs.first()
                CategorySpend(
                    mainCategory = sample.mainCategoryName ?: "Uncategorized",
                    categoryId = categoryId,
                    categoryName = sample.categoryName ?: "Uncategorized",
                    colorHex = sample.categoryColorHex ?: "#9E9E9E",
                    total = txs.sumOf { it.amount }
                )
            }
            .sortedByDescending { it.total }

        val (monthFrom, monthTo) = periodRange(PeriodOption.THIS_MONTH, null)
        val monthExpenses = transactions.filter {
            val effectiveDate =
                effectiveReportingDate(it.date, it.type, it.mainCategoryName, it.categoryName, shiftSalary)
            it.type == TransactionType.EXPENSE && effectiveDate >= monthFrom && effectiveDate < monthTo &&
                countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome = transfersInCountAsIncome(selectedAccountId, MainAccountSettings.mainAccountId.value))
        }
        val spentByCategory = monthExpenses
            .filter { it.categoryId != null }
            .groupBy { it.categoryId }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
        val categoryById = categories.associateBy { it.id }
        val categoryStatuses = categoryBudgets.mapNotNull { (categoryId, budget) ->
            val category = categoryById[categoryId] ?: return@mapNotNull null
            CategoryBudgetStatus(
                categoryId = categoryId,
                categoryName = category.name,
                mainCategory = category.mainCategory,
                colorHex = category.colorHex,
                budget = budget,
                spent = spentByCategory[categoryId] ?: 0.0
            )
        }.sortedByDescending { if (it.budget > 0) it.spent / it.budget else 0.0 }

        DashboardUiState(
            netBalance = netBalance,
            periodIncome = income,
            periodExpense = expense + anticipatedTotal,
            anticipateRecurringBillsEnabled = anticipateRecurring,
            categoryBreakdown = breakdown,
            periodOption = periodOption,
            customRange = customRange,
            budgetStatus = BudgetStatus(
                overallBudget = overallBudget,
                overallSpent = monthExpenses.sumOf { it.amount },
                categoryStatuses = categoryStatuses
            ),
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            uncategorizedCount = uncategorizedCount,
            balanceTrend = balanceTrend,
            balanceChange = percentChange(balanceTrend)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun selectPeriod(option: PeriodOption) {
        _periodOption.value = option
    }

    fun selectCustomRange(start: Long, endExclusive: Long) = ViewFilter.selectCustomRange(start, endExclusive)

    fun selectAccount(accountId: Long?) {
        _selectedAccountId.value = accountId
    }

    private val _isGeneratingInsights = MutableStateFlow(false)
    val isGeneratingInsights: StateFlow<Boolean> = _isGeneratingInsights.asStateFlow()

    fun generateInsights() {
        if (_isGeneratingInsights.value) return
        _isGeneratingInsights.value = true

        viewModelScope.launch {
            val current = uiState.value
            val currencyCode = CurrencySettings.currencyCode.value
            val systemPrompt =
                "You are a friendly personal finance assistant embedded in the user's finance-tracking app. " +
                    "Identify 2-4 specific, useful observations about their spending this period using the " +
                    "numbers provided, and present them via the present_insights tool. Be concrete with " +
                    "amounts and category names. Avoid generic advice like 'track your spending' or " +
                    "'create a budget'."
            val summary = buildString {
                appendLine("Currency: $currencyCode")
                appendLine("Period income: ${current.periodIncome}")
                appendLine("Period expense: ${current.periodExpense}")
                appendLine("Spending by category (mainCategory / category: total):")
                current.categoryBreakdown.forEach {
                    appendLine("- ${it.mainCategory} / ${it.categoryName}: ${it.total}")
                }
            }

            AiService.generateInsights(systemPrompt, summary, maxTokens = 600L)
                .onSuccess { cards ->
                    AiInsightsCache.save(
                        cards.ifEmpty {
                            listOf(
                                InsightCard(
                                    label = "Insights",
                                    value = "—",
                                    detail = "Couldn't generate insights this time — try again.",
                                    tone = InsightTone.NEUTRAL
                                )
                            )
                        }
                    )
                }
                .onFailure { error ->
                    AiInsightsCache.save(
                        listOf(
                            InsightCard(
                                label = "Insights",
                                value = "—",
                                detail = "Couldn't generate insights: ${error.message}",
                                tone = InsightTone.WARNING
                            )
                        )
                    )
                }
            _isGeneratingInsights.value = false
        }
    }

    fun dismissInsights() {
        AiInsightsCache.clear()
    }
}

/** For a specific account, its own budget. For "All accounts" (null), the combined total of
 * every account's own budget when any are set, falling back to the budget explicitly set under
 * "All accounts" itself otherwise. */
private fun resolveOverallBudget(overallBudgets: Map<Long?, Double>, selectedAccountId: Long?): Double? {
    if (selectedAccountId != null) return overallBudgets[selectedAccountId]
    val perAccount = overallBudgets.filterKeys { it != null }
    return if (perAccount.isNotEmpty()) perAccount.values.sum() else overallBudgets[null]
}

/** Same combining rule as [resolveOverallBudget], applied per category: a category with any
 * per-account budgets sums those; a category with none falls back to its "All accounts" budget. */
private fun resolveCategoryBudgets(
    allCategoryBudgets: Map<Pair<Long, Long?>, Double>,
    selectedAccountId: Long?
): Map<Long, Double> {
    if (selectedAccountId != null) {
        return allCategoryBudgets.filterKeys { it.second == selectedAccountId }.mapKeys { it.key.first }
    }
    val perAccountByCategory = allCategoryBudgets
        .filterKeys { it.second != null }
        .entries
        .groupBy({ it.key.first }) { it.value }
        .mapValues { (_, amounts) -> amounts.sum() }
    val allAccountsByCategory = allCategoryBudgets.filterKeys { it.second == null }.mapKeys { it.key.first }
    return (perAccountByCategory.keys + allAccountsByCategory.keys).associateWith { categoryId ->
        perAccountByCategory[categoryId] ?: allAccountsByCategory.getValue(categoryId)
    }
}
