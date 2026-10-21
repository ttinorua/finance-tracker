package com.financetracker.app.ui.screens.trends

import com.financetracker.app.data.prefs.ViewFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.TrendGranularity
import com.financetracker.app.util.TrendPoint
import com.financetracker.app.util.buildTrend
import com.financetracker.app.util.transfersInCountAsIncome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TrendsWindow(val months: Int, val label: String) {
    SIX(6, "6 months"),
    TWELVE(12, "12 months")
}

data class TrendsUiState(
    val granularity: TrendGranularity = TrendGranularity.MONTH,
    val window: TrendsWindow = TrendsWindow.SIX,
    val trends: List<TrendPoint> = emptyList(),
    val avgIncome: Double = 0.0,
    val avgExpense: Double = 0.0,
    val best: TrendPoint? = null,
    val worst: TrendPoint? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    val categories: List<Category> = emptyList(),
    val categoryFilter: CategoryFilter = CategoryFilter.All
)

private data class TrendsFilters(
    val granularity: TrendGranularity,
    val window: TrendsWindow,
    val selectedAccountId: Long?,
    val categoryFilter: CategoryFilter
)

/** Income/expense/net per month (the last 6 or 12) or per year, across all accounts or one, and
 * for every category or just one category or main category. */
class TrendsViewModel(private val repository: FinanceRepository) : ViewModel() {

    private val _granularity = MutableStateFlow(TrendGranularity.MONTH)
    private val _window = MutableStateFlow(TrendsWindow.SIX)
    // Shared with the other tabs (Trends keeps its own 6/12-month range).
    private val _selectedAccountId = ViewFilter.accountId
    private val _categoryFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)

    val uiState: StateFlow<TrendsUiState> = combine(
        repository.observeTransactions(),
        combine(repository.observeAccounts(), repository.observeCategories()) { accounts, categories -> accounts to categories },
        BudgetSettings.shiftSalaryToNextMonth,
        BudgetSettings.excludeTransfersFromSpending,
        combine(_granularity, _window, _selectedAccountId, _categoryFilter) { granularity, window, accountId, filter ->
            TrendsFilters(granularity, window, accountId, filter)
        }
    ) { allTransactions, (accounts, categories), shiftSalary, excludeTransfers, filters ->
        val (granularity, window, selectedAccountId, categoryFilter) = filters
        val transactions = if (selectedAccountId != null) {
            allTransactions.filter { it.accountId == selectedAccountId }
        } else {
            allTransactions
        }
        val trends = buildTrend(
            transactions,
            granularity,
            categoryFilter,
            shiftSalary,
            excludeTransfers,
            transfersInAreIncome = transfersInCountAsIncome(selectedAccountId, MainAccountSettings.mainAccountId.value),
            monthCount = window.months
        )

        TrendsUiState(
            granularity = granularity,
            window = window,
            trends = trends,
            avgIncome = trends.map { it.income }.average().takeIf { it.isFinite() } ?: 0.0,
            avgExpense = trends.map { it.expense }.average().takeIf { it.isFinite() } ?: 0.0,
            best = trends.maxByOrNull { it.net },
            worst = trends.minByOrNull { it.net },
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            categories = categories,
            categoryFilter = categoryFilter
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsUiState())

    fun selectGranularity(granularity: TrendGranularity) {
        _granularity.value = granularity
    }

    fun selectWindow(window: TrendsWindow) {
        _window.value = window
    }

    fun selectAccount(accountId: Long?) {
        _selectedAccountId.value = accountId
    }

    fun selectCategoryFilter(filter: CategoryFilter) {
        _categoryFilter.value = filter
    }
}
