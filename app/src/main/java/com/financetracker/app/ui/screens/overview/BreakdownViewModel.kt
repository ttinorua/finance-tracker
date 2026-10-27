package com.financetracker.app.ui.screens.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.DismissedRecurringExpenses
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.toTransactionDetails
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.BreakdownRow
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.anticipatedRecurringExpenses
import com.financetracker.app.util.categoryBreakdown
import com.financetracker.app.util.countsTowardTotals
import com.financetracker.app.util.effectiveReportingDate
import com.financetracker.app.util.groupKeyOf
import com.financetracker.app.util.mainCategoryBreakdown
import com.financetracker.app.util.periodRange
import com.financetracker.app.util.transfersInCountAsIncome
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class BreakdownUiState(
    val rows: List<BreakdownRow> = emptyList(),
    /** Everything this breakdown covers (all main categories), for "share of all spending". */
    val baseIncome: Double = 0.0,
    val baseExpense: Double = 0.0,
    /** Money in and out of the rows shown. */
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val transactionCount: Int = 0,
    /** Bills expected this period but not yet paid (only when asked for, from the Spent tile). */
    val upcoming: Double = 0.0,
    val loaded: Boolean = false
)

/** The main categories (or, given [mainCategory], the categories inside it) behind a tapped
 * number. Transactions are picked exactly as [GroupTransactionsViewModel] picks them for
 * [base], so every total here matches the list it opens. */
class BreakdownViewModel(
    repository: FinanceRepository,
    private val base: TransactionsDrillDown,
    private val mainCategory: String?,
    showUpcoming: Boolean
) : ViewModel() {

    val uiState: StateFlow<BreakdownUiState> = combine(
        repository.observeTransactions(),
        combine(
            BudgetSettings.shiftSalaryToNextMonth,
            BudgetSettings.excludeTransfersFromSpending,
            BudgetSettings.anticipateRecurringBills
        ) { a, b, c -> Triple(a, b, c) },
        repository.observeCategories(),
        combine(DismissedRecurringExpenses.dismissed, FixedExpenseCategories.fixedCategoryIds.combine(FixedMerchants.merchants) { ids, _ -> ids }, BankScheduledPayments.payments) { a, b, c ->
            Triple(a, b, c)
        }
    ) { transactions, (shiftSalary, excludeTransfers, anticipate), categories, (dismissed, fixedIds, scheduled) ->
        val (from, to) = periodRange(base.periodOption, base.customRange)
        val transfersInAreIncome = transfersInCountAsIncome(base.accountId, MainAccountSettings.mainAccountId.value)
        val picked = transactions.filter {
            val date = effectiveReportingDate(it.date, it.type, it.mainCategoryName, it.categoryName, shiftSalary)
            date >= from && date < to &&
                (base.groupBy == null || groupKeyOf(it, base.groupBy) == base.key) &&
                (base.accountId == null || it.accountId == base.accountId) &&
                (base.type == null || it.type == base.type) &&
                base.categoryFilter.matches(it) &&
                countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome = transfersInAreIncome)
        }
        val rows = if (mainCategory == null) mainCategoryBreakdown(picked) else categoryBreakdown(picked, mainCategory)

        val monthsAhead = when (base.periodOption) {
            PeriodOption.THIS_MONTH -> 0
            PeriodOption.NEXT_MONTH -> 1
            else -> null
        }
        val upcoming = if (showUpcoming && anticipate && monthsAhead != null && mainCategory == null) {
            val byId = categories.associateBy { it.id }
            val accountScoped = transactions.filter { base.accountId == null || it.accountId == base.accountId }
            anticipatedRecurringExpenses(
                accountScoped,
                monthsAhead = monthsAhead,
                dismissedKeys = dismissed,
                fixedCategoryIds = fixedIds,
                scheduled = scheduled.filter { base.accountId == null || it.accountId == base.accountId }.map { it.toTransactionDetails(byId) },
                excludeTransfers = excludeTransfers,
                fixedMerchants = FixedMerchants.merchants.value
            ).sumOf { it.amount }
        } else {
            0.0
        }

        BreakdownUiState(
            rows = rows,
            baseIncome = picked.filter { it.type == TransactionType.INCOME }.sumOf { it.amount },
            baseExpense = picked.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount },
            income = rows.sumOf { it.income },
            expense = rows.sumOf { it.expense },
            transactionCount = rows.sumOf { it.count },
            upcoming = upcoming,
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BreakdownUiState())
}
