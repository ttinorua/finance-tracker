package com.financetracker.app.ui.screens.receipts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.stores.Store
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.receipts.Receipts
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.CategoryPickerDialog
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.IconTile
import com.financetracker.app.ui.components.StoreLogo
import com.financetracker.app.ui.theme.IncomeGreen
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.Formatters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiptUiState(
    val loaded: Boolean = false,
    val receipt: Receipt? = null,
    val items: List<ReceiptItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** The bank payment it's filed with. */
    val transaction: Transaction? = null,
    val account: Account? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModel(private val repository: FinanceRepository, private val receiptId: Long) : ViewModel() {
    val state: StateFlow<ReceiptUiState> = combine(
        repository.observeReceipt(receiptId),
        repository.observeReceiptItems(receiptId),
        repository.observeCategories(),
        repository.observeAccounts()
    ) { receipt, items, categories, accounts -> Quad(receipt, items, categories, accounts) }
        .mapLatest { (receipt, items, categories, accounts) ->
            val tx = receipt?.transactionId?.let { repository.getTransaction(it) }
            ReceiptUiState(true, receipt, items, categories, tx, tx?.let { t -> accounts.firstOrNull { it.id == t.accountId } })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiptUiState())

    private val _choices = MutableStateFlow<List<TransactionWithDetails>?>(null)
    /** Payments to pick from, while "Change" is open. */
    val choices: StateFlow<List<TransactionWithDetails>?> = _choices

    fun setSplit(split: Boolean) = update { it.copy(split = split) }

    fun setItemCategory(item: ReceiptItem, categoryId: Long?) {
        viewModelScope.launch { repository.updateReceiptItem(item.copy(categoryId = categoryId)) }
    }

    fun openChoices() {
        val receipt = state.value.receipt ?: return
        viewModelScope.launch {
            _choices.value = Receipts.matchChoices(repository, receipt.total, receipt.purchasedAt, receipt.storeName, receipt.transactionId).second
        }
    }

    fun closeChoices() {
        _choices.value = null
    }

    fun match(transactionId: Long?) {
        _choices.value = null
        update { it.copy(transactionId = transactionId) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteReceipt(receiptId)
            onDone()
        }
    }

    private fun update(change: (Receipt) -> Receipt) {
        val receipt = state.value.receipt ?: return
        viewModelScope.launch { repository.updateReceipt(change(receipt)) }
    }

    private data class Quad(val receipt: Receipt?, val items: List<ReceiptItem>, val categories: List<Category>, val accounts: List<Account>)
}

/** A saved receipt: its payment, every item grouped by category (tap one to move it), and the
 * switch that splits the payment into those categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScreen(viewModel: ReceiptViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val choices by viewModel.choices.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    var editingItem by remember { mutableStateOf<ReceiptItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete receipt") } }
            )
        }
    ) { padding ->
        val receipt = state.receipt
        if (receipt == null) {
            if (state.loaded) {
                Text("This receipt has been deleted.", Modifier.padding(padding).padding(24.dp))
            }
            return@Scaffold
        }
        val byId = state.categories.associateBy { it.id }
        val tx = state.transaction
        // Items without a category stay in the payment's own category.
        val fallback = tx?.categoryId?.let(byId::get)
        val groups = state.items.groupBy { it.categoryId?.let(byId::get) ?: fallback }
            .entries.sortedByDescending { (_, items) -> items.sumOf { it.amount } }
        // A discount column (like the receipt itself) when any item had a discount.
        val discounts = state.items.any { it.discount > 0.004 }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StoreLogo(receipt.storeName, 40.dp) { IconTile(Icons.AutoMirrored.Filled.ReceiptLong, MaterialTheme.colorScheme.primary, 40.dp) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(receipt.storeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Formatters.fullDate(receipt.purchasedAt)} · ${sourceLabel(receipt.source)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("-${Formatters.currency(receipt.total, currency)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (tx != null) {
                                "Paid: ${tx.note.ifBlank { "payment" }} · ${Formatters.date(tx.date)}" + (state.account?.let { " · ${it.name}" } ?: "")
                            } else {
                                "Not matched to a bank payment yet — it's matched after a bank sync."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::openChoices) { Text(if (tx != null) "Change" else "Pick") }
                    }
                }
            }
            item {
                SplitSwitch(parts = groups.size, checked = receipt.split, enabled = tx != null, onChange = viewModel::setSplit)
            }
            if (discounts) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("Discount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(DISCOUNT_COLUMN))
                        Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(TOTAL_COLUMN))
                    }
                }
            }
            groups.forEach { (category, items) ->
                item(key = "c${category?.id}") {
                    GlassCard {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryIconTile(category?.id, category?.name, category?.mainCategory, category?.colorHex, size = 32.dp)
                                Text(
                                    category?.name ?: "Uncategorized",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                                )
                                Text(Formatters.amount(items.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
                            items.forEach { item ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { editingItem = item }.padding(start = 56.dp, end = 14.dp, top = 7.dp, bottom = 7.dp)
                                ) {
                                    val qty = if (item.quantity != 1.0) " ×${Formatters.amount(item.quantity).removeSuffix(",00").removeSuffix(".00")}" else ""
                                    Text("${item.name}$qty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (discounts) {
                                        Text(
                                            if (item.discount > 0.004) "-${Formatters.amount(item.discount)}" else "–",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (item.discount > 0.004) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.width(DISCOUNT_COLUMN)
                                        )
                                    }
                                    Text(
                                        Formatters.amount(item.amount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        modifier = if (discounts) Modifier.width(TOTAL_COLUMN) else Modifier
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (receipt.discount > 0) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Discounts", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("-${Formatters.amount(receipt.discount)}", style = MaterialTheme.typography.bodyMedium, color = IncomeGreen)
                    }
                }
            }
            item {
                Text(
                    "Tap an item to move it to another category.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    editingItem?.let { item ->
        CategoryPickerDialog(
            title = item.name,
            categories = state.categories.filter { it.type == com.financetracker.app.data.db.entity.TransactionType.EXPENSE },
            selected = item.categoryId?.let { id -> state.categories.firstOrNull { it.id == id }?.let { CategoryFilter.Single(it.id, it.name) } },
            onDismiss = { editingItem = null },
            onSelect = { filter ->
                viewModel.setItemCategory(item, (filter as? CategoryFilter.Single)?.categoryId)
                editingItem = null
            }
        )
    }

    choices?.let { list ->
        AlertDialog(
            onDismissRequest = viewModel::closeChoices,
            title = { Text("Which payment is it?") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (list.isEmpty()) {
                        item { Text("No card payments found around that date.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(list, key = { it.id }) { choice ->
                        TextButton(onClick = { viewModel.match(choice.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(choice.note.ifBlank { choice.categoryName ?: "Payment" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Formatters.date(choice.date)} · ${choice.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("-${Formatters.amount(choice.amount)}")
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.match(null) }) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = viewModel::closeChoices) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete receipt?") },
            text = { Text("The bank payment stays; it just counts in its own category again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Widths of the discount and total columns, so they line up across the category cards. */
private val DISCOUNT_COLUMN = 72.dp
private val TOTAL_COLUMN = 72.dp

internal fun sourceLabel(source: String): String = when (source) {
    "share" -> "shared"
    "photo" -> "photo"
    else -> "from ${Store.byId(source)?.company ?: source.replaceFirstChar { it.uppercase() }}"
}
