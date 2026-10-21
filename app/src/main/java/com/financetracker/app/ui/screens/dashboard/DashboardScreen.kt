package com.financetracker.app.ui.screens.dashboard

import com.financetracker.app.data.ai.AiSummary
import com.financetracker.app.data.ai.WeeklySummary
import com.financetracker.app.ui.components.HeaderButton
import com.financetracker.app.ui.components.ScreenHeader
import com.financetracker.app.ui.screens.household.attentionItems
import com.financetracker.app.ui.screens.household.HouseholdHomeCard
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.household.HouseholdState
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.data.ai.AiService
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.ui.components.AccountSelectorChip
import com.financetracker.app.ui.components.AiOrb
import com.financetracker.app.ui.components.BudgetProgressRow
import com.financetracker.app.ui.components.CategoryBreakdownList
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.PeriodSelectorChip
import com.financetracker.app.ui.components.figureStyle
import com.financetracker.app.ui.screens.ai.AdvisorLaunch
import com.financetracker.app.ui.screens.overview.TransactionsDrillDown
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.gradientBrush
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.advisor.AttentionFilter
import com.financetracker.app.util.advisor.AttentionItem
import com.financetracker.app.util.advisor.AttentionLevel
import java.util.Calendar
import kotlin.math.abs

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenAskAi: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenHousehold: () -> Unit = {},
    /** Tiles and the overall budget open the main categories behind them first; [upcoming] adds
     * the not-yet-paid bills (the Spent tile). */
    onOpenBreakdown: (drillDown: TransactionsDrillDown, upcoming: Boolean) -> Unit,
    onOpenTransactions: (
        type: TransactionType?,
        categoryId: Long?,
        label: String,
        periodOption: PeriodOption,
        customRange: Pair<Long, Long>?,
        includeAnticipated: Boolean,
        accountId: Long?,
        uncategorizedOnly: Boolean
    ) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val currencyCode by CurrencySettings.currencyCode.collectAsState()
    val summary by WeeklySummary.summary.collectAsState()
    val isMakingSummary by viewModel.isMakingSummary.collectAsState()
    val summaryError by viewModel.summaryError.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsState()
    val monitorItems by viewModel.attentionItems.collectAsState()
    val household by HouseholdSync.state.collectAsState()
    val dismissals by AttentionMonitor.dismissals.collectAsState()
    // The household budget's over-budget lines join the app's own checks.
    val attentionItems = remember(monitorItems, household, dismissals) {
        monitorItems + household.attentionItems().filterNot { AttentionMonitor.isDismissed(it.key) }
    }
    val context = LocalContext.current
    val askNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(attentionItems.isNotEmpty()) {
        if (attentionItems.isNotEmpty() && AttentionMonitor.shouldAskPermission(context)) {
            AttentionMonitor.markPermissionAsked()
            askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DashboardContent(
        state = state,
        currencyCode = currencyCode,
        summary = summary,
        isMakingSummary = isMakingSummary,
        summaryError = summaryError,
        isSyncing = isSyncing,
        lastSyncedAt = lastSyncedAt,
        attentionItems = attentionItems,
        aiConfigured = AiService.isConfigured,
        household = household.takeIf { it.joined },
        onOpenHousehold = onOpenHousehold,
        onSelectPeriod = viewModel::selectPeriod,
        onSelectCustomRange = viewModel::selectCustomRange,
        onSelectAccount = viewModel::selectAccount,
        onDismissAttention = viewModel::dismissAttention,
        onRefreshSummary = viewModel::refreshSummary,
        onHideSummary = viewModel::hideSummary,
        onOpenAskAi = onOpenAskAi,
        onOpenTrends = onOpenTrends,
        onOpenBreakdown = onOpenBreakdown,
        onOpenTransactions = onOpenTransactions
    )
}

/** The dashboard itself, given everything it shows. */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    currencyCode: String,
    summary: AiSummary?,
    isMakingSummary: Boolean,
    summaryError: String?,
    isSyncing: Boolean,
    lastSyncedAt: Long?,
    attentionItems: List<AttentionItem>,
    aiConfigured: Boolean,
    household: HouseholdState? = null,
    onOpenHousehold: () -> Unit = {},
    onSelectPeriod: (PeriodOption) -> Unit,
    onSelectCustomRange: (Long, Long) -> Unit,
    onSelectAccount: (Long?) -> Unit,
    onDismissAttention: (String) -> Unit,
    onRefreshSummary: () -> Unit,
    onHideSummary: () -> Unit,
    onOpenAskAi: () -> Unit,
    onOpenTrends: () -> Unit,
    /** Tiles and the overall budget open the main categories behind them first; [upcoming] adds
     * the not-yet-paid bills (the Spent tile). */
    onOpenBreakdown: (drillDown: TransactionsDrillDown, upcoming: Boolean) -> Unit,
    onOpenTransactions: (
        type: TransactionType?,
        categoryId: Long?,
        label: String,
        periodOption: PeriodOption,
        customRange: Pair<Long, Long>?,
        includeAnticipated: Boolean,
        accountId: Long?,
        uncategorizedOnly: Boolean
    ) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Header(
                title = if (state.periodOption == PeriodOption.THIS_MONTH) {
                    Formatters.month(System.currentTimeMillis())
                } else {
                    state.periodOption.label
                },
                syncLine = lastSyncedAt?.let { "synced ${Formatters.shortWhen(it)}" },
                isSyncing = isSyncing,
                onOpenTrends = onOpenTrends,
                onOpenAskAi = onOpenAskAi
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodSelectorChip(
                    option = state.periodOption,
                    customRange = state.customRange,
                    onOptionSelected = onSelectPeriod,
                    onCustomRangeSelected = onSelectCustomRange,
                    options = PeriodOption.entries
                )
                AccountSelectorChip(
                    accounts = state.accounts,
                    selectedAccountId = state.selectedAccountId,
                    onAccountSelected = onSelectAccount
                )
            }
        }
        item {
            BalanceCard(
                label = "Net balance · " +
                    (state.accounts.firstOrNull { it.id == state.selectedAccountId }?.name ?: "all accounts"),
                balance = state.netBalance,
                currencyCode = currencyCode,
                change = state.balanceChange,
                trend = state.balanceTrend,
                onClick = {
                    onOpenBreakdown(
                        TransactionsDrillDown("Net balance", state.periodOption, state.customRange, state.selectedAccountId),
                        false
                    )
                }
            )
        }
        item {
            val palette = LocalAppPalette.current
            val left = state.periodIncome - state.periodExpense
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniFigure(
                    "Income", Formatters.whole(state.periodIncome), TextStyle(color = palette.positive), Modifier.weight(1f)
                ) {
                    onOpenBreakdown(
                        TransactionsDrillDown("Income", state.periodOption, state.customRange, state.selectedAccountId, type = TransactionType.INCOME),
                        false
                    )
                }
                MiniFigure(
                    if (state.anticipateRecurringBillsEnabled) "Spent +" else "Spent",
                    Formatters.whole(state.periodExpense),
                    TextStyle(color = palette.negative),
                    Modifier.weight(1f)
                ) {
                    onOpenBreakdown(
                        TransactionsDrillDown("Spent", state.periodOption, state.customRange, state.selectedAccountId, type = TransactionType.EXPENSE),
                        true
                    )
                }
                MiniFigure(
                    "Left",
                    Formatters.whole(left),
                    if (left >= 0) palette.figureStyle() else TextStyle(color = palette.negative),
                    Modifier.weight(1f),
                    onClick = null
                )
            }
        }
        if (household != null) {
            item { HouseholdHomeCard(household, onClick = onOpenHousehold) }
        }
        if (attentionItems.isNotEmpty()) {
            item {
                NeedsAttentionCard(
                    items = attentionItems,
                    canAsk = aiConfigured,
                    onAsk = { prompt ->
                        AdvisorLaunch.ask(prompt)
                        onOpenAskAi()
                    },
                    onSeeTransactions = { filter ->
                        onOpenTransactions(
                            filter.type,
                            filter.categoryId,
                            filter.label,
                            if (filter.thisMonthOnly) PeriodOption.THIS_MONTH else PeriodOption.ALL_TIME,
                            null,
                            false,
                            filter.accountId,
                            filter.uncategorizedOnly
                        )
                    },
                    onDismiss = onDismissAttention
                )
            }
        }
        if (aiConfigured) {
            item {
                SummaryCard(
                    summary = summary,
                    isMaking = isMakingSummary,
                    error = summaryError,
                    onRefresh = onRefreshSummary,
                    onHide = onHideSummary,
                    onAsk = { prompt ->
                        AdvisorLaunch.ask(prompt)
                        onOpenAskAi()
                    }
                )
            }
        }
        val budgetStatus = state.budgetStatus
        if (budgetStatus.overallBudget != null || budgetStatus.categoryStatuses.isNotEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(Icons.Filled.DonutLarge, "Budget · this month", MaterialTheme.colorScheme.primary)
                        budgetStatus.overallBudget?.let { overallBudget ->
                            BudgetProgressRow(
                                label = "Overall",
                                spent = budgetStatus.overallSpent,
                                budget = overallBudget,
                                currencyCode = currencyCode,
                                modifier = Modifier.padding(top = 8.dp),
                                onClick = {
                                    onOpenBreakdown(
                                        TransactionsDrillDown("Overall budget", PeriodOption.THIS_MONTH, null, state.selectedAccountId, type = TransactionType.EXPENSE),
                                        false
                                    )
                                }
                            )
                        }
                        budgetStatus.categoryStatuses.forEach { catStatus ->
                            BudgetProgressRow(
                                label = catStatus.categoryName,
                                spent = catStatus.spent,
                                budget = catStatus.budget,
                                currencyCode = currencyCode,
                                colorHex = catStatus.colorHex,
                                onClick = {
                                    onOpenTransactions(
                                        TransactionType.EXPENSE, catStatus.categoryId, catStatus.categoryName, PeriodOption.THIS_MONTH,
                                        null, false, state.selectedAccountId, false
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        if (state.uncategorizedCount > 0) {
            item {
                val warning = LocalAppPalette.current.warning
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenTransactions(
                            null, null, "Uncategorized", state.periodOption, state.customRange, false, state.selectedAccountId, true
                        )
                    }
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconDot(Icons.Filled.Category, warning)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                "${state.uncategorizedCount} uncategorized transaction${if (state.uncategorizedCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Give them a category so totals and budgets are right",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = "Spending by category",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
        item {
            if (state.categoryBreakdown.isEmpty()) {
                EmptyState(message = "No expenses recorded for this period.")
            } else {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    CategoryBreakdownList(
                        categories = state.categoryBreakdown,
                        currencyCode = currencyCode,
                        onCategoryClick = { spend ->
                            onOpenTransactions(
                                TransactionType.EXPENSE, spend.categoryId, spend.categoryName, state.periodOption,
                                state.customRange, false, state.selectedAccountId, false
                            )
                        },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** "Good evening" over the month, with Trends and the AI orb on the right. */
@Composable
private fun Header(
    title: String,
    syncLine: String?,
    isSyncing: Boolean,
    onOpenTrends: () -> Unit,
    onOpenAskAi: () -> Unit
) {
    // The shared header (Activity, Spending and Trends use the same one).
    ScreenHeader(smallLine = greeting() + (syncLine?.let { " · $it" } ?: ""), title = title, onOpenAskAi = onOpenAskAi) {
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        HeaderButton(Icons.AutoMirrored.Filled.ShowChart, "Trends", onOpenTrends)
    }
}

/** The big net balance, its change over 30 days and a sparkline of the same 30 days. */
@Composable
private fun BalanceCard(
    label: String,
    balance: Double,
    currencyCode: String,
    change: Double?,
    trend: List<Double>,
    onClick: () -> Unit
) {
    val palette = LocalAppPalette.current
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (change != null) ChangePill(change)
            }
            val formatted = Formatters.amount(balance)
            val whole = formatted.substringBeforeLast('.')
            val decimals = formatted.substring(whole.length)
            Text(
                buildAnnotatedString {
                    append(whole)
                    withStyle(SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = palette.muted)) {
                        append("$decimals ${Formatters.currencySymbol(currencyCode)}")
                    }
                },
                style = MaterialTheme.typography.displaySmall.merge(palette.figureStyle()),
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (trend.size >= 2) Sparkline(trend, Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp))
        }
    }
}

@Composable
private fun ChangePill(change: Double) {
    val palette = LocalAppPalette.current
    val up = change >= 0
    val colour = if (up) palette.positive else palette.negative
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = .13f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = if (up) "Up" else "Down",
            tint = colour,
            modifier = Modifier.size(14.dp)
        )
        Text(
            String.format(java.util.Locale.US, "%.1f%%", abs(change)),
            style = MaterialTheme.typography.labelMedium,
            color = colour,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun Sparkline(points: List<Double>, modifier: Modifier = Modifier) {
    val accent = LocalAppPalette.current.accent
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val dot = 4.5.dp.toPx()
        val top = dot
        val usable = size.height - dot * 2
        val step = (size.width - dot) / (points.size - 1)
        fun y(v: Double) = if (max == min) size.height / 2 else top + (1 - ((v - min) / range).toFloat()) * usable
        val line = Path()
        points.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * step, y(v)) }
        val fill = Path().apply {
            addPath(line)
            lineTo((points.size - 1) * step, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = .28f), accent.copy(alpha = 0f))))
        drawPath(
            line,
            Brush.horizontalGradient(listOf(accent.copy(alpha = .5f), accent)),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(accent, radius = dot, center = Offset((points.size - 1) * step, y(points.last())))
    }
}

@Composable
private fun MiniFigure(label: String, value: String, valueStyle: TextStyle, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    GlassCard(modifier = modifier, shape = RoundedCornerShape(18.dp), onClick = onClick) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.merge(valueStyle).copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String, tint: Color, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        trailing()
    }
}

@Composable
private fun IconDot(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = .14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    )
}

/** The AI's read on the period, framed in the AI gradient. */
/** "Needs your attention": what the app's checks found (see AttentionChecks), most important
 * first. Only shown when there's something; each item can be opened, taken to the advisor or
 * dismissed. */
@Composable
private fun NeedsAttentionCard(
    items: List<AttentionItem>,
    canAsk: Boolean,
    onAsk: (String) -> Unit,
    onSeeTransactions: (AttentionFilter) -> Unit,
    onDismiss: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalAppPalette.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Row(modifier = Modifier.padding(end = 8.dp)) {
                SectionTitle(Icons.Filled.NotificationsActive, "Needs your attention", palette.warning) {
                    Text("${items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val visible = if (expanded) items else items.take(3)
            visible.forEachIndexed { i, item ->
                HorizontalDivider(
                    modifier = Modifier.padding(top = if (i == 0) 10.dp else 0.dp, end = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AttentionRow(item, canAsk, onAsk, onSeeTransactions, onDismiss)
            }
            if (items.size > 3) {
                Link(if (expanded) "Show less" else "Show all ${items.size}") { expanded = !expanded }
            }
        }
    }
}

@Composable
private fun AttentionRow(
    item: AttentionItem,
    canAsk: Boolean,
    onAsk: (String) -> Unit,
    onSeeTransactions: (AttentionFilter) -> Unit,
    onDismiss: (String) -> Unit
) {
    val palette = LocalAppPalette.current
    val accent = when (item.level) {
        AttentionLevel.URGENT -> palette.negative
        AttentionLevel.WARNING -> palette.warning
        AttentionLevel.GOOD -> palette.positive
        AttentionLevel.INFO -> palette.accent
    }
    val icon = when (item.level) {
        AttentionLevel.URGENT -> Icons.Filled.Error
        AttentionLevel.WARNING -> Icons.Filled.Warning
        AttentionLevel.GOOD -> Icons.Filled.CheckCircle
        AttentionLevel.INFO -> Icons.Filled.Info
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        IconDot(icon, accent)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item.filter?.let { filter -> Link("See transactions") { onSeeTransactions(filter) } }
                if (canAsk && item.prompt != null) Link(item.promptLabel) { onAsk(item.prompt) }
            }
        }
        IconButton(onClick = { onDismiss(item.key) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
