package com.financetracker.app.ui.screens.settings

import com.financetracker.app.util.advisor.AttentionChecks
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.ai.DeedReader
import com.financetracker.app.data.ai.LoanDocumentReader
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.Mortgage
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.advisor.FinanceAnalysis
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val monthYear = SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val dayMonthYear = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private const val HALF_YEAR = 182L * 24 * 60 * 60 * 1000

/**
 * Settings > Mortgage: the mortgage linked to its PBS payment. The debt goes down by itself with
 * each payment (estimated; the årsopgørelse corrects it), with this year's split into interest,
 * bidrag and afdrag, the debt-free date, an extra-payment slider, a rate-reset preview, friværdi
 * and the interest after rentefradrag. Loans themselves are added under Loans & goals.
 */
@Composable
fun MortgagePage(accountList: List<AccountUi>) {
    val accounts = accountList.map { it.account }
    val loans by LoansAndGoals.loans.collectAsState()
    val mortgages = loans.filter { it.kind == "mortgage" }
    if (mortgages.isEmpty()) {
        Text(
            "Add your mortgage under Loans & goals first. Reading the loan document fills in the terms for you.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    var pickedId by remember { mutableStateOf(MortgageNav.loanId ?: mortgages.first().id) }
    val loan = mortgages.firstOrNull { it.id == pickedId } ?: mortgages.first()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = (context.applicationContext as FinanceApp).repository
    val transactions by remember { repository.observeTransactions() }.collectAsState(initial = emptyList())
    val currency by CurrencySettings.currencyCode.collectAsState()
    val shared by SharedBillsSettings.setup.collectAsState()
    val palette = LocalAppPalette.current
    val now = remember { System.currentTimeMillis() }
    fun money(v: Double) = Formatters.currency(v, currency)

    val status = remember(loan, transactions) { Mortgage.status(loan, transactions, now) }
    val recurring = remember(transactions) { FinanceAnalysis.recurringPayments(transactions, now) }
    val payments = remember(loan, transactions) { Mortgage.payments(loan, transactions) }
    val household by HouseholdSync.state.collectAsState()
    // Paid from the shared bills account (on this phone, or shared from the partner's phone).
    val paidFromShared = (shared.accountId != null && (loan.paidFromAccountId == shared.accountId || payments.lastOrNull()?.accountId == shared.accountId)) ||
        loan.sharedBy.isNotBlank()
    // Your share of the bills: from the split on this phone, else as the partner's phone shares it.
    val myShare = remember(transactions, shared, household.partnerSharedBills) {
        if (shared.isSetUp) SharedBills.myShare(SharedBills.averageNetSalary(transactions, now), shared.partnerNetIncome)
        else household.partnerSharedBills?.snapshot?.let { 1 - it.publisherShare } ?: 0.5
    }
    val otherName = shared.partnerName.ifBlank { loan.sharedBy.ifBlank { "your partner" } }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Link the first payment by itself when it arrives.
    LaunchedEffect(loan, transactions) {
        Mortgage.findFirstPayment(loan, transactions, now)?.let { LoansAndGoals.saveLoan(loan.copy(paymentMerchant = it, debtAsOf = loan.debtAsOf ?: now)) }
    }
    val period = Mortgage.periodMonths(loan)
    val perTerm = if (period == 3) "quarter" else if (period == 1) "month" else "payment"
    val paidFromName = accounts.firstOrNull { it.id == loan.paidFromAccountId }?.name ?: payments.lastOrNull()?.accountName
    val next = Mortgage.nextPayment(loan, now)
    val nextDue = next?.first
    val nextAmount = next?.second ?: payments.lastOrNull()?.amount

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mortgages.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mortgages.forEach { m -> FilterChip(selected = m.id == loan.id, onClick = { pickedId = m.id }, label = { Text(m.name.ifBlank { "Mortgage" }) }) }
            }
        }

        // Short title; the full description behind Details; Edit opens the loan's details.
        var details by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(shortLender(loan.lender), shortType(loan.loanType)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { loan.name.ifBlank { "Mortgage" } },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide" else "Details") }
            OutlinedButton(onClick = { editing = true }) { Text("Edit") }
        }
        if (editing) {
            LoanDialog(
                loan = loan, accounts = accountList, isNew = false,
                onSave = { saveEditedLoan(loans, it); editing = false },
                onDelete = { LoansAndGoals.deleteLoan(loan.id); editing = false },
                onDismiss = { editing = false }
            )
        }
        if (loan.sharedBy.isNotBlank()) {
            Text(
                "Shared by ${loan.sharedBy}" + (loan.updatedAt?.let { " · updated ${Formatters.syncTimestamp(it)}" } ?: "") + " · you can edit it too",
                style = MaterialTheme.typography.bodySmall, color = palette.positive
            )
        }
        if (details) {
            Text(
                listOf(loan.name, loan.lender, loan.loanType, loan.summary).filter { it.isNotBlank() }.joinToString("\n\n"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Summary: debt left, next payment, debt-free.
        Card {
            Text("Debt left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(status.debt), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            status.paidOffFraction(loan.originalAmount)?.takeIf { it >= 0.005 }?.let { f ->
                Bar(f.toFloat())
                Text("${(f * 100).roundToInt()}% paid of ${money(loan.originalAmount!!)}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Next payment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(nextAmount?.let { "≈ " + money(it) } ?: "Not set", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(nextDue?.let { dayMonthYear.format(it) }, paidFromName).joinToString(" · ").ifBlank { "Set it under Loans & goals" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Debt-free", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status.monthsLeft?.let { monthYear.format(Mortgage.dateAfter(now, it)) } ?: "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (Mortgage.endDateOf(loan) != null) "from the loan" else "estimated",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (status.monthlyPayment > 0) {
                Text(
                    (if (period == 1) "Every month" else "Every $perTerm · ≈ ${money(status.monthlyPayment)} a month to set aside"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The PBS payment: links by itself; picking by hand is tucked away.
            var picking by remember { mutableStateOf(false) }
            if (loan.paymentMerchant.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Waiting for the first payment. It links by itself.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { picking = !picking }) { Text(if (picking) "Close" else "Pick manually") }
                }
                if (picking) {
                    val suggested = Mortgage.suggestPayment(loan, recurring)
                    val options = (listOfNotNull(suggested) + recurring.filter { it != suggested }).take(5)
                    if (options.isEmpty()) Text("No repeating payment found yet.", style = MaterialTheme.typography.bodySmall)
                    options.forEach { p ->
                        TextButton(
                            onClick = { LoansAndGoals.saveLoan(loan.copy(paymentMerchant = p.merchant, debtAsOf = loan.debtAsOf ?: now)); picking = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${title(p.merchant)} · ${money(p.lastAmount)} ${p.cadence.label}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull("Linked to ${title(loan.paymentMerchant)}", status.lastPaymentDate?.let { "last ${dayMonthYear.format(it)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { LoansAndGoals.saveLoan(loan.copy(paymentMerchant = "")) }) { Text("Unlink") }
                }
            }
        }

        // Your part of it, by the bills split.
        if (paidFromShared && nextAmount != null) {
            Card {
                Text("Your part", style = MaterialTheme.typography.titleSmall)
                LineRow("Of the next payment (${(myShare * 100).roundToInt()}%)", "≈ " + money(nextAmount * myShare), bold = true)
                if (status.monthlyPayment > 0) LineRow("Per month, in your transfer", "≈ " + money(status.monthlyPayment * myShare))
                Text(
                    "From the bills split by income. $otherName's part: ${((1 - myShare) * 100).roundToInt()}%." +
                        (loan.ownershipSharePct?.let { " You own ${it.roundToInt()}% of the home." } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // This year's payments.
        Card {
            Text(if (status.year.paid > 0) "Paid this year · ${money(status.year.paid)}" else "Paid this year", style = MaterialTheme.typography.titleSmall)
            if (status.year.paid > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Figure("Interest", money(status.year.interest), Modifier.weight(1f))
                    Figure("Admin fee (bidrag)", money(status.year.contribution), Modifier.weight(1f))
                    Figure("Repayment (afdrag)", money(status.year.repayment), Modifier.weight(1f))
                }
                Text("The repayment is the part that lowers your debt. Estimated from the rates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "Nothing yet. After the first payment you'll see the split into interest, administration fee (bidrag) and repayment (afdrag), the part that lowers the debt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Pay extra each month / quarter.
        val annuity = Mortgage.annuityMonthly(loan, status)
        if (annuity > 0 && status.monthsLeft != null) {
            var extra by remember(period) { mutableFloatStateOf(1000f * period) }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pay extra each $perTerm", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("+${money(extra.toDouble())}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = extra, onValueChange = { extra = (it / (250 * period)).roundToInt() * 250f * period }, valueRange = 0f..5000f * period)
                val effect = Mortgage.extraPayment(status.debt, Mortgage.interestRateOf(loan), annuity, extra.toDouble() / period, status.monthsLeft)
                if (effect != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure("Debt-free", monthYear.format(Mortgage.dateAfter(now, effect.monthsLeft)), Modifier.weight(1f))
                        Figure(
                            "Earlier by", if (effect.monthsEarlier == 0) "-" else "${"%.1f".format(Locale.US, effect.monthsEarlier / 12.0)} years",
                            Modifier.weight(1f)
                        )
                        Figure("Interest saved", money(effect.interestSaved), Modifier.weight(1f))
                    }
                }
                if (paidFromShared) {
                    Text(
                        "Paid from the shared bills account: your ${(myShare * 100).roundToInt()}% is +${money(extra * myShare)}, " +
                            "$otherName's is +${money(extra * (1 - myShare))}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Rate reset preview.
        if (loan.rateType.equals("variable", true) || loan.nextRateReset.isNotBlank()) {
            Card {
                val reset = AttentionChecks.parseDate(loan.nextRateReset)?.let { dayMonthYear.format(it) } ?: loan.nextRateReset
                Text("Rate reset" + (reset.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                val i = loan.interestRatePct ?: 0.0
                val c = loan.contributionRatePct ?: 0.0
                Text(
                    "Today: interest ${pct(i)}% + administration fee (bidrag) ${pct(c)}% = ${pct(i + c)}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val today = Mortgage.paymentAfterReset(loan, status, 0.0)
                if (today != null) {
                    RateRow("Rate", "Per $perTerm", "Per month", header = true)
                    listOf(0.0, 1.0, 2.0).forEach { delta ->
                        Mortgage.paymentAfterReset(loan, status, delta)?.let { monthly ->
                            RateRow(
                                if (delta == 0.0) "Today" else "+${delta.roundToInt()}%",
                                money(monthly * period), money(monthly),
                                color = if (delta > 0) palette.warning else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    if (paidFromShared) {
                        Text(
                            "Your share at +1%: +${money(((Mortgage.paymentAfterReset(loan, status, 1.0) ?: today) - today) * myShare)} a month.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HomeCard(loan, status, now, ::money)

        // Interest after the tax deduction.
        if (status.year.interest + status.year.contribution > 0) {
            Card {
                Text("Interest after tax · this year", style = MaterialTheme.typography.titleSmall)
                LineRow("Interest + administration fee paid", money(status.year.interest + status.year.contribution))
                LineRow("Tax deduction (rentefradrag), about 33%", "−" + money((status.year.interest + status.year.contribution) * Mortgage.TAX_DEDUCTION_RATE))
                LineRow("Real cost", "≈ " + money(Mortgage.afterTax(status.year)), bold = true)
                Text("An estimate. Your yearly tax statement (årsopgørelse) from Skat has the exact figure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Correct the debt with the årsopgørelse.
        val pickStatement = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                LoanDocumentReader.read(context, uris)
                    .onSuccess { read ->
                        val debt = read.remainingDebt
                        if (debt == null) message = "Couldn't find the remaining debt in that document."
                        else {
                            LoansAndGoals.saveLoan(loan.copy(remainingDebt = debt, debtAsOf = System.currentTimeMillis()))
                            message = "Debt corrected to ${money(debt)}."
                        }
                    }
                    .onFailure { message = it.message ?: "Couldn't read the document." }
                busy = false
            }
        }
        if (LoanDocumentReader.isAvailable) {
            TextButton(onClick = { pickStatement.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Reading…" else "Correct the debt with your yearly tax statement (årsopgørelse)")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun pct(v: Double) = "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')

/** "Totalkredit A/S (arranged through SJF Bank A/S)" → "Totalkredit". */
private fun shortLender(lender: String) = lender.substringBefore(" (").removeSuffix(" A/S").trim()

/** "Adjustable-rate F5, 30-year … (Tilpasningslån F5, …)" → "Adjustable-rate F5". */
private fun shortType(type: String) = type.substringBefore(",").substringBefore(" (").trim()

@Composable
private fun RateRow(label: String, perTerm: String, perMonth: String, header: Boolean = false, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    val style = MaterialTheme.typography.bodyMedium
    val c = if (header) MaterialTheme.colorScheme.onSurfaceVariant else color
    Row {
        Text(label, style = style, color = c, modifier = Modifier.weight(1f))
        Text(perTerm, style = style, color = c, fontWeight = if (header) null else FontWeight.SemiBold, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
        Text(perMonth, style = style, color = c, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
    }
}

/** Which mortgage to open, when Loans & goals sends you here. */
object MortgageNav {
    var loanId by mutableStateOf<String?>(null)
}

/** Friværdi from the home value the person typed in, and their ownership share if set. */
@Composable
private fun HomeCard(loan: Loan, status: com.financetracker.app.util.MortgageStatus, now: Long, money: (Double) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingValue by remember { mutableStateOf(false) }
    var editingShare by remember { mutableStateOf(false) }
    var deedShares by remember { mutableStateOf<List<Double>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val pickDeed = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        note = "Reading the title deed…"
        scope.launch {
            DeedReader.read(context, uris)
                .onSuccess { shares ->
                    note = null
                    if (shares.size == 1 || shares.distinct().size == 1) LoansAndGoals.saveLoan(loan.copy(ownershipSharePct = shares.first()))
                    else deedShares = shares
                }
                .onFailure { note = it.message ?: "Couldn't read the title deed." }
        }
    }

    Card {
        Text("Home equity (friværdi)", style = MaterialTheme.typography.titleSmall)
        val equity = Mortgage.equity(loan, status)
        if (equity == null) {
            Text("What you own: the home's value minus the debt. Add the value to see it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(money(equity), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LocalAppPalette.current.positive)
            loan.homeValue?.takeIf { it > 0 }?.let { value -> Bar((equity / value).coerceIn(0.0, 1.0).toFloat()) }
            loan.ownershipSharePct?.let { pct ->
                Text("Your share (${pct.roundToInt()}%): ${money(equity * pct / 100)}", style = MaterialTheme.typography.bodyMedium)
            }
            val stale = loan.homeValueUpdatedAt?.let { now - it > HALF_YEAR } ?: true
            Text(
                "Home value ${money(loan.homeValue!!)}" + (loan.homeValueUpdatedAt?.let { " · updated ${dayMonthYear.format(it)}" } ?: "") +
                    if (stale) " · time to update" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Home value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { editingValue = true }) { Text(if (loan.homeValue == null) "Add" else "Update") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your share" + (loan.ownershipSharePct?.let { ": ${it.roundToInt()}%" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text("from the title deed (skøde)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (DeedReader.isAvailable) TextButton(onClick = { pickDeed.launch(arrayOf("application/pdf", "image/*")) }) { Text("Read") }
            OutlinedButton(onClick = { editingShare = true }) { Text(if (loan.ownershipSharePct == null) "Set" else "Change") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (editingValue) NumberDialog("Home value", loan.homeValue, onDismiss = { editingValue = false }) {
        LoansAndGoals.saveLoan(loan.copy(homeValue = it, homeValueUpdatedAt = System.currentTimeMillis()))
        editingValue = false
    }
    if (editingShare) NumberDialog("Your ownership share in %", loan.ownershipSharePct, onDismiss = { editingShare = false }) {
        LoansAndGoals.saveLoan(loan.copy(ownershipSharePct = it.coerceIn(0.0, 100.0)))
        editingShare = false
    }
    deedShares?.let { shares ->
        AlertDialog(
            onDismissRequest = { deedShares = null },
            title = { Text("Which share is yours?") },
            text = {
                Column {
                    shares.forEachIndexed { i, pct ->
                        TextButton(onClick = { LoansAndGoals.saveLoan(loan.copy(ownershipSharePct = pct)); deedShares = null }) {
                            Text("Owner ${i + 1}: ${pct.roundToInt()}%")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deedShares = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NumberDialog(label: String, initial: Double?, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial?.roundToInt()?.toString() ?: "") }
    val value = text.replace(".", "").replace(",", "").trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null && value > 0) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LineRow(left: String, right: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
        Text(right, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun title(merchant: String) = merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
