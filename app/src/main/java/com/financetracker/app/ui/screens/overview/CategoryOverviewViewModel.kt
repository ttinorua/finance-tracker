package com.financetracker.app.ui.screens.overview

import com.financetracker.app.data.prefs.ViewFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.ui.components.BarChartEntry
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.GroupByOption
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.countsTowardTotals
import com.financetracker.app.util.transfersInCountAsIncome
import com.financetracker.app.util.effectiveReportingDate
import com.financetracker.app.util.groupKeyOf
import com.financetracker.app.util.TrendGranularity
import com.financetracker.app.util.TrendPoint
import com.financetracker.app.util.buildTrend
import com.financetracker.app.util.periodRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoryOverviewUiState(
    val periodOption: PeriodOption = PeriodOption.THIS_MONTH,
    val customRange: Pair<Long, Long>? = null,
    val groupBy: GroupByOption = GroupByOption.MAIN_CATEGORY,
    val entries: List<BarChartEntry> = emptyList(),
    val totalExpense: Double = 0.0,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: Long? = null,
    val categories: List<Category> = emptyList(),
    val categoryFilter: CategoryFilter = CategoryFilter.All,
    val granularity: TrendGranularity = TrendGranularity.MONTH,
    val trend: List<TrendPoint> = emptyList(),
    val averageExpense: Double = 0.0
)

private data class OverviewFilters(
    val periodOption: PeriodOption,
    val customRange: Pair<Long, Long>?,
    val groupBy: GroupByOption,
    val selectedAccountId: Long?,
    val categoryFilter: CategoryFilter,
    val granularity: TrendGranularity
)

/** Spending only: a trend of expenses over months or years, and a breakdown of the selected
 * period's expenses by account, main category or category — both narrowed to one account and
 * one category or main category when chosen. Income lives on the Dashboard and Trends screens. */
class CategoryOverviewViewModel(private val repository: FinanceRepository) : ViewModel() {

    // Account and period are shared with the other tabs and remembered (see ViewFilter).
    private val _periodOption = ViewFilter.period
    private val _customRange = ViewFilter.customRange
    private val _groupBy = MutableStateFlow(GroupByOption.MAIN_CATEGORY)
    private val _selectedAccountId = ViewFilter.accountId
    private val _categoryFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)
    private val _granularity = MutableStateFlow(TrendGranularity.MONTH)

    val uiState: StateFlow<CategoryOverviewUiState> = combine(
        repository.observeTransactions(),
        combine(repository.observeAccounts(), repository.observeCategories()) { accounts, categories -> accounts to categories },
        combine(
            combine(_periodOption, _customRange, _groupBy) { periodOption, customRange, groupBy ->
                Triple(periodOption, customRange, groupBy)
            },
            _selectedAccountId,
            _categoryFilter,
            _granularity
        ) { (periodOption, customRange, groupBy), selectedAccountId, categoryFilter, granularity ->
            OverviewFilters(periodOption, customRange, groupBy, selectedAccountId, categoryFilter, granularity)
        },
        BudgetSettings.shiftSalaryToNextMonth,
        BudgetSettings.excludeTransfersFromSpending
    ) { allTransactions, (accounts, categories), filters, shiftSalary, excludeTransfers ->
        val (periodOption, customRange, groupBy, selectedAccountId, categoryFilter, granularity) = filters
        val transfersInAreIncome = transfersInCountAsIncome(selectedAccountId, MainAccountSettings.mainAccountId.value)
        val expenses = allTransactions.filter {
            it.type == TransactionType.EXPENSE && (selectedAccountId == null || it.accountId == selectedAccountId)
        }
        val (from, to) = periodRange(periodOption, customRange)
        val inPeriod = expenses.filter {
            val effectiveDate =
                effectiveReportingDate(it.date, it.type, it.mainCategoryName, it.categoryName, shiftSalary)
            effectiveDate >= from && effectiveDate < to && categoryFilter.matches(it) &&
                countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome)
        }

        val entries = inPeriod
            .groupBy { groupKeyOf(it, groupBy) }
            .map { (key, txs) -> BarChartEntry(key = key, label = key, income = 0.0, expense = txs.sumOf { it.amount }) }
            .sortedByDescending { it.expense }

        val trend = buildTrend(
            expenses,
            granularity,
            categoryFilter,
            shiftSalary,
            excludeTransfers,
            transfersInAreIncome
        )

        CategoryOverviewUiState(
            periodOption = periodOption,
            customRange = customRange,
            groupBy = groupBy,
            entries = entries,
            totalExpense = inPeriod.sumOf { it.amount },
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            categories = categories.filter { it.type == TransactionType.EXPENSE },
            categoryFilter = categoryFilter,
            granularity = granularity,
            trend = trend,
            averageExpense = trend.map { it.expense }.average().takeIf { it.isFinite() } ?: 0.0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryOverviewUiState())

    fun selectPeriod(option: PeriodOption) {
        _periodOption.value = option
    }

    fun selectCustomRange(start: Long, endExclusive: Long) = ViewFilter.selectCustomRange(start, endExclusive)

    fun selectGroupBy(option: GroupByOption) {
        _groupBy.value = option
    }

    fun selectAccount(accountId: Long?) {
        _selectedAccountId.value = accountId
    }

    fun selectCategoryFilter(filter: CategoryFilter) {
        _categoryFilter.value = filter
    }

    fun selectGranularity(granularity: TrendGranularity) {
        _granularity.value = granularity
    }
}
