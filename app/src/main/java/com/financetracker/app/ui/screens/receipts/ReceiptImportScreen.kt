package com.financetracker.app.ui.screens.receipts

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.ai.ParsedReceipt
import com.financetracker.app.data.ai.ReceiptReader
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.receipts.Receipts
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ReceiptImportState(
    val reading: Boolean = true,
    val error: String? = null,
    val receipt: ParsedReceipt? = null,
    val categories: List<Category> = emptyList(),
    /** The bank transaction it'll be filed with, or null to wait for the next bank sync. */
    val match: TransactionWithDetails? = null,
    /** Possible transactions for "Change match": the likely ones first, then others nearby. */
    val choices: List<TransactionWithDetails> = emptyList(),
    val split: Boolean = true,
    /** Set once saved: the receipt to open. */
    val savedId: Long? = null,
    val alreadySaved: Boolean = false
)

class ReceiptImportViewModel(
    private val repository: FinanceRepository,
    private val context: Context,
    private val files: List<Uri>,
    private val source: String
) : ViewModel() {
    private val _state = MutableStateFlow(ReceiptImportState())
    val state: StateFlow<ReceiptImportState> = _state

    init {
        read()
    }

    fun read() {
        _state.value = ReceiptImportState(reading = true)
        viewModelScope.launch {
            val categories = repository.observeCategories().first()
            if (files.isEmpty()) {
                _state.value = ReceiptImportState(reading = false, error = "There was nothing to read.")
                return@launch
            }
            ReceiptReader.read(context, files, categories)
                .onSuccess { receipt ->
                    val existing = repository.findReceipt(source, receipt.externalId)
                    if (existing != null) {
                        _state.value = ReceiptImportState(reading = false, receipt = receipt, savedId = existing.id, alreadySaved = true)
                        return@onSuccess
                    }
                    val (best, choices) = Receipts.matchChoices(repository, receipt.total, receipt.purchasedAt, receipt.storeName)
                    _state.value = ReceiptImportState(
                        reading = false,
                        receipt = receipt,
                        categories = categories,
                        match = best,
                        choices = choices
                    )
                }
                .onFailure { e -> _state.value = ReceiptImportState(reading = false, error = e.message ?: "Couldn't read the receipt.") }
        }
    }

    fun choose(tx: TransactionWithDetails?) {
        _state.value = _state.value.copy(match = tx)
    }

    fun setSplit(split: Boolean) {
        _state.value = _state.value.copy(split = split)
    }

    fun save() {
        val s = _state.value
        val receipt = s.receipt ?: return
        viewModelScope.launch {
            val id = Receipts.save(repository, receipt, source, s.match?.id, s.split)
            _state.value = _state.value.copy(savedId = id)
        }
    }
}

/** Reading a shared or photographed receipt: what the AI found, the bank payment it belongs to
 * (to confirm or change), and whether to split that payment into the receipt's categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptImportScreen(viewModel: ReceiptImportViewModel, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val state by viewModel.state.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(state.savedId, state.alreadySaved) {
        val id = state.savedId
        if (id != null && !state.alreadySaved) onSaved(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add receipt") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } }
            )
        }
    ) { padding ->
        val receipt = state.receipt
        when {
            state.reading -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Reading the receipt…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The AI reads the store, every item and its price, and sorts the items into your categories.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            state.error != null || receipt == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Couldn't read it", style = MaterialTheme.typography.titleMedium)
                Text(state.error ?: "Couldn't read the receipt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::read) { Text("Try again") }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
            state.alreadySaved -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Already saved", style = MaterialTheme.typography.titleMedium)
                Text("This ${receipt.storeName} receipt from ${Formatters.date(receipt.purchasedAt)} is already in the app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { state.savedId?.let(onSaved) }) { Text("Open it") }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GlassCard(aiBorder = true) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(
                                    "Receipt read · ${receipt.items.size} item${if (receipt.items.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            Text(
                                "${receipt.storeName} · ${Formatters.fullDate(receipt.purchasedAt)} · ${Formatters.currency(receipt.total, currency)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val match = state.match
                            Text(
                                if (match != null) {
                                    "Matched to ${match.note.ifBlank { "a payment" }} · ${Formatters.date(match.date)} · -${Formatters.amount(match.amount)} on ${match.accountName}"
                                } else {
                                    "No bank payment found yet. It's matched automatically after a bank sync, or pick one now."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(onClick = viewModel::save) { Text(if (state.split && match != null) "Save & split" else "Save") }
                                OutlinedButton(onClick = { choosing = true }) { Text(if (match != null) "Change match" else "Pick payment") }
                            }
                        }
                    }
                }
                item {
                    val groups = groupByCategory(receipt, state.categories)
                    SplitSwitch(
                        parts = groups.size,
                        checked = state.split,
                        enabled = true,
                        onChange = viewModel::setSplit
                    )
                }
                val groups = groupByCategory(receipt, state.categories)
                groups.forEach { group ->
                    item(key = "g${group.category?.id}") {
                        GlassCard {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val c = group.category
                                    CategoryIconTile(c?.id, c?.name, c?.mainCategory, c?.colorHex, size = 32.dp)
                                    Text(
                                        c?.name ?: "Other (stays in the payment's category)",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(Formatters.amount(group.total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                group.items.forEach { (name, amount) ->
                                    Row(Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp)) {
                                        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(Formatters.amount(amount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "You can change an item's category on the receipt after saving. The receipt photo isn't kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Which payment is it?") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (state.choices.isEmpty()) {
                        item { Text("No card payments found around that date.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(state.choices, key = { it.id }) { tx ->
                        TextButton(onClick = { viewModel.choose(tx); choosing = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(tx.note.ifBlank { tx.categoryName ?: "Payment" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Formatters.date(tx.date)} · ${tx.accountName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("-${Formatters.amount(tx.amount)}")
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.choose(null); choosing = false }) { Text("None — wait for bank sync") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } }
        )
    }
}

/** The "Split into N categories" switch, shared by the import and the receipt screen. */
@Composable
internal fun SplitSwitch(parts: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (parts > 1) "Split into $parts categories" else "Split by category",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Budgets count each part in its own category instead of the whole amount in one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

private class ItemGroup(val category: Category?, val total: Double, val items: List<Pair<String, Double>>)

private fun groupByCategory(receipt: ParsedReceipt, categories: List<Category>): List<ItemGroup> {
    val byId = categories.associateBy { it.id }
    return receipt.items.groupBy { it.categoryId?.let(byId::get) }
        .map { (category, items) -> ItemGroup(category, items.sumOf { it.amount }, items.map { it.name to it.amount }) }
        .sortedWith(compareBy<ItemGroup> { it.category == null }.thenByDescending { it.total })
}
