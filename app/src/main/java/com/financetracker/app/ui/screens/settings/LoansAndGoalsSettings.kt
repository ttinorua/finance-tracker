package com.financetracker.app.ui.screens.settings

import com.financetracker.app.util.Mortgage
import com.financetracker.app.data.prefs.SharedBillsSettings
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Delete
import com.financetracker.app.data.prefs.WeeklySummarySettings
import com.financetracker.app.data.prefs.UserAlerts
import com.financetracker.app.data.prefs.AlertKind
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Switch
import androidx.core.content.ContextCompat
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.util.advisor.GoalTracking
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.LoanDocumentReader
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.SavingsGoal
import com.financetracker.app.util.Formatters
import kotlinx.coroutines.launch

/** Settings > General > My loans & goals: what the AI advisor knows about the user's loans and
 * savings goals. A loan can be typed in or read from its (Danish) document by the AI. */
@Composable
fun LoansAndGoalsSettings(accounts: List<AccountUi>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loans by LoansAndGoals.loans.collectAsState()
    val goals by LoansAndGoals.goals.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    var editingLoan by remember { mutableStateOf<Loan?>(null) }
    var editingGoal by remember { mutableStateOf<SavingsGoal?>(null) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        reading = true
        error = null
        scope.launch {
            LoanDocumentReader.read(context, uris)
                .onSuccess { editingLoan = it }
                .onFailure { error = it.message ?: "Couldn't read the document." }
            reading = false
        }
    }

    Column(modifier = modifier) {
        Text("My loans & goals", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your loans, mortgages and savings goals, so the AI advisor can give advice that fits them. " +
                "Read a loan document (PDF or photos of the pages, Danish is fine) and the AI fills in the terms " +
                "in English for you to check. Only the terms are kept — not the document or personal details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
        )
        loans.forEach { loan ->
            ItemRow(
                title = loan.name.ifBlank { "Loan" },
                detail = listOfNotNull(
                    loan.remainingDebt?.let { "Debt ${Formatters.currency(it, currency)}" },
                    loan.interestRatePct?.let { "$it%" + (loan.contributionRatePct?.let { c -> " + $c%" } ?: "") },
                    loan.monthlyPayment?.let { "${Formatters.currency(it, currency)}/month" }
                ).joinToString(" · ").ifBlank { loan.lender },
                onClick = { editingLoan = loan }
            )
        }
        goals.forEach { goal ->
            val linked = goal.accountId?.let { id -> accounts.firstOrNull { it.account.id == id } }
            val saved = linked?.balance ?: goal.savedAmount
            val target = goal.targetAmount
            val status = if (target != null && target > 0 && saved != null) {
                GoalTracking.status(
                    target, saved, GoalTracking.parseTargetDate(goal.targetDate), goal.startedAt, goal.startAmount,
                    goal.monthlySaving, System.currentTimeMillis()
                )
            } else {
                null
            }
            ItemRow(
                title = goal.name.ifBlank { "Goal" },
                detail = listOfNotNull(
                    saved?.let { Formatters.currency(it, currency) + (target?.let { t -> " of ${Formatters.currency(t, currency)}" } ?: "") },
                    goal.targetDate.takeIf { it.isNotBlank() }?.let { "by $it" },
                    linked?.let { "linked to ${it.account.name}" },
                    when {
                        status == null -> null
                        status.reached -> "reached"
                        status.behind -> "behind by ${Formatters.currency(status.behindBy, currency)}"
                        status.monthsLeft != null -> "on track"
                        else -> null
                    }
                ).joinToString(" · "),
                onClick = { editingGoal = goal }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { editingLoan = Loan() }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Loan", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(onClick = { editingGoal = SavingsGoal() }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Goal", modifier = Modifier.padding(start = 4.dp))
            }
        }
        OutlinedButton(
            onClick = { pickDocument.launch(arrayOf("application/pdf", "image/*")) },
            enabled = !reading,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            if (reading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Reading the document…", modifier = Modifier.padding(start = 8.dp))
            } else {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Read loan document", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (!LoanDocumentReader.isAvailable) {
            Text(
                "Reading documents needs Gemini or Claude (AI assistant above).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    editingLoan?.let { loan ->
        LoanDialog(
            loan = loan,
            accounts = accounts,
            isNew = loans.none { it.id == loan.id },
            onSave = {
                // A newly typed debt is the new exact figure the mortgage estimate starts from.
                val previous = loans.firstOrNull { l -> l.id == it.id }
                val debtChanged = previous == null || previous.remainingDebt != it.remainingDebt
                LoansAndGoals.saveLoan(if (debtChanged && it.remainingDebt != null) it.copy(debtAsOf = System.currentTimeMillis()) else it)
                editingLoan = null
            },
            onDelete = {
                LoansAndGoals.deleteLoan(loan.id)
                editingLoan = null
            },
            onDismiss = { editingLoan = null }
        )
    }
    editingGoal?.let { goal ->
        GoalDialog(
            goal = goal,
            accounts = accounts,
            isNew = goals.none { it.id == goal.id },
            onSave = {
                LoansAndGoals.saveGoal(it, it.accountId?.let { id -> accounts.firstOrNull { a -> a.account.id == id }?.balance } ?: it.savedAmount)
                editingGoal = null
            },
            onDelete = {
                LoansAndGoals.deleteGoal(goal.id)
                editingGoal = null
            },
            onDismiss = { editingGoal = null }
        )
    }
}

@Composable
private fun ItemRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val LOAN_KINDS = listOf("mortgage" to "Mortgage", "bank_loan" to "Bank loan", "car_loan" to "Car loan", "student_loan" to "Student loan", "other" to "Other")

@Composable
private fun LoanDialog(loan: Loan, accounts: List<AccountUi>, isNew: Boolean, onSave: (Loan) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    // A new mortgage is paid from the shared bills account, when there is one.
    var draft by remember(loan.id) {
        mutableStateOf(
            if (isNew && loan.kind == "mortgage" && loan.paidFromAccountId == null) loan.copy(paidFromAccountId = SharedBillsSettings.setup.value.accountId)
            else loan
        )
    }
    val currency by CurrencySettings.currencyCode.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Loan" else "Edit loan") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isNew && loan.summary.isNotBlank()) {
                    Text(
                        "Read from your document — check the figures before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Field("Name", draft.name) { draft = draft.copy(name = it) }
                Field("Lender", draft.lender) { draft = draft.copy(lender = it) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LOAN_KINDS.take(3).forEach { (key, label) ->
                        FilterChip(selected = draft.kind == key, onClick = { draft = draft.copy(kind = key) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LOAN_KINDS.drop(3).forEach { (key, label) ->
                        FilterChip(selected = draft.kind == key, onClick = { draft = draft.copy(kind = key) }, label = { Text(label) })
                    }
                }
                Field("Loan type", draft.loanType) { draft = draft.copy(loanType = it) }
                NumberField("Remaining debt", draft.remainingDebt) { draft = draft.copy(remainingDebt = it) }
                NumberField("Original amount", draft.originalAmount) { draft = draft.copy(originalAmount = it) }
                NumberField("Interest rate (% a year)", draft.interestRatePct) { draft = draft.copy(interestRatePct = it) }
                NumberField("Administration fee rate (bidragssats, %)", draft.contributionRatePct) { draft = draft.copy(contributionRatePct = it) }
                Text("How often is it paid?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(12 to "Monthly", 4 to "Quarterly", 2 to "Half-year", 1 to "Yearly").forEach { (n, label) ->
                        FilterChip(
                            selected = (draft.paymentsPerYear ?: 12) == n,
                            onClick = { draft = draft.copy(paymentsPerYear = n) },
                            label = { Text(label) }
                        )
                    }
                }
                if ((draft.paymentsPerYear ?: 12) == 12) {
                    NumberField("Monthly payment", draft.monthlyPayment) { draft = draft.copy(monthlyPayment = it) }
                } else {
                    NumberField("Amount per payment (ydelse per termin)", draft.paymentAmount) { draft = draft.copy(paymentAmount = it) }
                    draft.paymentAmount?.takeIf { it > 0 }?.let { amount ->
                        Text(
                            "${Formatters.currency(amount / Mortgage.periodMonths(draft), currency)} a month to set aside",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Field("First payment (YYYY-MM-DD)", draft.firstPaymentDate) { draft = draft.copy(firstPaymentDate = it) }
                Text("Paid from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(selected = draft.paidFromAccountId == null, onClick = { draft = draft.copy(paidFromAccountId = null) }, label = { Text("Not set") })
                    accounts.forEach { account ->
                        FilterChip(
                            selected = draft.paidFromAccountId == account.account.id,
                            onClick = { draft = draft.copy(paidFromAccountId = account.account.id) },
                            label = { Text(account.account.name) }
                        )
                    }
                }
                NumberField("Years left", draft.yearsLeft) { draft = draft.copy(yearsLeft = it) }
                Field("Rate type (fixed / variable)", draft.rateType) { draft = draft.copy(rateType = it) }
                Field("Next rate reset", draft.nextRateReset) { draft = draft.copy(nextRateReset = it) }
                Field("Interest-only until", draft.interestOnlyUntil) { draft = draft.copy(interestOnlyUntil = it) }
                Field("Ends", draft.endDate) { draft = draft.copy(endDate = it) }
                Field("Early repayment terms", draft.earlyRepayment, singleLine = false) { draft = draft.copy(earlyRepayment = it) }
                Field("Other terms", draft.otherTerms, singleLine = false) { draft = draft.copy(otherTerms = it) }
                if (draft.summary.isNotBlank()) {
                    Text("Document summary", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    Text(draft.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete loan", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(name = draft.name.trim().ifBlank { "Loan" })) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GoalDialog(
    goal: SavingsGoal,
    accounts: List<AccountUi>,
    isNew: Boolean,
    onSave: (SavingsGoal) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(goal.id) { mutableStateOf(goal) }
    val currency by CurrencySettings.currencyCode.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Savings goal" else "Edit goal") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Field("Name, e.g. Emergency fund", draft.name) { draft = draft.copy(name = it) }
                NumberField("Target amount", draft.targetAmount) { draft = draft.copy(targetAmount = it) }
                Text("Saved in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(selected = draft.accountId == null, onClick = { draft = draft.copy(accountId = null) }, label = { Text("I'll enter it") })
                    accounts.forEach { account ->
                        FilterChip(
                            selected = draft.accountId == account.account.id,
                            onClick = { draft = draft.copy(accountId = account.account.id) },
                            label = { Text(account.account.name) }
                        )
                    }
                }
                val linked = draft.accountId?.let { id -> accounts.firstOrNull { it.account.id == id } }
                if (linked != null) {
                    Text(
                        "Progress follows this account's balance (now ${Formatters.currency(linked.balance, currency)}) and updates with every bank sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    NumberField("Saved so far", draft.savedAmount) { draft = draft.copy(savedAmount = it) }
                }
                NumberField("Planned saving per month", draft.monthlySaving) { draft = draft.copy(monthlySaving = it) }
                Field("Target date (YYYY-MM-DD)", draft.targetDate) { draft = draft.copy(targetDate = it) }
                Text(
                    "With a target date, the app checks daily whether you're on pace and tells you if you fall behind.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete goal", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(name = draft.name.trim().ifBlank { "Goal" })) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Settings > General > Needs your attention: phone notifications on or off. */
@Composable
fun AttentionSettings(modifier: Modifier = Modifier) {
    val enabled by AttentionMonitor.notificationsEnabled.collectAsState()
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(modifier = modifier) {
        Text("Needs your attention", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every day and after each bank sync, the app checks your budgets, subscriptions and price rises, unusual " +
                "payments, balances, goals, loans, bank connection and backups. Anything worth a look shows on the " +
                "Dashboard, plus a monthly review at the start of each month. Phone notifications tell you when " +
                "something new comes up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    AttentionMonitor.setNotificationsEnabled(on)
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
            Text(if (enabled) "Phone notifications on" else "Phone notifications off", modifier = Modifier.padding(start = 8.dp))
        }
        YourAlerts()
    }
}

/** The alerts the person set up (usually by asking the AI advisor), and the weekly AI summary. */
@Composable
private fun YourAlerts() {
    val alerts by UserAlerts.alerts.collectAsState()
    val weekly by WeeklySummarySettings.enabled.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val money = { value: Double -> Formatters.currency(value, currency) }

    Text("Your alerts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
    Text(
        if (alerts.isEmpty()) {
            "None yet. Ask the AI advisor, e.g. \"Tell me if takeaway goes over 1,000 kr this month\" or " +
                "\"Warn me about any payment over 3,000 kr\". The app checks them itself, so they don't use the AI."
        } else {
            "Checked by the app after each bank sync and once a day. They don't use the AI."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    alerts.forEach { alert ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(UserAlerts.describe(alert, money), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        if (alert.kind == AlertKind.CATEGORY_LIMIT) "Monthly" else "Each payment",
                        alert.warnAtPercent?.takeIf { alert.kind == AlertKind.CATEGORY_LIMIT }?.let { "warns at $it%" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = alert.enabled, onCheckedChange = { UserAlerts.setEnabled(alert.id, it) })
            IconButton(onClick = { UserAlerts.remove(alert.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete alert: ${UserAlerts.describe(alert, money)}")
            }
        }
    }

    Text("Weekly AI summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "A short summary of your week every Sunday at 18:00, on Home and as a notification. One short AI request a week.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = weekly, onCheckedChange = WeeklySummarySettings::setEnabled)
    }
}

@Composable
private fun Field(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A number field that accepts both 1234.5 and Danish 1.234,5. */
@Composable
private fun NumberField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { formatNumber(it) }.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(parseNumber(it))
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.isNotBlank() && parseNumber(text) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

internal fun parseNumber(raw: String): Double? {
    val text = raw.trim().replace(" ", "")
    if (text.isEmpty()) return null
    val normalized = when {
        // 1.234.567,89 or 1234,5 (Danish)
        text.contains(',') && (text.lastIndexOf(',') > text.lastIndexOf('.')) -> text.replace(".", "").replace(',', '.')
        // 1,234,567.89
        text.contains(',') -> text.replace(",", "")
        // 1.234.567 (Danish thousands without decimals)
        text.count { it == '.' } > 1 -> text.replace(".", "")
        else -> text
    }
    return normalized.toDoubleOrNull()
}
