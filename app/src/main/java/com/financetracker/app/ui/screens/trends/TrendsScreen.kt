package com.financetracker.app.ui.screens.trends

import androidx.compose.foundation.layout.statusBarsPadding
import com.financetracker.app.ui.components.ScreenHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.ui.components.AccountSelectorChip
import com.financetracker.app.ui.components.CategoryFilterChip
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.NetTrendChart
import com.financetracker.app.ui.components.SummaryCard
import com.financetracker.app.ui.components.TrendBarChart
import com.financetracker.app.ui.components.TrendSeries
import com.financetracker.app.ui.screens.overview.GranularityToggle
import com.financetracker.app.ui.screens.overview.TransactionsDrillDown
import com.financetracker.app.ui.theme.ExpenseRed
import com.financetracker.app.ui.theme.IncomeGreen
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.TrendGranularity
import com.financetracker.app.util.TrendPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    viewModel: TrendsViewModel,
    onBack: () -> Unit,
    onOpenTransactions: (TransactionsDrillDown) -> Unit,
    onOpenAskAi: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val currencyCode by CurrencySettings.currencyCode.collectAsState()
    val hasData = state.trends.any { it.income != 0.0 || it.expense != 0.0 }
    val unit = if (state.granularity == TrendGranularity.MONTH) "month" else "year"
    val filterPrefix = state.categoryFilter.takeIf { it != CategoryFilter.All }?.let { "${it.label} · " } ?: ""

    fun open(title: String, from: Long, to: Long, type: TransactionType?) {
        onOpenTransactions(
            TransactionsDrillDown(
                title = filterPrefix + title,
                periodOption = PeriodOption.CUSTOM,
                customRange = from to to,
                accountId = state.selectedAccountId,
                type = type,
                categoryFilter = state.categoryFilter
            )
        )
    }

    fun openPoint(point: TrendPoint, type: TransactionType?) {
        val what = when (type) {
            TransactionType.INCOME -> "Income · "
            TransactionType.EXPENSE -> "Expenses · "
            null -> ""
        }
        open(what + point.longLabel, point.from, point.to, type)
    }

    fun openWholeRange(type: TransactionType) {
        val first = state.trends.firstOrNull() ?: return
        val last = state.trends.last()
        val what = if (type == TransactionType.INCOME) "Income" else "Expenses"
        open("$what · ${first.longLabel} – ${last.longLabel}", first.from, last.to, type)
    }

    Scaffold(
        // Same header as Home. The account is shared with the other tabs; Trends keeps its own range.
        topBar = {
            ScreenHeader(
                smallLine = "Trends",
                title = if (state.granularity == TrendGranularity.MONTH) "Last ${state.window.label}" else "By year",
                modifier = Modifier.statusBarsPadding().padding(end = 16.dp),
                onBack = onBack,
                onOpenAskAi = onOpenAskAi
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AccountSelectorChip(
                        accounts = state.accounts,
                        selectedAccountId = state.selectedAccountId,
                        onAccountSelected = viewModel::selectAccount
                    )
                    CategoryFilterChip(
                        categories = state.categories,
                        selected = state.categoryFilter,
                        onSelected = viewModel::selectCategoryFilter
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GranularityToggle(state.granularity, viewModel::selectGranularity)
                    if (state.granularity == TrendGranularity.MONTH) {
                        TrendsWindow.entries.forEach { window ->
                            FilterChip(
                                selected = state.window == window,
                                onClick = { viewModel.selectWindow(window) },
                                label = { Text(window.label) }
                            )
                        }
                    }
                }
            }
            if (!hasData) {
                item { EmptyState(message = "No transactions in this period yet.") }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Income vs. expense", style = MaterialTheme.typography.titleMedium)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                            ) {
                                LegendDot(IncomeGreen)
                                Text(
                                    "Income",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 6.dp, end = 16.dp)
                                )
                                LegendDot(ExpenseRed)
                                Text(
                                    "Expense",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                            TrendBarChart(
                                labels = state.trends.map { it.label },
                                subLabels = state.trends.map { it.subLabel },
                                series = listOf(
                                    TrendSeries("Income", IncomeGreen, state.trends.map { it.income }),
                                    TrendSeries("Expense", ExpenseRed, state.trends.map { it.expense })
                                ),
                                onBarClick = { index, seriesIndex ->
                                    val type = when (seriesIndex) {
                                        0 -> TransactionType.INCOME
                                        1 -> TransactionType.EXPENSE
                                        else -> null
                                    }
                                    state.trends.getOrNull(index)?.let { openPoint(it, type) }
                                }
                            )
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Net · income − expense", style = MaterialTheme.typography.titleMedium)
                            NetTrendChart(
                                trends = state.trends,
                                onBarClick = { index -> state.trends.getOrNull(index)?.let { openPoint(it, null) } },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Tap a bar or a figure below to see its transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Avg. income / $unit",
                            amount = Formatters.currency(state.avgIncome, currencyCode),
                            valueColor = IncomeGreen,
                            onClick = { openWholeRange(TransactionType.INCOME) },
                            amountStyle = MaterialTheme.typography.titleMedium,
                            contentPadding = 12.dp
                        )
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Avg. expense / $unit",
                            amount = Formatters.currency(state.avgExpense, currencyCode),
                            valueColor = ExpenseRed,
                            onClick = { openWholeRange(TransactionType.EXPENSE) },
                            amountStyle = MaterialTheme.typography.titleMedium,
                            contentPadding = 12.dp
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        state.best?.let { best ->
                            SummaryCard(
                                modifier = Modifier.weight(1f),
                                title = "Best $unit · ${best.longLabel}",
                                amount = Formatters.currency(best.net, currencyCode),
                                valueColor = if (best.net >= 0) IncomeGreen else ExpenseRed,
                                onClick = { openPoint(best, null) },
                                amountStyle = MaterialTheme.typography.titleMedium,
                                contentPadding = 12.dp
                            )
                        }
                        state.worst?.let { worst ->
                            SummaryCard(
                                modifier = Modifier.weight(1f),
                                title = "Worst $unit · ${worst.longLabel}",
                                amount = Formatters.currency(worst.net, currencyCode),
                                valueColor = if (worst.net >= 0) IncomeGreen else ExpenseRed,
                                onClick = { openPoint(worst, null) },
                                amountStyle = MaterialTheme.typography.titleMedium,
                                contentPadding = 12.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
