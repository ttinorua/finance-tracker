package com.financetracker.app.ui.screens.receipts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.IconTile
import com.financetracker.app.ui.components.StoreLogo
import com.financetracker.app.ui.theme.ExpenseRed
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption
import com.financetracker.app.util.ShoppingStats
import com.financetracker.app.util.periodRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.financetracker.app.data.prefs.ViewFilter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ShoppingView(val label: String) { STORE("By store"), CATEGORY("By category"), ITEM("Items") }

data class ShoppingState(
    val periodOption: PeriodOption = PeriodOption.THIS_MONTH,
    val customRange: Pair<Long, Long>? = null,
    val view: ShoppingView = ShoppingView.STORE,
    val total: Double = 0.0,
    val receiptCount: Int = 0,
    val lines: List<ShoppingStats.Line> = emptyList(),
    val bought: List<ShoppingStats.Line> = emptyList(),
    val priceRises: List<ShoppingStats.PriceChange> = emptyList(),
    val receipts: List<Receipt> = emptyList(),
    val hasAnyReceipts: Boolean = false
)

/** Uses the app-wide [periodRange] (UTC calendar days, like every other screen) so the period
 * label and the receipts it covers always agree. */
class ShoppingViewModel(repository: FinanceRepository) : ViewModel() {
    /** Follows the period picked in the Spending header (shared with every tab). */
    private val period = combine(ViewFilter.period, ViewFilter.customRange) { option, custom -> option to custom }
    private val view = MutableStateFlow(ShoppingView.STORE)

    val state: StateFlow<ShoppingState> = combine(
        repository.observeReceipts(),
        repository.observeReceiptItemDetails(),
        period,
        view
    ) { receipts, items, (option, custom), view ->
        val range = periodRange(option, custom)
        val inMonth = receipts.filter { ShoppingStats.inRange(it.purchasedAt, range) }
        val byCategory = ShoppingStats.byCategory(items, range)
        ShoppingState(
            periodOption = option,
            customRange = custom,
            view = view,
            total = inMonth.sumOf { it.total },
            receiptCount = inMonth.size,
            lines = when (view) {
                ShoppingView.STORE -> ShoppingStats.byStore(receipts, range)
                ShoppingView.CATEGORY -> byCategory
                ShoppingView.ITEM -> ShoppingStats.byItem(items, range).take(30)
            },
            bought = if (view == ShoppingView.CATEGORY) emptyList() else byCategory,
            priceRises = ShoppingStats.priceRises(items, range).take(8),
            receipts = inMonth,
            hasAnyReceipts = receipts.isNotEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingState())

    fun selectView(v: ShoppingView) { view.value = v }
}

/** The Shopping tab of Spending: shopping in the chosen period from receipts — by store, by category or
 * by item — what got more expensive, and the receipts themselves. + adds a receipt. */
@Composable
fun ShoppingTab(viewModel: ShoppingViewModel, onOpenReceipt: (Long) -> Unit, modifier: Modifier = Modifier, onOpenStores: () -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addMenu by remember { mutableStateOf(false) }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = photoUri
        if (saved && uri != null) ReceiptNav.import(listOf(uri), "photo")
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { ReceiptNav.import(ReceiptFiles.copyIn(context, uris), "share") }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The period comes from the header's period chip; only the store and add buttons live here.
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenStores) { Icon(Icons.Filled.Storefront, contentDescription = "Store connections") }
                Box {
                    FilledIconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, contentDescription = "Add receipt") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Take a photo of a receipt") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                val uri = ReceiptFiles.newPhoto(context)
                                photoUri = uri
                                takePhoto.launch(uri)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Choose a screenshot or PDF") },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                pickFiles.launch(arrayOf("image/*", "application/pdf"))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Share from a store app")
                                    Text(
                                        "Open a receipt in Netto+, Coop, Lidl Plus, REMA 1000… → Share → Finance Tracker",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
        }
        if (!state.hasAnyReceipts) {
            item {
                EmptyState(
                    message = "No receipts yet. Connect a store account with the store button, share a receipt from a store app, " +
                        "or tap + to photograph a paper one. " +
                        "The AI reads every item, sorts it into your categories and finds the bank payment it belongs to.",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong
                )
            }
            return@LazyColumn
        }
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Spent in shops · ${state.receiptCount} receipt${if (state.receiptCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(Formatters.currency(state.total, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        ShoppingView.entries.forEach { v ->
                            FilterChip(selected = state.view == v, onClick = { viewModel.selectView(v) }, label = { Text(v.label) })
                        }
                    }
                }
            }
        }
        if (state.lines.isEmpty()) {
            item { Text("No receipts in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item { LinesCard(state.lines, state.view, currency) }
        }
        if (state.bought.isNotEmpty()) {
            item { SectionLabel("What you bought") }
            item { LinesCard(state.bought, ShoppingView.CATEGORY, currency) }
        }
        if (state.priceRises.isNotEmpty()) {
            item { SectionLabel("Price rises") }
            item {
                GlassCard {
                    state.priceRises.forEachIndexed { i, rise ->
                        if (i > 0) HorizontalDivider()
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.AutoMirrored.Filled.TrendingUp, ExpenseRed, 32.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(rise.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Formatters.amount(rise.now)} at ${rise.store} · was ${Formatters.amount(rise.before)} in ${Formatters.month(rise.beforeAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Text("+${rise.percent.toInt()}%", style = MaterialTheme.typography.bodyMedium, color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (state.receipts.isNotEmpty()) {
            item { SectionLabel("Receipts") }
            items(state.receipts, key = { it.id }) { receipt ->
                GlassCard(onClick = { onOpenReceipt(receipt.id) }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StoreLogo(receipt.storeName, 32.dp) { IconTile(Icons.Filled.Storefront, MaterialTheme.colorScheme.primary, 32.dp) }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(receipt.storeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Formatters.date(receipt.purchasedAt) + if (receipt.transactionId == null) " · waiting for bank payment" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("-${Formatters.amount(receipt.total)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun LinesCard(lines: List<ShoppingStats.Line>, view: ShoppingView, currency: String) {
    val max = lines.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
    GlassCard {
        lines.forEachIndexed { i, line ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                when (view) {
                    ShoppingView.STORE -> StoreLogo(line.label, 32.dp) { IconTile(Icons.Filled.Storefront, MaterialTheme.colorScheme.primary, 32.dp) }
                    else -> CategoryIconTile(line.categoryId, line.label.takeIf { line.categoryId != null }, line.mainCategory, line.colorHex, size = 32.dp)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(line.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when (view) {
                        ShoppingView.STORE -> "${line.count} receipt${if (line.count == 1) "" else "s"}"
                        ShoppingView.CATEGORY -> "${line.count} item${if (line.count == 1) "" else "s"}"
                        ShoppingView.ITEM -> "bought ${line.count}×"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                    ) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth((line.total / max).toFloat().coerceIn(0.02f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Text(Formatters.currency(line.total, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
