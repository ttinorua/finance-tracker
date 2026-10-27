package com.financetracker.app.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.Contributor
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.SharedBillsStatus
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val dayFormat = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val monthFormat = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun title(merchant: String) = merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/**
 * Settings > Shared bills: the joint bills account split by net income. Set up once (account,
 * partner's name and net income), then shows this month's targets and who has paid in, asks
 * "Who sent this?" for deposits it can't place, lists the bills, the year so far, warnings for
 * big bills, and a summary to send the partner (who doesn't use the app).
 */
@Composable
fun SharedBillsPage(accounts: List<Account>) {
    val setup by SharedBillsSettings.setup.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (!setup.isSetUp || editing) {
        SharedBillsSetupForm(accounts, onDone = { editing = false })
        return
    }
    val repository = (LocalContext.current.applicationContext as FinanceApp).repository
    val transactions by remember { repository.observeTransactions() }.collectAsState(initial = emptyList())
    val currency by CurrencySettings.currencyCode.collectAsState()
    val account = accounts.firstOrNull { it.id == setup.accountId }
    val balance by produceState(0.0, account, transactions) { value = account?.let { repository.getAccountBalance(it) } ?: 0.0 }
    val now = remember { System.currentTimeMillis() }
    val myIncome = remember(transactions) { SharedBills.averageNetSalary(transactions, now) }
    val status = remember(transactions, setup, balance) {
        SharedBills.status(setup.accountId!!, transactions, balance, myIncome, setup.partnerNetIncome, setup.partnerName, setup.answers, now)
    }
    val partner = setup.partnerName.ifBlank { "Partner" }
    fun money(v: Double) = Formatters.currency(v, currency)
    val palette = LocalAppPalette.current
    val me = MaterialTheme.colorScheme.primary
    val them = Color(0xFFE08A3C)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account?.name ?: "Bills account", style = MaterialTheme.typography.titleMedium)
                Text("Split by net income · balance ${money(balance)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = true }) { Text("Edit") }
        }

        // Shares and this month's targets.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${monthFormat.format(now)}: paid in ${money(status.myPaidThisMonth + status.partnerPaidThisMonth)} of ${money(status.myTarget + status.partnerTarget)}", style = MaterialTheme.typography.titleSmall)
                PersonRow("You · ${(status.myShare * 100).roundToInt()}%", status.myPaidThisMonth, status.myTarget, status.myShortThisMonth, me, ::money)
                PersonRow("$partner · ${(status.partnerShare * 100).roundToInt()}%", status.partnerPaidThisMonth, status.partnerTarget, status.partnerShortThisMonth, them, ::money)
                Text(
                    "Your net income ${money(myIncome)}/month (salary, last 3 months) · $partner ${money(setup.partnerNetIncome)}/month.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Deposits the app can't place.
        status.unknown.forEach { deposit ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Who sent this?", style = MaterialTheme.typography.titleSmall)
                    Row {
                        Text("\"${deposit.transaction.note}\" · ${dayFormat.format(deposit.transaction.date)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("+${money(deposit.transaction.amount)}", fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val key = SharedBills.senderKey(deposit.transaction.note)
                        OutlinedButton(onClick = { SharedBillsSettings.answer(key, Contributor.ME) }) { Text("Me") }
                        OutlinedButton(onClick = { SharedBillsSettings.answer(key, Contributor.PARTNER) }) { Text(partner) }
                        OutlinedButton(onClick = { SharedBillsSettings.answer(key, Contributor.NOT_A_SHARE) }) { Text("Not a share") }
                    }
                    Text("The app remembers your answer for next time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Big bills the account won't cover.
        status.warnings.forEach { w ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${title(w.label)} ${money(w.amount)} · ${dayFormat.format(w.dueDate)}", style = MaterialTheme.typography.titleSmall, color = palette.warning)
                    Text(
                        "The account will be ${money(w.shortBy)} short. Adding ${money(w.extraPerMonth)} a month in total until then covers it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (status.depositsThisMonth.isNotEmpty()) {
            Section("Money in this month") {
                status.depositsThisMonth.forEach { d ->
                    val who = when (d.from) {
                        Contributor.ME -> "You · ${d.reason}"
                        Contributor.PARTNER -> "$partner · ${d.reason}"
                        Contributor.NOT_A_SHARE -> "Not a share"
                        null -> "Unknown sender"
                    }
                    Line(d.transaction.note.ifBlank { "Deposit" }, who, "+${money(d.transaction.amount)}")
                }
            }
        }

        Section("Bills from this account · per month") {
            if (status.bills.isEmpty()) {
                Text("No repeating bills found on this account yet.", style = MaterialTheme.typography.bodyMedium)
            }
            status.bills.forEach { b ->
                val how = if (b.cadence.perYear == 12.0) b.cadence.label else "${money(b.lastAmount)} ${b.cadence.label}"
                Line(title(b.merchant), how, money(b.monthlyCost))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Line("Total", "", money(status.monthlyBills), bold = true)
        }

        Section("Year so far") {
            fun balanceText(v: Double) = if (v >= 0) "${money(v)} ahead" else "${money(-v)} behind"
            Line("You", "", balanceText(status.myYearBalance))
            Line(partner, "", balanceText(status.partnerYearBalance))
        }

        val context = LocalContext.current
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summaryText(status, partner, now, ::money))
                context.startActivity(Intent.createChooser(send, "Send summary"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send summary to $partner") }
    }
}

/** The plain-text monthly summary sent to the partner by message. */
fun summaryText(s: SharedBillsStatus, partner: String, now: Long, money: (Double) -> String): String = buildString {
    appendLine("Bills, ${monthFormat.format(now)}: ${money(s.myTarget + s.partnerTarget)}")
    appendLine("Me ${(s.myShare * 100).roundToInt()}% · ${money(s.myTarget)}" + if (s.myShortThisMonth <= 0) " ✓" else " (${money(s.myShortThisMonth)} to go)")
    appendLine("$partner ${(s.partnerShare * 100).roundToInt()}% · ${money(s.partnerTarget)}" + if (s.partnerShortThisMonth <= 0) " ✓" else " (${money(s.partnerPaidThisMonth)} paid, ${money(s.partnerShortThisMonth)} to go)")
    s.warnings.firstOrNull()?.let { appendLine("Coming up: ${title(it.label)} ${money(it.amount)} on ${dayFormat.format(it.dueDate)}.") }
}.trim()

@Composable
private fun PersonRow(label: String, paid: Double, target: Double, short: Double, color: Color, money: (Double) -> String) {
    val palette = LocalAppPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (short <= 0) "On track" else "${money(short)} short",
                color = if (short <= 0) palette.positive else palette.warning,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val fraction = if (target > 0) (paid / target).coerceIn(0.0, 1.0).toFloat() else 0f
            Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
        Text("${money(paid)} of ${money(target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(heading: String, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Line(left: String, sub: String, right: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(left, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(right, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun SharedBillsSetupForm(accounts: List<Account>, onDone: () -> Unit) {
    val setup by SharedBillsSettings.setup.collectAsState()
    var accountId by remember { mutableStateOf(setup.accountId) }
    var name by remember { mutableStateOf(setup.partnerName) }
    var income by remember { mutableStateOf(if (setup.partnerNetIncome > 0) setup.partnerNetIncome.roundToInt().toString() else "") }
    val incomeValue = income.replace(".", "").replace(",", "").trim().toDoubleOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Split the bills account between the two of you by net income (after tax). Your income comes from your salary; type in your partner's. They don't need the app.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Bills account", style = MaterialTheme.typography.titleSmall)
        accounts.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = accountId == a.id, role = Role.RadioButton, onClick = { accountId = a.id })
            ) {
                RadioButton(selected = accountId == a.id, onClick = null)
                Text(a.name, modifier = Modifier.padding(start = 8.dp))
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Partner's name, as it appears on transfers") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = income, onValueChange = { income = it },
            label = { Text("Partner's net income per month") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { SharedBillsSettings.save(accountId, name, incomeValue ?: 0.0); onDone() },
            enabled = accountId != null && name.isNotBlank() && (incomeValue ?: 0.0) > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save split") }
        if (setup.isSetUp) TextButton(onClick = onDone) { Text("Cancel") }
    }
}
