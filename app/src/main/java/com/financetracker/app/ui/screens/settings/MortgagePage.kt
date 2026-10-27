package com.financetracker.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.ai.DeedReader
import com.financetracker.app.data.ai.LoanDocumentReader
import com.financetracker.app.data.db.entity.Account
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
fun MortgagePage(accounts: List<Account>) {
    val loans by LoansAndGoals.loans.collectAsState()
    val mortgages = loans.filter { it.kind == "mortgage" }
    if (mortgages.isEmpty()) {
        Text(
            "Add your mortgage under Loans & goals first. Reading the loan document fills in the terms for you.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    var pickedId by remember { mutableStateOf(mortgages.first().id) }
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
    val paidFromShared = shared.accountId != null && payments.lastOrNull()?.accountId == shared.accountId
    val myShare = remember(transactions, shared) {
        SharedBills.myShare(SharedBills.averageNetSalary(transactions, now), shared.partnerNetIncome)
    }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mortgages.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mortgages.forEach { m -> FilterChip(selected = m.id == loan.id, onClick = { pickedId = m.id }, label = { Text(m.name.ifBlank { "Mortgage" }) }) }
            }
        }
        Text(
            listOf(loan.name.ifBlank { "Mortgage" }, loan.lender, loan.loanType).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium
        )

        // Debt left and debt-free date.
        Card {
            Text("Debt left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(status.debt), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            status.paidOffFraction(loan.originalAmount)?.let { f ->
                Bar(f.toFloat())
                Text("${(f * 100).roundToInt()}% paid of ${money(loan.originalAmount!!)}", style = MaterialTheme.typography.bodySmall)
            }
            status.monthsLeft?.let { Text("Debt-free ${monthYear.format(Mortgage.dateAfter(now, it))}", fontWeight = FontWeight.SemiBold) }
            Text(
                if (loan.paymentMerchant.isBlank()) "Link the payment below so the debt goes down by itself."
                else "Estimated after each payment since ${loan.debtAsOf?.let { dayMonthYear.format(it) } ?: "linking"}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The PBS payment.
        Card {
            if (loan.paymentMerchant.isBlank()) {
                Text("Link the PBS payment", style = MaterialTheme.typography.titleSmall)
                val suggested = Mortgage.suggestPayment(loan, recurring)
                val options = (listOfNotNull(suggested) + recurring.filter { it != suggested }).take(5)
                if (options.isEmpty()) Text("No repeating payment found yet.", style = MaterialTheme.typography.bodySmall)
                options.forEach { p ->
                    OutlinedButton(
                        onClick = { LoansAndGoals.saveLoan(loan.copy(paymentMerchant = p.merchant, debtAsOf = loan.debtAsOf ?: now)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${title(p.merchant)} · ${money(p.lastAmount)} ${p.cadence.label}" + if (p == suggested) " (suggested)" else "")
                    }
                }
            } else {
                Text("Linked to ${title(loan.paymentMerchant)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        payments.lastOrNull()?.accountName?.let { "From $it" },
                        "${money(status.monthlyPayment)}/month",
                        status.lastPaymentDate?.let { "last ${dayMonthYear.format(it)}" },
                        if (paidFromShared) "shared bills" else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { LoansAndGoals.saveLoan(loan.copy(paymentMerchant = "")) }) { Text("Unlink") }
            }
        }

        // This year's payments.
        if (status.year.paid > 0) {
            Card {
                Text("Paid this year · ${money(status.year.paid)}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Figure("Interest", money(status.year.interest), Modifier.weight(1f))
                    Figure("Bidrag", money(status.year.contribution), Modifier.weight(1f))
                    Figure("Afdrag", money(status.year.repayment), Modifier.weight(1f))
                }
                Text("Afdrag is the part that lowers your debt. Estimated from the rates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Debt over time, from now to debt-free.
        status.monthsLeft?.takeIf { it > 0 }?.let { months ->
            Card {
                Text("Debt over time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DebtChart(status.debt, Mortgage.yearlyRateOf(loan), status.monthlyPayment, months)
                Row {
                    Text(monthYear.format(now), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(monthYear.format(Mortgage.dateAfter(now, months)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Correct with the årsopgørelse.
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
            OutlinedButton(onClick = { pickStatement.launch(arrayOf("application/pdf", "image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Reading…" else "Correct with årsopgørelse")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Pay extra each month.
        if (status.monthlyPayment > 0 && status.monthsLeft != null) {
            var extra by remember { mutableFloatStateOf(1000f) }
            Card {
                Text("Pay extra each month", style = MaterialTheme.typography.titleSmall)
                Text("+${money(extra.toDouble())}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Slider(value = extra, onValueChange = { extra = (it / 250).roundToInt() * 250f }, valueRange = 0f..5000f)
                val effect = Mortgage.extraPayment(status.debt, Mortgage.yearlyRateOf(loan), status.monthlyPayment, extra.toDouble())
                if (effect != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure("Debt-free", monthYear.format(Mortgage.dateAfter(now, effect.monthsLeft)), Modifier.weight(1f))
                        Figure(
                            "Earlier", if (effect.monthsEarlier == 0) "-" else "${"%.1f".format(Locale.US, effect.monthsEarlier / 12.0)} years",
                            Modifier.weight(1f)
                        )
                        Figure("Interest saved", money(effect.interestSaved), Modifier.weight(1f))
                    }
                }
                if (paidFromShared) {
                    Text(
                        "Paid from the shared bills account: your ${(myShare * 100).roundToInt()}% is +${money(extra * myShare)}, " +
                            "${shared.partnerName.ifBlank { "your partner" }}'s is +${money(extra * (1 - myShare))}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Rate reset preview.
        if (loan.rateType.equals("variable", true) || loan.nextRateReset.isNotBlank()) {
            Card {
                Text("Rate reset" + (loan.nextRateReset.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text("If rates go up, your monthly payment becomes:", style = MaterialTheme.typography.bodySmall)
                val ratePct = (loan.interestRatePct ?: 0.0) + (loan.contributionRatePct ?: 0.0)
                listOf(0.0, 1.0, 2.0).forEach { delta ->
                    val payment = if (delta == 0.0) status.monthlyPayment else Mortgage.paymentAfterReset(loan, status, delta)
                    if (payment != null) {
                        val diff = payment - status.monthlyPayment
                        val share = if (paidFromShared) " · your share ${if (delta == 0.0) money(payment * myShare) else "+" + money(diff * myShare)}" else ""
                        Text(
                            (if (delta == 0.0) "Today ${"%.2f".format(Locale.US, ratePct)}%" else "+${delta.roundToInt()}% → ${"%.2f".format(Locale.US, ratePct + delta)}%") +
                                ": ${money(payment)}" + (if (delta > 0) " (+${money(diff)})" else "") + share,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (delta > 0) palette.warning else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        HomeCard(loan, status, now, ::money)

        // Interest after rentefradrag.
        if (status.year.interest + status.year.contribution > 0) {
            Card {
                Text("Interest after tax · this year", style = MaterialTheme.typography.titleSmall)
                LineRow("Interest + bidrag paid", money(status.year.interest + status.year.contribution))
                LineRow("Rentefradrag, about 33%", "−" + money((status.year.interest + status.year.contribution) * Mortgage.TAX_DEDUCTION_RATE))
                LineRow("Real cost", "≈ " + money(Mortgage.afterTax(status.year)), bold = true)
                Text("An estimate. Your årsopgørelse from Skat has the exact figure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
        note = "Reading the skøde…"
        scope.launch {
            DeedReader.read(context, uris)
                .onSuccess { shares ->
                    note = null
                    if (shares.size == 1 || shares.distinct().size == 1) LoansAndGoals.saveLoan(loan.copy(ownershipSharePct = shares.first()))
                    else deedShares = shares
                }
                .onFailure { note = it.message ?: "Couldn't read the skøde." }
        }
    }

    Card {
        Text("Friværdi", style = MaterialTheme.typography.titleSmall)
        val equity = Mortgage.equity(loan, status)
        if (equity == null) {
            Text("Type in your home's value to see what you own versus what you owe.", style = MaterialTheme.typography.bodySmall)
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
        TextButton(onClick = { editingValue = true }) { Text(if (loan.homeValue == null) "Add home value" else "Update home value") }
        if (loan.ownershipSharePct == null) {
            Text("Your ownership share: not set. It's in the skøde (tinglysning.dk). Until it's set, the whole friværdi is shown.", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (DeedReader.isAvailable) OutlinedButton(onClick = { pickDeed.launch(arrayOf("application/pdf", "image/*")) }) { Text("Read from skøde") }
            OutlinedButton(onClick = { editingShare = true }) { Text(if (loan.ownershipSharePct == null) "Enter %" else "Change %") }
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
private fun DebtChart(debt: Double, yearlyRate: Double, monthly: Double, months: Int) {
    val color = MaterialTheme.colorScheme.primary
    val points = remember(debt, yearlyRate, monthly, months) {
        var d = debt
        val r = yearlyRate / 12
        (0..months).map { m -> if (m > 0) d = (d + d * r - monthly).coerceAtLeast(0.0); d }
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / points.size.coerceAtLeast(2).minus(1)
            val y = size.height * (1 - (v / debt).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        drawCircle(color, 5.dp.toPx(), Offset(0f, 0f))
    }
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
