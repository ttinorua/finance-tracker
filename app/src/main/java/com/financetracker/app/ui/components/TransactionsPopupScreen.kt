package com.financetracker.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.data.ai.MerchantLogoLookup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.ui.screens.receipts.ReceiptNav
import com.financetracker.app.util.ReceiptSplits
import com.financetracker.app.ui.screens.transactions.AddEditTransactionSheet
import com.financetracker.app.util.AnticipatedExpense
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.SimilarTransactionsPrompt

/**
 * A drill-down window: a title, an editable transaction list (tap to edit, long-press to
 * delete, FAB to add), and a close button back to the caller — the same editing affordances
 * as the main Transactions tab, available from any grouped/filtered view.
 *
 * When [showAnticipatedSections] is true (only ever the Dashboard's own Expenses tile, with
 * "Anticipate recurring bills" on), the list splits into an "Upcoming expenses" section listing
 * [anticipatedExpenses] — read-only, since they aren't real transactions yet — above a "Posted
 * expenses" section with the normal editable [transactions]. Every other caller leaves
 * [showAnticipatedSections] false and sees the same flat, editable list as always.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsPopupScreen(
    title: String,
    transactions: List<TransactionWithDetails>,
    accounts: List<Account>,
    categories: List<Category>,
    onClose: () -> Unit,
    onAddTransaction: (
        amount: Double,
        type: TransactionType,
        accountId: Long,
        categoryId: Long?,
        date: Long,
        note: String
    ) -> Unit,
    onUpdateTransaction: (
        id: Long,
        amount: Double,
        type: TransactionType,
        accountId: Long,
        categoryId: Long?,
        date: Long,
        note: String
    ) -> Unit,
    onDeleteTransaction: (TransactionWithDetails) -> Unit,
    showAnticipatedSections: Boolean = false,
    anticipatedExpenses: List<AnticipatedExpense> = emptyList(),
    onDismissAnticipated: (AnticipatedExpense) -> Unit = {},
    similarPrompt: SimilarTransactionsPrompt? = null,
    onApplySimilarUpdate: () -> Unit = {},
    onDismissSimilarPrompt: () -> Unit = {}
) {
    var editingTransaction by remember { mutableStateOf<TransactionWithDetails?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TransactionWithDetails?>(null) }
    var dismissTarget by remember { mutableStateOf<AnticipatedExpense?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingTransaction = null
                showAddEditDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        }
    ) { padding ->
        if (transactions.isEmpty() && anticipatedExpenses.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                message = "No transactions in this period."
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showAnticipatedSections) {
                    if (anticipatedExpenses.isNotEmpty()) {
                        item { SectionHeader("Upcoming expenses", anticipatedExpenses.size) }
                        items(anticipatedExpenses, key = { it.dismissKey }) { item ->
                            Card {
                                AnticipatedExpenseRow(
                                    item = item,
                                    onLongClick = { dismissTarget = item },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                    if (transactions.isNotEmpty()) {
                        item { SectionHeader("Posted expenses", transactions.size) }
                    }
                } else {
                    item {
                        val count = transactions.size
                        Text(
                            text = "$count transaction${if (count == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(transactions, key = { it.id }) { tx ->
                    Card {
                        TransactionRow(
                            transaction = tx,
                            onClick = {
                                // A purchase with a receipt opens the receipt (a split one shows as its parts).
                                if (ReceiptNav.hasReceipt(tx.id)) {
                                    ReceiptNav.openForTransaction(tx.id)
                                } else {
                                    editingTransaction = tx
                                    showAddEditDialog = true
                                }
                            },
                            onLongClick = { if (ReceiptSplits.isPart(tx.id)) ReceiptNav.openForTransaction(tx.id) else deleteTarget = tx },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAddEditDialog) {
        AddEditTransactionSheet(
            existing = editingTransaction,
            accounts = accounts,
            categories = categories,
            onDismiss = { showAddEditDialog = false },
            onSave = { amount, type, accountId, categoryId, date, note ->
                val current = editingTransaction
                if (current == null) {
                    onAddTransaction(amount, type, accountId, categoryId, date, note)
                } else {
                    onUpdateTransaction(current.id, amount, type, accountId, categoryId, date, note)
                }
                showAddEditDialog = false
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete transaction?") },
            text = {
                Text(
                    "This will permanently remove \"${target.note.ifBlank { target.categoryName ?: "this transaction" }}\"."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTransaction(target)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    dismissTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { dismissTarget = null },
            title = { Text("Remove from Upcoming expenses?") },
            text = {
                Text(
                    "\"${MerchantLogoLookup.nameFor(target.label) ?: target.label}\" won't count toward this month's Expenses total anymore. " +
                        "It comes back automatically once it actually posts, or again next month."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissAnticipated(target)
                    dismissTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { dismissTarget = null }) { Text("Cancel") } }
        )
    }

    similarPrompt?.let { prompt ->
        SimilarTransactionsPromptDialog(
            prompt = prompt,
            onApply = onApplySimilarUpdate,
            onDismiss = onDismissSimilarPrompt
        )
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        text = "$label ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** A row for a not-yet-posted recurring expense — same visual shape as [TransactionRow]
 * (category dot, label, category, amount) but never tap-to-edit, since there's no real
 * transaction behind it yet — either a projection, or a payment the bank has scheduled but not
 * booked (labelled "Scheduled by bank", see [AnticipatedExpense.scheduledByBank]).
 * Long-press still works, to remove it from this month's "Upcoming expenses" (see
 * [onLongClick]/[com.financetracker.app.data.prefs.DismissedRecurringExpenses]) — unlike a real,
 * posted transaction, this is the only kind of row a user can ever remove this way. The date is
 * prefixed with "~" when it's a rough guess rather than a date backed by a consistent historical
 * pattern (see [AnticipatedExpense.dateIsEstimated]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnticipatedExpenseRow(item: AnticipatedExpense, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Same short company name and logo as posted transactions (TransactionRow), so the
            // person's own "Change logo & name" choices apply here too.
            val cachedDomains by MerchantLogoLookup.domains.collectAsState()
            val cachedNames by MerchantLogoLookup.names.collectAsState()
            val overrides by MerchantOverrides.rules.collectAsState()
            LaunchedEffect(item.label) { MerchantLogoLookup.request(item.label) }
            CompanyLogo(
                domain = MerchantLogoLookup.domainFor(item.label, cachedDomains, overrides),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                photo = MerchantOverrides.photoFor(item.label, overrides)
            ) {
                CategoryIconTile(categoryId = null, name = item.category, mainCategory = item.mainCategory, colorHex = item.colorHex, size = 36.dp)
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = MerchantLogoLookup.nameFor(item.label, cachedNames, overrides) ?: item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (item.scheduledByBank) "Scheduled by bank · ${item.category}" else item.category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "-" + Formatters.amount(item.amount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = (if (item.dateIsEstimated) "~" else "") + Formatters.date(item.estimatedDate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
