package com.financetracker.app.ui.screens.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.CategoryIcons
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.IconTile
import com.financetracker.app.ui.components.MainCategoryIconTile
import com.financetracker.app.ui.components.figureStyle
import com.financetracker.app.ui.components.parseHexColour
import com.financetracker.app.ui.components.readable
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.BreakdownRow
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.UNCATEGORIZED
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption
import kotlin.math.roundToInt

/** A step on the way from a tapped number to its transactions: its main categories, or (given
 * [mainCategory]) the categories inside one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownScreen(
    viewModel: BreakdownViewModel,
    base: TransactionsDrillDown,
    mainCategory: String?,
    onBack: () -> Unit,
    /** Back to the main categories (from the path under the title). */
    onOpenMainCategories: () -> Unit,
    onOpenMainCategory: (String) -> Unit,
    onOpenTransactions: (TransactionsDrillDown) -> Unit,
    onOpenUpcoming: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    BreakdownContent(state, currency, base, mainCategory, onBack, onOpenMainCategories, onOpenMainCategory, onOpenTransactions, onOpenUpcoming)
}

/** [BreakdownScreen] given what it shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownContent(
    state: BreakdownUiState,
    currency: String,
    base: TransactionsDrillDown,
    mainCategory: String?,
    onBack: () -> Unit,
    onOpenMainCategories: () -> Unit,
    onOpenMainCategory: (String) -> Unit,
    onOpenTransactions: (TransactionsDrillDown) -> Unit,
    onOpenUpcoming: () -> Unit
) {
    val palette = LocalAppPalette.current
    val mixed = base.type == null
    // Opened from a main category elsewhere (e.g. Food on Spending), the title is that category.
    val rootLabel = if (mainCategory != null && base.title == mainCategory) "All" else base.title
    val period = when (base.periodOption) {
        PeriodOption.THIS_MONTH -> Formatters.month(System.currentTimeMillis())
        PeriodOption.CUSTOM -> null
        else -> base.periodOption.label
    }

    fun amountOf(row: BreakdownRow) = when (base.type) {
        TransactionType.INCOME -> row.income
        TransactionType.EXPENSE -> row.expense
        null -> row.net
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(mainCategory ?: base.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Crumbs(
                            listOfNotNull(
                                (rootLabel to onOpenMainCategories).takeIf { mainCategory != null },
                                period?.let { it to null }
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (state.loaded && state.rows.isEmpty() && state.upcoming == 0.0) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding), message = "Nothing here for this period.")
            return@Scaffold
        }
        val total = state.rows.sumOf { it.size }.takeIf { it > 0 } ?: 1.0
        val biggest = state.rows.maxOfOrNull { it.size }?.takeIf { it > 0 } ?: 1.0
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Summary(
                    state = state,
                    base = base,
                    mainCategory = mainCategory,
                    currency = currency,
                    rowColours = state.rows.map { rowColour(it, mainCategory != null) }
                )
            }
            if (state.upcoming > 0) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenUpcoming) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Filled.EventRepeat, palette.warning, 38.dp)
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Upcoming bills", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "Expected this period, not paid yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(Formatters.whole(state.upcoming), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.transactionCount > 0) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onOpenTransactions(
                                if (mainCategory == null) {
                                    base
                                } else {
                                    base.copy(
                                        title = mainCategory,
                                        categoryFilter = CategoryFilter.Main(mainCategory)
                                    )
                                }
                            )
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "See all ${state.transactionCount} ${mainCategory?.let { "$it " } ?: ""}transaction" +
                                    if (state.transactionCount == 1) "" else "s",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        state.rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            RowItem(
                                row = row,
                                isCategory = mainCategory != null,
                                amount = amountOf(row),
                                signed = mixed,
                                share = row.size / total,
                                barFraction = (row.size / biggest).toFloat(),
                                onClick = {
                                    if (mainCategory == null) {
                                        if (row.mainCategory == UNCATEGORIZED) {
                                            onOpenTransactions(base.copy(title = row.label, categoryFilter = CategoryFilter.Uncategorized))
                                        } else {
                                            onOpenMainCategory(row.mainCategory)
                                        }
                                    } else {
                                        onOpenTransactions(
                                            base.copy(
                                                title = row.label,
                                                categoryFilter = row.categoryId?.let { CategoryFilter.Single(it, row.label) }
                                                    ?: CategoryFilter.Uncategorized
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rowColour(row: BreakdownRow, isCategory: Boolean): Color =
    LocalAppPalette.current.readable(if (isCategory) parseHexColour(row.colorHex) else CategoryIcons.mainColour(row.mainCategory))

/** The path under the title. Steps with an action can be tapped to go back to them. */
@Composable
private fun Crumbs(steps: List<Pair<String, (() -> Unit)?>>) {
    if (steps.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (label, action) ->
            if (i > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (action != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = action) else Modifier
            )
        }
    }
}

@Composable
private fun Summary(state: BreakdownUiState, base: TransactionsDrillDown, mainCategory: String?, currency: String, rowColours: List<Color>) {
    val palette = LocalAppPalette.current
    val shown = state.income + state.expense
    val all = state.baseIncome + state.baseExpense
    val headline = when (base.type) {
        TransactionType.INCOME -> state.income
        TransactionType.EXPENSE -> state.expense + state.upcoming
        null -> state.income - state.expense
    }
    val count = state.rows.size
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mainCategory != null) {
                    MainCategoryIconTile(mainCategory, size = 46.dp, modifier = Modifier.padding(end = 14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        (mainCategory ?: base.title) + " · " +
                            "$count ${if (mainCategory == null) "main " else ""}categor${if (count == 1) "y" else "ies"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (base.type == null && headline > 0) "+" else "") + Formatters.whole(headline) + " " + Formatters.currencySymbol(currency),
                        style = MaterialTheme.typography.headlineMedium.merge(palette.figureStyle()),
                        maxLines = 1
                    )
                }
                // Inside one main category: its share of everything behind the tapped number.
                if (mainCategory != null && all > 0 && shown / all < .995) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("of the total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(shown / all * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (base.type == null) {
                Text(
                    "In ${Formatters.whole(state.income)} · Out ${Formatters.whole(state.expense)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // How the total splits, in each row's colour.
            if (state.rows.size > 1 && shown > 0) {
                Row(
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    state.rows.forEachIndexed { i, row ->
                        val weight = (row.size / shown).toFloat()
                        if (weight > 0.002f) Box(Modifier.weight(weight).fillMaxHeight().background(rowColours[i]))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowItem(
    row: BreakdownRow,
    isCategory: Boolean,
    amount: Double,
    signed: Boolean,
    share: Double,
    barFraction: Float,
    onClick: () -> Unit
) {
    val palette = LocalAppPalette.current
    val colour = rowColour(row, isCategory)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCategory) {
            CategoryIconTile(row.categoryId, row.label.takeIf { row.categoryId != null }, row.mainCategory, row.colorHex, size = 38.dp)
        } else {
            MainCategoryIconTile(row.mainCategory)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (isCategory || row.topCategories.isEmpty() || row.topCategories == listOf(row.label)) "${row.count} transaction${if (row.count == 1) "" else "s"}"
                else row.topCategories.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
            ) {
                Box(Modifier.fillMaxWidth(barFraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colour))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (signed && amount > 0) "+" else "") + Formatters.whole(amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (signed && amount > 0) palette.positive else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).width(20.dp)
        )
    }
}
