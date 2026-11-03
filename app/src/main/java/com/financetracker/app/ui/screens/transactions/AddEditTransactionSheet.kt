package com.financetracker.app.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.CategorySuggester
import com.financetracker.app.data.ai.AiService
import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.CategoryPickerField
import com.financetracker.app.ui.components.CompanyLogo
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.todayUtcMidnight
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionSheet(
    existing: TransactionWithDetails?,
    accounts: List<Account>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (amount: Double, type: TransactionType, accountId: Long, categoryId: Long?, date: Long, note: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currencyCode by CurrencySettings.currencyCode.collectAsState()

    var type by remember { mutableStateOf(existing?.type ?: TransactionType.EXPENSE) }
    var amountText by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var selectedAccountId by remember { mutableStateOf(existing?.accountId ?: accounts.firstOrNull()?.id) }
    var selectedCategoryId by remember { mutableStateOf(existing?.categoryId) }
    var dateMillis by remember { mutableStateOf(existing?.date ?: todayUtcMidnight()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf<String?>(null) }
    var isSuggesting by remember { mutableStateOf(false) }
    var suggestError by remember { mutableStateOf<String?>(null) }
    var showMerchantStyle by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val categoriesForType = categories.filter { it.type == type }

    fun requestAiSuggestion() {
        if (isSuggesting || note.isBlank()) return
        isSuggesting = true
        suggestError = null
        coroutineScope.launch {
            CategorySuggester.suggest(note, categories)
                .onSuccess { match ->
                    if (match != null) {
                        type = match.type
                        selectedCategoryId = match.id
                    } else {
                        suggestError = "Couldn't match a category. Try picking one manually."
                    }
                }
                .onFailure { suggestError = "AI suggestion failed: ${it.message}" }
            isSuggesting = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (existing == null) "Add Transaction" else "Edit Transaction",
                style = MaterialTheme.typography.titleLarge
            )

            // How this company shows in the list (logo and short name), with a way to change it.
            if (existing != null && existing.note.isNotBlank()) {
                val cachedDomains by MerchantLogoLookup.domains.collectAsState()
                val cachedNames by MerchantLogoLookup.names.collectAsState()
                val overrides by MerchantOverrides.rules.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompanyLogo(
                        domain = MerchantLogoLookup.domainFor(existing.note, cachedDomains, overrides),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        photo = MerchantOverrides.photoFor(existing.note, overrides)
                    ) {
                        CategoryIconTile(
                            categoryId = existing.categoryId,
                            name = existing.categoryName,
                            mainCategory = existing.mainCategoryName,
                            colorHex = existing.categoryColorHex,
                            size = 36.dp
                        )
                    }
                    Text(
                        MerchantLogoLookup.nameFor(existing.note, cachedNames, overrides) ?: existing.note,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    )
                    TextButton(onClick = { showMerchantStyle = true }) { Text("Change logo & name") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == TransactionType.EXPENSE,
                    onClick = {
                        type = TransactionType.EXPENSE
                        selectedCategoryId = null
                    },
                    label = { Text("Expense") }
                )
                FilterChip(
                    selected = type == TransactionType.INCOME,
                    onClick = {
                        type = TransactionType.INCOME
                        selectedCategoryId = null
                    },
                    label = { Text("Income") }
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    amountError = null
                },
                label = { Text("Amount") },
                leadingIcon = { Text(Formatters.currencySymbol(currencyCode)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = amountError != null,
                supportingText = amountError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )

            AccountDropdown(
                accounts = accounts,
                selectedId = selectedAccountId,
                onSelected = { selectedAccountId = it }
            )

            CategoryPickerField(
                categories = categoriesForType,
                selectedId = selectedCategoryId,
                onSelected = { selectedCategoryId = it }
            )

            OutlinedTextField(
                value = Formatters.date(dateMillis),
                onValueChange = {},
                readOnly = true,
                label = { Text("Date") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { showDatePicker = true }) { Text("Change") }
                }
            )

            OutlinedTextField(
                value = note,
                onValueChange = { newNote ->
                    note = newNote
                    suggestError = null
                    // Only while adding a brand-new transaction — editing an existing one
                    // already has its own category, and shouldn't change out from under the
                    // user just because they touched the note field.
                    if (existing == null) {
                        LearnedCategoryRules.suggest(newNote, categories)?.let { match ->
                            type = match.type
                            selectedCategoryId = match.id
                        }
                    }
                },
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            if (AiService.isConfigured && note.isNotBlank()) {
                OutlinedButton(
                    onClick = { requestAiSuggestion() },
                    enabled = !isSuggesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSuggesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = if (isSuggesting) "Thinking..." else "Suggest category with AI",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (suggestError != null) {
                    Text(
                        text = suggestError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    val amount = amountText.replace(",", "").toDoubleOrNull()
                    val accountId = selectedAccountId
                    when {
                        amount == null || amount <= 0.0 -> amountError = "Enter a valid amount"
                        accountId == null -> amountError = "Add an account first"
                        else -> onSave(amount, type, accountId, selectedCategoryId, dateMillis, note.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existing == null) "Add" else "Save")
            }
        }
    }

    if (showMerchantStyle && existing != null) {
        MerchantStyleDialog(note = existing.note, onDismiss = { showMerchantStyle = false })
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { dateMillis = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(accounts: List<Account>, selectedId: Long?, onSelected: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = accounts.firstOrNull { it.id == selectedId }?.name ?: "Select account"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelected(account.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
