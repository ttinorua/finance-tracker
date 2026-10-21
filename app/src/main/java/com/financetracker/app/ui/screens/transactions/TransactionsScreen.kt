package com.financetracker.app.ui.screens.transactions

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import com.financetracker.app.ui.components.AccountSelectorChip
import com.financetracker.app.ui.components.periodTitle
import com.financetracker.app.ui.components.HeaderButton
import com.financetracker.app.ui.components.ScreenHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.ui.screens.receipts.ReceiptNav
import com.financetracker.app.util.ReceiptSplits
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.PeriodSelectorChip
import com.financetracker.app.ui.components.SimilarTransactionsPromptDialog
import com.financetracker.app.ui.components.TransactionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel, onOpenAskAi: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    // The search text comes straight from the filter, not from the (background-built) list,
    // so typing never lags or jumps.
    val filter by viewModel.filter.collectAsState()
    val similarPrompt by viewModel.similarPrompt.collectAsState()

    var editingTransaction by remember { mutableStateOf<TransactionWithDetails?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TransactionWithDetails?>(null) }
    var searchActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // Same header as Home: the shared account and period chips, search and the AI orb.
            androidx.compose.foundation.layout.Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                ScreenHeader(
                    smallLine = "Activity",
                    title = periodTitle(filter.periodOption),
                    onOpenAskAi = onOpenAskAi
                ) {
                    HeaderButton(
                        icon = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchActive) "Close search" else "Search"
                    ) {
                        if (searchActive) viewModel.setSearchQuery("")
                        searchActive = !searchActive
                    }
                }
                if (searchActive) {
                    OutlinedTextField(
                        value = filter.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        placeholder = { Text("Search note, category, amount") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PeriodSelectorChip(
                        option = filter.periodOption,
                        customRange = filter.customRange,
                        onOptionSelected = viewModel::selectPeriod,
                        onCustomRangeSelected = viewModel::selectCustomRange
                    )
                    AccountSelectorChip(
                        accounts = state.allAccounts,
                        selectedAccountId = filter.selectedAccountId,
                        onAccountSelected = viewModel::selectAccount
                    )
                }
            }
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
        if (state.displayItems.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                message = "No transactions match this view. Tap + to add one, or import a spreadsheet.",
                icon = Icons.Filled.ReceiptLong
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(state.displayItems) { item ->
                    when (item) {
                        is TransactionListItem.MonthHeader -> Text(
                            text = item.label,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                        is TransactionListItem.DateHeader -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.balanceLabel != null) {
                                Text(
                                    text = "Balance: ${item.balanceLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is TransactionListItem.Row -> Surface(shape = MaterialTheme.shapes.medium) {
                            TransactionRow(
                                transaction = item.tx,
                                runningBalance = item.runningBalance,
                                onClick = {
                                    // A purchase with a receipt opens the receipt (a split one shows as its parts).
                                    if (ReceiptNav.hasReceipt(item.tx.id)) {
                                        ReceiptNav.openForTransaction(item.tx.id)
                                    } else {
                                        editingTransaction = item.tx
                                        showAddEditDialog = true
                                    }
                                },
                                onLongClick = { if (ReceiptSplits.isPart(item.tx.id)) ReceiptNav.openForTransaction(item.tx.id) else deleteTarget = item.tx },
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddEditDialog) {
        AddEditTransactionSheet(
            existing = editingTransaction,
            accounts = state.allAccounts,
            categories = state.categories,
            onDismiss = { showAddEditDialog = false },
            onSave = { amount, type, accountId, categoryId, date, note ->
                val current = editingTransaction
                if (current == null) {
                    viewModel.addTransaction(amount, type, accountId, categoryId, date, note)
                } else {
                    viewModel.updateTransaction(current.id, amount, type, accountId, categoryId, date, note)
                }
                showAddEditDialog = false
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete transaction?") },
            text = { Text("This will permanently remove \"${target.note.ifBlank { target.categoryName ?: "this transaction" }}\".") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTransaction(target)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    similarPrompt?.let { prompt ->
        SimilarTransactionsPromptDialog(
            prompt = prompt,
            onApply = viewModel::applySimilarCategoryUpdate,
            onDismiss = viewModel::dismissSimilarPrompt
        )
    }
}
