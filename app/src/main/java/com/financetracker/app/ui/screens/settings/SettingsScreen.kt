package com.financetracker.app.ui.screens.settings

import com.financetracker.app.ui.screens.household.HouseholdSettings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import com.financetracker.app.data.enablebanking.BankAutoSync
import com.financetracker.app.ui.components.parseHexColour
import com.financetracker.app.ui.components.categoryIconKey
import com.financetracker.app.ui.components.IconTile
import com.financetracker.app.ui.components.CategoryIcons
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.CategoryIconPicker
import com.financetracker.app.util.CategoryIconRules
import com.financetracker.app.data.prefs.CategoryIconChoices
import androidx.compose.material.icons.filled.Sync
import com.financetracker.app.ui.components.GlassCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.bank.Bank
import com.financetracker.app.data.bank.SupportedBanks
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.LinkedBankAccount
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.SUPPORTED_CURRENCIES
import com.financetracker.app.ui.components.AccountSelectorChip
import com.financetracker.app.ui.components.CategoryColorDot
import com.financetracker.app.ui.components.CategoryGroupHeader
import com.financetracker.app.ui.components.CategoryPickerDialog
import com.financetracker.app.ui.components.CompanyLogo
import com.financetracker.app.ui.components.accordionShape
import com.financetracker.app.ui.screens.importexport.ImportExportScreen
import com.financetracker.app.ui.screens.importexport.ImportExportViewModel
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_SCHEDULED_SHOWN = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    importExportViewModel: ImportExportViewModel,
    enableBankingViewModel: EnableBankingViewModel
) {
    val state by viewModel.uiState.collectAsState()
    val currencyCode by CurrencySettings.currencyCode.collectAsState()
    val mainAccountId by MainAccountSettings.mainAccountId.collectAsState()
    val shiftSalaryToNextMonth by BudgetSettings.shiftSalaryToNextMonth.collectAsState()
    val excludeTransfersFromSpending by BudgetSettings.excludeTransfersFromSpending.collectAsState()
    val anticipateRecurringBills by BudgetSettings.anticipateRecurringBills.collectAsState()
    val fixedExpenseCategoryIds by FixedExpenseCategories.fixedCategoryIds.collectAsState()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    var showAddAccount by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var deleteAccountTarget by remember { mutableStateOf<Account?>(null) }
    var editAccountTarget by remember { mutableStateOf<Account?>(null) }
    var mergeSourceAccount by remember { mutableStateOf<Account?>(null) }
    var mergePending by remember { mutableStateOf<Pair<Account, Account>?>(null) }
    var dedupeTarget by remember { mutableStateOf<Account?>(null) }
    var deleteCategoryTarget by remember { mutableStateOf<Category?>(null) }
    var iconTarget by remember { mutableStateOf<Category?>(null) }
    var collapsedMains by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
                navigationIcon = {
                    if (page != null) {
                        IconButton(onClick = { page = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (page == SettingsPage.ACCOUNTS) {
                FloatingActionButton(onClick = { showAddAccount = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when (page) {
                null -> SettingsHome(state, onOpen = { page = it })

                SettingsPage.ACCOUNTS -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(state.accounts, key = { it.account.id }) { accountUi ->
                        Card(modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            accountUi.account.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            Formatters.currency(accountUi.balance, currencyCode),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { editAccountTarget = accountUi.account }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Rename account")
                                    }
                                    IconButton(onClick = { deleteAccountTarget = accountUi.account }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete account")
                                    }
                                }
                                val isMain = mainAccountId == accountUi.account.id
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = isMain,
                                            role = Role.Checkbox,
                                            onValueChange = { viewModel.setMainAccount(accountUi.account, it) }
                                        )
                                ) {
                                    Checkbox(checked = isMain, onCheckedChange = null)
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text("Main account", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Shown by default on every screen",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(modifier = Modifier.align(Alignment.End)) {
                                    // A recovery tool for an account whose transactions got
                                    // doubled (e.g. merging two accounts that both held the same
                                    // real history, before the merge itself became duplicate-aware) —
                                    // collapses exact date/amount/type/note repeats back to one.
                                    TextButton(onClick = { dedupeTarget = accountUi.account }) {
                                        Text("Remove duplicates")
                                    }
                                    // Only worth offering once there's another account to merge
                                    // into — e.g. Enable Banking minted a fresh account on
                                    // reconnect that didn't get recognized as one already synced,
                                    // leaving two local accounts for the same real one. Moves
                                    // every transaction over instead of the data loss a plain
                                    // Delete above would cause.
                                    if (state.accounts.size > 1) {
                                        TextButton(onClick = { mergeSourceAccount = accountUi.account }) {
                                            Text("Merge into another account…")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsPage.CATEGORIES -> {
                    val grouped = state.categories.groupBy { it.mainCategory }.toSortedMap()
                    val allExpanded = collapsedMains.isEmpty()
                    val duplicates by viewModel.duplicateCategories.collectAsState()
                    val categoryMessage by viewModel.categoryMessage.collectAsState()
                    var confirmMerge by remember { mutableStateOf(false) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        categoryMessage?.let { message ->
                            item(key = "category_message") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = viewModel::clearCategoryMessage) { Text("OK") }
                                }
                            }
                        }
                        if (duplicates.isNotEmpty()) {
                            item(key = "category_duplicates") {
                                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val extra = duplicates.sumOf { it.remove.size }
                                        Text(
                                            "$extra duplicate ${if (extra == 1) "category" else "categories"} found",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            duplicates.joinToString("\n") { d ->
                                                "${d.keep.mainCategory} › ${d.keep.name} ← " +
                                                    d.remove.joinToString { "${it.mainCategory} › ${it.name}" }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(onClick = { confirmMerge = true }) { Text("Merge duplicates") }
                                    }
                                }
                            }
                        }
                        item(key = "category_tools") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { collapsedMains = if (allExpanded) grouped.keys.toSet() else emptySet() },
                                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = if (allExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Text(
                                        text = if (allExpanded) "Collapse all" else "Expand all",
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                                Button(
                                    onClick = { showAddCategory = true },
                                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(19.dp))
                                    Text("New category", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                        val groups = grouped.entries.toList()
                        groups.forEachIndexed { groupIndex, (mainCategory, subcategories) ->
                            val isExpanded = mainCategory !in collapsedMains
                            val isLastGroup = groupIndex == groups.lastIndex
                            val sortedSubcategories = subcategories.sortedBy { it.name.lowercase() }
                            item(key = "header_$mainCategory") {
                                CategoryGroupHeader(
                                    title = mainCategory,
                                    count = subcategories.size,
                                    expanded = isExpanded,
                                    shape = accordionShape(top = groupIndex == 0, bottom = isLastGroup && !isExpanded),
                                    onToggle = {
                                        collapsedMains = if (isExpanded) {
                                            collapsedMains + mainCategory
                                        } else {
                                            collapsedMains - mainCategory
                                        }
                                    },
                                    modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else 2.dp)
                                )
                            }
                            if (isExpanded) {
                                itemsIndexed(sortedSubcategories, key = { _, category -> category.id }) { index, category ->
                                    val isLastRow = index == sortedSubcategories.lastIndex
                                    CategoryAccordionRow(
                                        category = category,
                                        isFixed = category.id in fixedExpenseCategoryIds,
                                        onToggleFixed = { FixedExpenseCategories.setFixed(category.id, it) },
                                        onDelete = { deleteCategoryTarget = category },
                                        onChangeIcon = { iconTarget = category },
                                        showDivider = !isLastRow,
                                        shape = accordionShape(top = false, bottom = isLastGroup && isLastRow)
                                    )
                                }
                            }
                        }
                    }
                    if (confirmMerge) {
                        AlertDialog(
                            onDismissRequest = { confirmMerge = false },
                            title = { Text("Merge duplicate categories?") },
                            text = {
                                Text(
                                    "Each pair becomes one category: the one on the left of the arrow is kept, and " +
                                        "the transactions, receipt lines, budgets, Fixe marks and learned notes of the " +
                                        "others move onto it. Then the extra copies are removed.\n\n" +
                                        duplicates.joinToString("\n") { d ->
                                            "${d.keep.mainCategory} › ${d.keep.name} ← " +
                                                d.remove.joinToString { "${it.mainCategory} › ${it.name}" }
                                        }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmMerge = false
                                    viewModel.mergeDuplicateCategories()
                                }) { Text("Merge") }
                            },
                            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancel") } }
                        )
                    }
                }

                SettingsPage.BUDGETS -> BudgetsTab(
                    categories = state.categories.filter { it.type == TransactionType.EXPENSE },
                    accounts = state.accounts.map { it.account },
                    currencyCode = currencyCode
                )

                SettingsPage.BACKUP -> ImportExportScreen(importExportViewModel)

                SettingsPage.BANK -> BankTab(enableBankingViewModel)

                else -> Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (page == SettingsPage.APPEARANCE) AppearanceSettings()
                    if (page == SettingsPage.HOUSEHOLD) HouseholdSettings(state.accounts.map { it.account })

                    if (page == SettingsPage.GENERAL) {

                    Text(
                        "Display currency",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Text(
                        "Amounts are shown in this currency. This doesn't convert existing values.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)
                    )
                    CurrencyDropdown(
                        selected = currencyCode,
                        onSelected = { CurrencySettings.setCurrencyCode(it) }
                    )

                    Text(
                        "Shift salary to next month",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Text(
                        "Income categorized as Income • \"Pay, benefits and pension\" (a salary " +
                            "paid at month-end) counts toward next month's totals instead — the " +
                            "transaction keeps its real payment date, only dashboard and " +
                            "spending-overview totals shift.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = shiftSalaryToNextMonth,
                            onCheckedChange = { BudgetSettings.setShiftSalaryToNextMonth(it) }
                        )
                        Text(
                            text = if (shiftSalaryToNextMonth) "On" else "Off",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Text(
                        "Exclude transfers from totals",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Text(
                        "Transactions categorized as Other • \"Other (Transfer)\" (moving money " +
                            "between your own accounts, e.g. to or from savings) are left out of " +
                            "income and expense totals, budgets, and the spending breakdown, " +
                            "since they're neither real income nor real spending. When viewing a " +
                            "single account other than your main account, money transferred in " +
                            "from your other accounts counts as that account's income. Net " +
                            "balance and the account register still include every transaction.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = excludeTransfersFromSpending,
                            onCheckedChange = { BudgetSettings.setExcludeTransfersFromSpending(it) }
                        )
                        Text(
                            text = if (excludeTransfersFromSpending) "On" else "Off",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Text(
                        "Anticipate recurring bills",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Text(
                        "Bills in categories marked \"Fixe\" (Categories tab) that haven't " +
                            "posted yet are added to the Dashboard's Expenses tile at their last " +
                            "known amount, so Remaining reflects what's left once they go out — " +
                            "not just what you've spent so far. Only Fixe categories are " +
                            "anticipated; each merchant's billing cadence (monthly, quarterly, or " +
                            "yearly) is detected so it's only anticipated in the month it's " +
                            "actually due. Works for This month and Next month alike. Only " +
                            "affects that one tile; budgets, the spending breakdown, and every " +
                            "other screen are unaffected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = anticipateRecurringBills,
                            onCheckedChange = { BudgetSettings.setAnticipateRecurringBills(it) }
                        )
                        Text(
                            text = if (anticipateRecurringBills) "On" else "Off",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    }

                    if (page == SettingsPage.AI) AiAssistantSettings()


                    if (page == SettingsPage.LOANS_GOALS) LoansAndGoalsSettings(accounts = state.accounts)

                    if (page == SettingsPage.ATTENTION) AttentionSettings()

                    if (page == SettingsPage.AI) {

                    Text(
                        "AI categorization",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Text(
                        "One-time cleanup for transactions with no real category (mainly bank " +
                            "sync history, since it carries no category data at all). Uses your " +
                            "chosen AI above and can take a while for a large history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )

                    val isCategorizing by viewModel.isCategorizing.collectAsState()
                    val categorizationProgress by viewModel.categorizationProgress.collectAsState()
                    val categorizationMessage by viewModel.categorizationMessage.collectAsState()

                    categorizationMessage?.let { message ->
                        Card(modifier = Modifier.padding(bottom = 8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { viewModel.dismissCategorizationMessage() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    if (isCategorizing) {
                        val progress = categorizationProgress
                        if (progress != null && progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.done.toFloat() / progress.total },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                            )
                            Text(
                                "${progress.done} of ${progress.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.categorizeWithAi() }, enabled = !isCategorizing) {
                            Text(if (isCategorizing) "Categorizing…" else "Categorize with AI")
                        }
                        if (isCategorizing) {
                            TextButton(
                                onClick = { viewModel.cancelCategorization() },
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("Cancel") }
                        }
                    }
                    }
                }
            }
        }
    }

    if (showAddAccount) {
        AddAccountDialog(
            onDismiss = { showAddAccount = false },
            onConfirm = { name, balance ->
                viewModel.addAccount(name, balance)
                showAddAccount = false
            }
        )
    }

    if (showAddCategory) {
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onConfirm = { mainCategory, name, type, icon ->
                viewModel.addCategory(mainCategory, name, type, icon)
                showAddCategory = false
            }
        )
    }

    editAccountTarget?.let { account ->
        RenameAccountDialog(
            currentName = account.name,
            onDismiss = { editAccountTarget = null },
            onConfirm = { newName ->
                viewModel.updateAccount(account.copy(name = newName))
                editAccountTarget = null
            }
        )
    }

    deleteAccountTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteAccountTarget = null },
            title = { Text("Delete \"${account.name}\"?") },
            text = { Text("Its transactions will also be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(account)
                    deleteAccountTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteAccountTarget = null }) { Text("Cancel") } }
        )
    }

    mergeSourceAccount?.let { source ->
        val candidates = state.accounts.map { it.account }.filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { mergeSourceAccount = null },
            title = { Text("Merge \"${source.name}\" into…") },
            text = {
                Column {
                    Text(
                        "Pick the account to keep. Every transaction on \"${source.name}\" moves " +
                            "there, and \"${source.name}\" is removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    candidates.forEach { target ->
                        TextButton(
                            onClick = {
                                mergePending = source to target
                                mergeSourceAccount = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(target.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeSourceAccount = null }) { Text("Cancel") } }
        )
    }

    mergePending?.let { (source, target) ->
        AlertDialog(
            onDismissRequest = { mergePending = null },
            title = { Text("Merge \"${source.name}\" into \"${target.name}\"?") },
            text = {
                Text(
                    "Every transaction on \"${source.name}\" moves to \"${target.name}\", their " +
                        "balances combine, and \"${source.name}\" is removed. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.mergeAccounts(source, target)
                    mergePending = null
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { mergePending = null }) { Text("Cancel") } }
        )
    }

    dedupeTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { dedupeTarget = null },
            title = { Text("Remove duplicate transactions?") },
            text = {
                Text(
                    "Any transaction on \"${account.name}\" with the exact same date, amount, " +
                        "type and note as another one is collapsed down to a single copy. " +
                        "Genuinely distinct transactions are never touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deduplicateTransactions(account)
                    dedupeTarget = null
                }) { Text("Remove duplicates") }
            },
            dismissButton = { TextButton(onClick = { dedupeTarget = null }) { Text("Cancel") } }
        )
    }

    iconTarget?.let { category ->
        CategoryIconDialog(category, onDismiss = { iconTarget = null })
    }

    deleteCategoryTarget?.let { category ->
        AlertDialog(
            onDismissRequest = { deleteCategoryTarget = null },
            title = { Text("Delete \"${category.name}\"?") },
            text = { Text("Transactions using it will become uncategorized.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(category)
                    CategoryIconChoices.set(category.id, null)
                    deleteCategoryTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCategoryTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BudgetsTab(categories: List<Category>, accounts: List<Account>, currencyCode: String) {
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    val overallBudgets by BudgetLimits.overallBudgets.collectAsState()
    val allCategoryBudgets by BudgetLimits.categoryBudgets.collectAsState()
    val overallBudget = overallBudgets[selectedAccountId]
    val categoryBudgets = remember(allCategoryBudgets, selectedAccountId) {
        allCategoryBudgets.filterKeys { it.second == selectedAccountId }.mapKeys { it.key.first }
    }
    var overallText by remember(selectedAccountId) {
        mutableStateOf(overallBudget?.let { Formatters.amount(it) } ?: "")
    }
    var pendingCategoryIds by remember(selectedAccountId) { mutableStateOf(setOf<Long>()) }

    val categoryById = categories.associateBy { it.id }
    val activeCategories = (categoryBudgets.keys + pendingCategoryIds)
        .mapNotNull { categoryById[it] }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.mainCategory }, { it.name }))
    val availableCategories = categories
        .filterNot { it.id in categoryBudgets.keys || it.id in pendingCategoryIds }
        .sortedWith(compareBy({ it.mainCategory }, { it.name }))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                "Budgets are set per account (or for \"All accounts\" combined).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            AccountSelectorChip(
                accounts = accounts,
                selectedAccountId = selectedAccountId,
                onAccountSelected = { selectedAccountId = it }
            )
        }
        item {
            Text(
                "Overall monthly budget",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "A single spending limit across all expenses combined, compared against " +
                    "this calendar month's spending so far.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
            )
            OutlinedTextField(
                value = overallText,
                onValueChange = { text ->
                    overallText = text
                    val amount = text.replace(",", "").toDoubleOrNull()
                    BudgetLimits.setOverallBudget(selectedAccountId, if (text.isBlank()) null else amount)
                },
                label = { Text("Amount") },
                placeholder = { Text("No limit set") },
                leadingIcon = { Text(Formatters.currencySymbol(currencyCode)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                "Category budgets",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                "Optional monthly limits per category, shown on the dashboard once set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
            )
            AddCategoryBudgetSelector(
                availableCategories = availableCategories,
                onCategorySelected = { category -> pendingCategoryIds = pendingCategoryIds + category.id }
            )
        }
        if (activeCategories.isEmpty()) {
            item {
                Text(
                    "No category budgets set yet. Tap the button above to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else {
            items(activeCategories, key = { it.id }) { category ->
                CategoryBudgetRow(
                    category = category,
                    currentBudget = categoryBudgets[category.id],
                    currencyCode = currencyCode,
                    onBudgetChanged = { amount -> BudgetLimits.setCategoryBudget(category.id, selectedAccountId, amount) },
                    onRemove = {
                        BudgetLimits.setCategoryBudget(category.id, selectedAccountId, null)
                        pendingCategoryIds = pendingCategoryIds - category.id
                    }
                )
            }
        }
    }
}

@Composable
private fun AddCategoryBudgetSelector(
    availableCategories: List<Category>,
    onCategorySelected: (Category) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        enabled = availableCategories.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Add a category budget", modifier = Modifier.padding(start = 8.dp))
    }
    if (open) {
        CategoryPickerDialog(
            title = "Add a category budget",
            categories = availableCategories,
            selected = null,
            onDismiss = { open = false },
            onSelect = { filter ->
                val id = (filter as? CategoryFilter.Single)?.categoryId
                availableCategories.firstOrNull { it.id == id }?.let(onCategorySelected)
            },
            allowUncategorized = false
        )
    }
}

@Composable
private fun CategoryBudgetRow(
    category: Category,
    currentBudget: Double?,
    currencyCode: String,
    onBudgetChanged: (Double?) -> Unit,
    onRemove: () -> Unit
) {
    var text by remember(category.id) { mutableStateOf(currentBudget?.let { Formatters.amount(it) } ?: "") }

    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryColorDot(category.colorHex, modifier = Modifier.size(12.dp))
                Text(
                    text = "${category.mainCategory} • ${category.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove budget", modifier = Modifier.size(18.dp))
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    val amount = input.replace(",", "").toDoubleOrNull()
                    onBudgetChanged(if (input.isBlank()) null else amount)
                },
                label = { Text("Amount") },
                placeholder = { Text("No limit set") },
                leadingIcon = { Text(Formatters.currencySymbol(currencyCode)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyDropdown(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Currency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SUPPORTED_CURRENCIES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankDropdown(
    banks: List<Bank>,
    selected: Bank,
    onSelected: (Bank) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Bank") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            banks.forEach { bank ->
                DropdownMenuItem(
                    text = { Text(bank.displayName) },
                    leadingIcon = {
                        CompanyLogo(domain = bank.logoDomain, contentDescription = null) {
                            Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        onSelected(bank)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onConfirm: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var balanceText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Account") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("Starting balance") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val balance = balanceText.toDoubleOrNull() ?: 0.0
                    if (name.isNotBlank()) onConfirm(name.trim(), balance)
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Renaming only ever changes the account's display name (its id stays the same), so its
 * transactions, budgets and Enable Banking link are all unaffected — nothing else in the app
 * keys off this name. */
@Composable
private fun RenameAccountDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename account") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != currentName
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Change an existing category's icon, or go back to the one picked from its name. */
@Composable
private fun CategoryIconDialog(category: Category, onDismiss: () -> Unit) {
    val current = categoryIconKey(category.id, category.name, category.mainCategory)
    val guessed = CategoryIconRules.guess(category.name, category.mainCategory)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(category.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CategoryIconPicker(
                    selected = current,
                    colour = parseHexColour(category.colorHex),
                    onSelect = { key ->
                        CategoryIconChoices.set(category.id, key.takeIf { it != guessed })
                        onDismiss()
                    },
                    columns = 5
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (current != guessed) {
                TextButton(onClick = {
                    CategoryIconChoices.set(category.id, null)
                    onDismiss()
                }) { Text("Use automatic icon") }
            }
        }
    )
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String, String, TransactionType, String?) -> Unit) {
    var mainCategory by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    // Follows the name until the user picks one themselves.
    var pickedIcon by remember { mutableStateOf<String?>(null) }
    val guessedIcon = CategoryIconRules.guess(name, mainCategory)
    val icon = pickedIcon ?: guessedIcon

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = mainCategory,
                    onValueChange = { mainCategory = it },
                    label = { Text("Main category (e.g. Food)") },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Subcategory name") })
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.EXPENSE,
                        onClick = { type = TransactionType.EXPENSE },
                        label = { Text("Expense") }
                    )
                    FilterChip(
                        selected = type == TransactionType.INCOME,
                        onClick = { type = TransactionType.INCOME },
                        label = { Text("Income") }
                    )
                }
                Row(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(CategoryIcons.icon(icon), CategoryIcons.mainColour(mainCategory.ifBlank { "Uncategorized" }), 36.dp)
                    Text(
                        if (pickedIcon == null) "Icon · picked from the name, tap to change" else "Icon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                CategoryIconPicker(
                    selected = icon,
                    colour = CategoryIcons.mainColour(mainCategory.ifBlank { "Uncategorized" }),
                    onSelect = { pickedIcon = it },
                    columns = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        // Only a choice that differs from the name's own icon needs keeping.
                        onConfirm(
                            mainCategory.trim().ifBlank { "Uncategorized" },
                            name.trim(),
                            type,
                            pickedIcon?.takeIf { it != CategoryIconRules.guess(name.trim(), mainCategory.trim()) }
                        )
                    }
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BankTab(viewModel: EnableBankingViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var disconnectTarget by remember { mutableStateOf<BankConnectionUi?>(null) }

    LaunchedEffect(state.authUrl) {
        state.authUrl?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            viewModel.consumeAuthUrl()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // The explanation is for setting up; once a bank is connected the room goes to the accounts.
        if (state.connections.isEmpty()) {
            Text("Bank sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Link a bank account via Enable Banking (open banking / PSD2) to pull in " +
                    "transactions automatically instead of exporting and importing a spreadsheet. " +
                    "You can connect more than one bank at once.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp, top = 4.dp)
            )
        }

        EnableBankingAppRow(
            ownApplicationId = state.ownApplicationId,
            isConfigured = state.isConfigured,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (!state.isConfigured) return@Column

        state.statusMessage?.let { message ->
            Card(modifier = Modifier.padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.dismissStatusMessage() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (state.connections.isEmpty()) {
            val selectedBank = state.connectableBanks.firstOrNull { it.id == state.selectedBankId }
                ?: state.connectableBanks.first()
            BankDropdown(
                banks = state.connectableBanks,
                selected = selectedBank,
                onSelected = { bank -> viewModel.selectBank(bank.id) },
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Button(onClick = { viewModel.startBankAuth(selectedBank.id) }, enabled = !state.isStartingAuth) {
                if (state.isStartingAuth) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Connect ${selectedBank.displayName}")
                }
            }
            return@Column
        }

        val scheduledPayments by BankScheduledPayments.payments.collectAsState()
        val autoSync by BankAutoSync.enabled.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.lastSyncedAt?.let { "Last synced ${Formatters.syncTimestamp(it)}" } ?: "Never synced",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (autoSync) "Syncs automatically about every 6 hours" else "Syncs when you open the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoSync, onCheckedChange = { BankAutoSync.setEnabled(context, it) })
        }
        if (state.lastSyncedAt != null) {
            // Collapsed by default and capped: this sits above the connections list and the Sync
            // button in a non-scrolling column, so a long list would push them off screen.
            var showScheduled by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showScheduled = !showScheduled },
                enabled = scheduledPayments.isNotEmpty(),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "Upcoming payments received from bank: ${scheduledPayments.size}" +
                        if (scheduledPayments.isEmpty()) "" else if (showScheduled) " · Hide" else " · Show",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (showScheduled) {
                val sorted = scheduledPayments.sortedBy { it.date }
                sorted.take(MAX_SCHEDULED_SHOWN).forEach { payment ->
                    Text(
                        "${Formatters.date(payment.date)} · ${payment.note.ifBlank { "(no text)" }} · " +
                            "-${Formatters.amount(payment.amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (sorted.size > MAX_SCHEDULED_SHOWN) {
                    Text(
                        "+ ${sorted.size - MAX_SCHEDULED_SHOWN} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Sync stays in place above the accounts, which get the rest of the screen and scroll.
        if (state.isSyncing) {
            val progress = state.syncProgress
            when {
                progress != null && progress.total > 0 -> {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                    Text(
                        "${progress.done} of ${progress.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                // total == 0: still paginating through the bank's full history — this can be
                // the slowest, least visible part of a first sync, so show it's moving even
                // though there's no known denominator yet.
                progress != null && progress.total == 0 && progress.done > 0 -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    Text(
                        "Fetching from bank… ${progress.done} transaction(s) so far",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
        Button(onClick = { viewModel.syncNow() }, enabled = !state.isSyncing, modifier = Modifier.fillMaxWidth()) {
            if (state.isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sync now", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (state.isSyncing) {
            TextButton(onClick = { viewModel.cancelSync() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.connections, key = { it.bankId }) { connection ->
                BankConnectionSection(
                    connection = connection,
                    isStartingAuth = state.isStartingAuth,
                    onRefresh = { viewModel.startBankAuth(connection.bankId) },
                    onToggleAccount = { uid, checked -> viewModel.setAccountSelected(connection.bankId, uid, checked) },
                    onDisconnect = { disconnectTarget = connection }
                )
            }
            if (state.connectableBanks.isNotEmpty()) {
                item {
                    ConnectAnotherBankSection(
                        banks = state.connectableBanks,
                        selectedBankId = state.selectedBankId,
                        isStartingAuth = state.isStartingAuth,
                        onSelected = { id -> viewModel.selectBank(id) },
                        onConnect = { id -> viewModel.startBankAuth(id) }
                    )
                }
            }
        }

    }

    disconnectTarget?.let { connection ->
        AlertDialog(
            onDismissRequest = { disconnectTarget = null },
            title = { Text("Disconnect ${connection.bankDisplayName}?") },
            text = { Text("You'll need to log in again to reconnect. Already-synced transactions are kept, and any other connected bank keeps syncing.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnect(connection.bankId)
                    disconnectTarget = null
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { disconnectTarget = null }) { Text("Cancel") } }
        )
    }
}

/** One connected bank: its accounts-to-sync checklist, consent expiry, and its own
 * refresh/disconnect actions — independent of every other connected bank's section. */
@Composable
private fun BankConnectionSection(
    connection: BankConnectionUi,
    isStartingAuth: Boolean,
    onRefresh: () -> Unit,
    onToggleAccount: (uid: String, selected: Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    CompanyLogo(
                        domain = SupportedBanks.byId(connection.bankId).logoDomain,
                        contentDescription = null
                    ) {
                        Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(connection.bankDisplayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${connection.linkedAccounts.count { it.uid in connection.selectedAccountUids }} of " +
                                "${connection.linkedAccounts.size} synced",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefresh, enabled = !isStartingAuth) {
                        if (isStartingAuth) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
            connection.consentValidUntil?.let { validUntil ->
                Text(
                    "Access valid until ${formatBankDate(validUntil)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            connection.linkedAccounts.forEach { account ->
                BankAccountRow(
                    account = account,
                    selected = account.uid in connection.selectedAccountUids,
                    onToggle = { checked -> onToggleAccount(account.uid, checked) }
                )
            }
        }
    }
}

/** Picker + button for adding a bank not already connected — appears once, below every already-
 * connected bank's own section, and disappears entirely once every supported bank is connected. */
@Composable
private fun ConnectAnotherBankSection(
    banks: List<Bank>,
    selectedBankId: String,
    isStartingAuth: Boolean,
    onSelected: (String) -> Unit,
    onConnect: (String) -> Unit
) {
    val selectedBank = banks.firstOrNull { it.id == selectedBankId } ?: banks.first()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Connect another bank", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        BankDropdown(
            banks = banks,
            selected = selectedBank,
            onSelected = { bank -> onSelected(bank.id) },
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(onClick = { onConnect(selectedBank.id) }, enabled = !isStartingAuth) {
            if (isStartingAuth) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Text("Connect ${selectedBank.displayName}")
            }
        }
    }
}

@Composable
private fun BankAccountRow(account: LinkedBankAccount, selected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
            .clickable { onToggle(!selected) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            // Sydbank reuses the same product label (e.g. "Privatkonto") across more than
            // one account, so a suffix is included here too, not just in the underlying
            // local account name, or two accounts would look identical in this list.
            val suffix = account.iban?.takeLast(4) ?: account.uid.take(6)
            Text(
                "${account.product ?: account.name} ••$suffix",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${account.iban ?: account.uid} • ${account.currency}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatBankDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMillis))

@Composable
private fun CategoryAccordionRow(
    category: Category,
    isFixed: Boolean,
    onToggleFixed: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onChangeIcon: () -> Unit,
    showDivider: Boolean,
    shape: Shape
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardDefaults.cardColors().containerColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryIconTile(category.id, category.name, category.mainCategory, category.colorHex, size = 32.dp, onClick = onChangeIcon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp)
            ) {
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (category.type == TransactionType.INCOME) "Income" else "Expense",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (category.type == TransactionType.EXPENSE) {
                FilterChip(
                    selected = isFixed,
                    onClick = { onToggleFixed(!isFixed) },
                    label = { Text("Fixe") }
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete category")
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 64.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
