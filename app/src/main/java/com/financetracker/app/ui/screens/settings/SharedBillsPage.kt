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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.household.SharedBillsSnapshot
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.BillCadence
import com.financetracker.app.util.Contributor
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.Mortgage
import com.financetracker.app.util.PartnerBill
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.SharedBillsInput
import com.financetracker.app.util.SharedBillsStatus
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

private val dayFormat = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val monthFormat = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun title(merchant: String) = merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/**
 * Settings > Shared bills: the joint bills account split by net income. Set up once (account,
 * partner's name and net income), then shows this month's targets and what each of you still has
 * to transfer, where the bill total comes from (the bills account, your recurring card bills
 * ticked as shared, and bills your partner pays by card), who has paid in, your own check (paying
 * too much or too little), the balance since the start, warnings for big bills, and a summary to
 * send the partner. When the partner's phone is in the Household, their figures fill in by
 * themselves; a partner phone without the setup shows the same numbers, read-only.
 */
@Composable
fun SharedBillsPage(accounts: List<Account>) {
    val setup by SharedBillsSettings.setup.collectAsState()
    val household by HouseholdSync.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val repository = (LocalContext.current.applicationContext as FinanceApp).repository
    val transactions by remember { repository.observeTransactions() }.collectAsState(initial = emptyList())
    val fixedMerchants by FixedMerchants.merchants.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val now = remember { System.currentTimeMillis() }
    fun money(v: Double) = Formatters.currency(v, currency)
    val partnerReport = household.partnerSharedBills

    if (editing || !setup.isSetUp) {
        // The partner's phone: show the split as the other phone sees it, and let them tick their own bills.
        val snapshot = partnerReport?.snapshot
        if (!editing && snapshot != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PartnerPhoneView(snapshot, household.memberName(partnerReport.uid), partnerReport.updatedAt, ::money)
                MyBillsTicks(transactions, setup.accountId, setup.sharedMerchants, fixedMerchants, now, ::money)
                HouseholdShareSwitch(household.joined, household.partnerName, setup.shareWithHousehold)
                TextButton(onClick = { editing = true }) { Text("Set up the split on this phone instead") }
            }
        } else {
            SharedBillsSetupForm(accounts, onDone = { editing = false })
        }
        return
    }

    val account = accounts.firstOrNull { it.id == setup.accountId }
    var balance by remember { mutableStateOf(0.0) }
    LaunchedEffect(account, transactions) {
        balance = account?.let { repository.getAccountBalance(it) } ?: 0.0
    }
    val myIncome = remember(transactions) { SharedBills.averageNetSalary(transactions, now) }
    val loans by LoansAndGoals.loans.collectAsState()
    val status = remember(transactions, setup, balance, partnerReport, fixedMerchants, loans) {
        SharedBills.status(
            SharedBillsInput(
                setup.accountId!!, transactions, balance, myIncome, setup.partnerNetIncome, setup.partnerName, setup.answers,
                setup.sharedMerchants, fixedMerchants, setup.partnerBills, setup.lockedMySharePct, setup.startedAt, setup.history,
                partnerReport?.side, now, Mortgage.plannedBills(loans, setup.accountId, now)
            )
        )
    }
    // 1l: keep this month's targets, so later changes don't rewrite it once it's over.
    LaunchedEffect(status.myTarget, status.partnerTarget) {
        if (status.hasTarget) SharedBillsSettings.recordMonth(SharedBills.monthKey(now), status.myTarget, status.partnerTarget)
    }
    val partner = setup.partnerName.ifBlank { "Partner" }
    val palette = LocalAppPalette.current
    val me = MaterialTheme.colorScheme.primary
    val them = Color(0xFFE08A3C)
    var addingBill by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account?.name ?: "Bills account", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Split by net income" + (if (status.shareLocked) " · locked" else "") + " · balance ${money(balance)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { editing = true }) { Text("Edit") }
        }

        // This month: one tile per owner, side by side: what's left to transfer, share, card bills, income.
        Text(
            if (status.hasTarget) "${monthFormat.format(now)}: transfer ${money(status.myToTransfer + status.partnerToTransfer)} in total"
            else "${monthFormat.format(now)}: no target yet",
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnerTile("You", status.myShare, status.hasTarget, status.myTarget, status.myCardThisMonth, status.myToTransfer, status.myPaidInThisMonth, status.myShortThisMonth, status.myIncome, me, ::money, Modifier.weight(1f))
            OwnerTile(partner, status.partnerShare, status.hasTarget, status.partnerTarget, status.partnerCardThisMonth, status.partnerToTransfer, status.partnerPaidInThisMonth, status.partnerShortThisMonth, status.partnerIncome, them, ::money, Modifier.weight(1f))
        }
        Text(
            "Net income: yours is your salary, averaged over the months you were paid this past year" +
                (if (status.partnerConnected) "; $partner's comes from $partner's phone." else "."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!status.hasTarget) {
            Text(
                "No bills found yet. Tick your shared bills below, or wait for payments out of ${account?.name ?: "the bills account"}.",
                style = MaterialTheme.typography.bodySmall
            )
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

        // 1d: your own check.
        if (status.hasTarget && status.monthsCounted > 0) {
            Section("Your check · since ${monthFormat.format(status.startMonth)}") {
                val avg = status.myAveragePerMonth
                Text(
                    when {
                        avg >= 50 -> "You're paying about ${money(avg)} a month more than your share."
                        avg <= -50 -> "You're paying about ${money(-avg)} a month less than your share."
                        else -> "You're paying your share."
                    },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                status.handOver?.let {
                    Text("Ask $partner to take over ${it.name} (${money(it.monthly)}/month) and you're about even.", style = MaterialTheme.typography.bodySmall)
                }
                fun balanceText(v: Double) = if (v >= 0) "${money(v)} ahead" else "${money(-v)} behind"
                Line("You", "transfers + shared card bills", balanceText(status.myBalance))
                Line(partner, if (status.partnerConnected) "from $partner's phone" else "transfers + typed card bills", balanceText(status.partnerBalance))
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

        // 1a: where the bill total comes from.
        Section("Shared bills per month") {
            Text("1 · Paid from ${account?.name ?: "the bills account"}", style = MaterialTheme.typography.titleSmall)
            if (status.budgetPayees.isEmpty()) Text("Nothing paid out yet.", style = MaterialTheme.typography.bodySmall)
            status.budgetPayees.take(8).forEach { Line(it.name, "", money(it.monthly)) }
            if (status.budgetIsRough && status.budgetPayees.isNotEmpty()) {
                Text("Rough for now: under 3 months of history. It becomes the real monthly average over time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("2 · Your bills ticked as shared", style = MaterialTheme.typography.titleSmall)
            if (status.myBills.isEmpty()) Text("None ticked yet (see below).", style = MaterialTheme.typography.bodySmall)
            status.myBills.forEach { Line(it.name, it.via, money(it.monthly)) }
            if (status.movedToBudget.isNotEmpty()) {
                Text("Now paid from the bills account, so not counted twice: ${status.movedToBudget.joinToString()}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("3 · Bills $partner pays by card", style = MaterialTheme.typography.titleSmall)
            if (status.partnerConnected) {
                status.partnerBills.forEach { Line(it.name, "from $partner's phone", money(it.monthly)) }
                if (status.partnerBills.isEmpty()) Text("$partner hasn't ticked any yet.", style = MaterialTheme.typography.bodySmall)
                if (setup.partnerBills.isNotEmpty()) {
                    Text("$partner's phone now shares her bills, so the ones you typed aren't used.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { SharedBillsSettings.clearPartnerBills() }) { Text("Remove the typed ones") }
                }
            } else {
                setup.partnerBills.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${money(b.amount)} ${b.cadence.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(b.monthly), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { SharedBillsSettings.removePartnerBill(b.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${b.name}")
                        }
                    }
                }
                OutlinedButton(onClick = { addingBill = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a bill $partner pays") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Line("Total", "", money(status.monthlyBills), bold = true)
        }

        MyBillsTicks(transactions, setup.accountId, setup.sharedMerchants, fixedMerchants, now, ::money)

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

        HouseholdShareSwitch(household.joined, household.partnerName, setup.shareWithHousehold)

        val context = LocalContext.current
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summaryText(status, partner, now, ::money))
                context.startActivity(Intent.createChooser(send, "Send summary"))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send summary to $partner") }
    }

    if (addingBill) PartnerBillDialog(partner, onDismiss = { addingBill = false }) {
        SharedBillsSettings.savePartnerBill(it)
        addingBill = false
    }
}

/** Your recurring card bills, each with a "Shared" tick: ticked ones count in the bill total and
 * as money you've already paid. */
@Composable
private fun MyBillsTicks(
    transactions: List<com.financetracker.app.data.db.entity.TransactionWithDetails>,
    billsAccountId: Long?,
    shared: Set<String>,
    fixedMerchants: Set<String>,
    now: Long,
    money: (Double) -> String
) {
    val candidates = remember(transactions, billsAccountId, fixedMerchants, shared) {
        SharedBills.candidates(transactions, billsAccountId ?: -1L, fixedMerchants + shared, now)
    }
    Section("Your recurring bills · tick the shared ones") {
        if (candidates.isEmpty()) Text("No recurring bills found on your accounts yet.", style = MaterialTheme.typography.bodySmall)
        candidates.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = c.merchant in shared, role = Role.Checkbox,
                    onValueChange = { SharedBillsSettings.setShared(c.merchant, it) }
                )
            ) {
                Checkbox(checked = c.merchant in shared, onCheckedChange = null)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium)
                    c.accountName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("${money(c.monthly)}/mo", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 2e: whether this phone shares its shared-bill figures with the partner's phone. */
@Composable
private fun HouseholdShareSwitch(joined: Boolean, partnerName: String, on: Boolean) {
    if (!joined) {
        Text(
            "When your partner joins the Household (Settings > Household), their income and bills fill in here by themselves.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Share with $partnerName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Only your net income, the bills you ticked and your transfer amounts, encrypted. Nothing else.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = SharedBillsSettings::setShareWithHousehold)
    }
}

/** 2d: the split as the phone that set it up sees it, on the partner's phone. */
@Composable
private fun PartnerPhoneView(s: SharedBillsSnapshot, other: String, updatedAt: Long, money: (Double) -> String) {
    val myShare = 1 - s.publisherShare
    val me = MaterialTheme.colorScheme.primary
    val them = Color(0xFFE08A3C)
    val hasTarget = s.monthlyBills > 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(s.accountName, style = MaterialTheme.typography.titleMedium)
            Text("Split by net income · from $other's phone · updated ${Formatters.syncTimestamp(updatedAt)}", style = MaterialTheme.typography.bodySmall, color = LocalAppPalette.current.positive)
        }
        Text(
            if (hasTarget) "${monthFormat.format(System.currentTimeMillis())}: transfer ${money(s.publisherToTransfer + s.otherToTransfer)} in total" else "No target yet",
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OwnerTile("You", myShare, hasTarget, s.otherTarget, s.otherCard, s.otherToTransfer, s.otherPaidIn, (s.otherToTransfer - s.otherPaidIn).coerceAtLeast(0.0), s.otherIncome, me, money, Modifier.weight(1f))
            OwnerTile(other, s.publisherShare, hasTarget, s.publisherTarget, s.publisherCard, s.publisherToTransfer, s.publisherPaidIn, (s.publisherToTransfer - s.publisherPaidIn).coerceAtLeast(0.0), s.publisherIncome, them, money, Modifier.weight(1f))
        }
        Section("Shared bills per month") {
            Text("1 · Paid from ${s.accountName}", style = MaterialTheme.typography.titleSmall)
            if (s.payees.isEmpty()) Text("Nothing paid out yet.", style = MaterialTheme.typography.bodySmall)
            s.payees.take(8).forEach { Line(it.name, it.via, money(it.monthly)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("2 · Bills $other pays", style = MaterialTheme.typography.titleSmall)
            if (s.publisherBills.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
            s.publisherBills.forEach { Line(it.name, it.via, money(it.monthly)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("3 · Your bills by card", style = MaterialTheme.typography.titleSmall)
            if (s.otherBills.isEmpty()) Text("None ticked yet (see below).", style = MaterialTheme.typography.bodySmall)
            s.otherBills.forEach { Line(it.name, "", money(it.monthly)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Line("Total", "", money(s.monthlyBills), bold = true)
        }
        Text("Set up on $other's phone. Tick your own shared bills below and they count for you on both phones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One owner's tile: what's left to transfer this month, their share, card bills and net income. */
@Composable
private fun OwnerTile(
    name: String,
    share: Double,
    hasTarget: Boolean,
    target: Double,
    card: Double,
    toTransfer: Double,
    paidIn: Double,
    short: Double,
    income: Double,
    color: Color,
    money: (Double) -> String,
    modifier: Modifier = Modifier
) {
    val palette = LocalAppPalette.current
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("$name · ${(share * 100).roundToInt()}%", color = color, fontWeight = FontWeight.SemiBold)
            Text(if (hasTarget) money(toTransfer) else "-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !hasTarget -> "No target yet"
                    short <= 0 -> "On track · ${money(paidIn)} paid in"
                    else -> "${money(short)} to go · ${money(paidIn)} paid in"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !hasTarget -> MaterialTheme.colorScheme.onSurfaceVariant
                    short <= 0 -> palette.positive
                    else -> palette.warning
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val fraction = if (toTransfer > 0) (paidIn / toTransfer).coerceIn(0.0, 1.0).toFloat() else if (hasTarget) 1f else 0f
                Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
            }
            TileLine("Share", money(target))
            if (card > 0) TileLine("Card bills", "−" + money(card))
            if (income > 0) TileLine("Net income", money(income))
        }
    }
}

@Composable
private fun TileLine(left: String, right: String) {
    Row {
        Text(left, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PartnerBillDialog(partner: String, onDismiss: () -> Unit, onSave: (PartnerBill) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf(BillCadence.MONTHLY) }
    val value = amount.replace(".", "").replace(",", ".").trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A bill $partner pays") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name, e.g. Spotify") }, singleLine = true)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                BillCadence.entries.forEach { c ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected = cadence == c, role = Role.RadioButton, onClick = { cadence = c })
                    ) {
                        RadioButton(selected = cadence == c, onClick = null)
                        Text(c.label.replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(PartnerBill(UUID.randomUUID().toString(), name.trim(), value!!, cadence)) },
                enabled = name.isNotBlank() && value != null && value > 0
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The plain-text monthly summary sent to the partner by message. */
fun summaryText(s: SharedBillsStatus, partner: String, now: Long, money: (Double) -> String): String = buildString {
    appendLine("Bills, ${monthFormat.format(now)}: ${money(s.monthlyBills)}")
    appendLine("Me ${(s.myShare * 100).roundToInt()}% · ${money(s.myTarget)} · transfer ${money(s.myToTransfer)}" + if (s.myShortThisMonth <= 0) " ✓" else " (${money(s.myShortThisMonth)} to go)")
    appendLine("$partner ${(s.partnerShare * 100).roundToInt()}% · ${money(s.partnerTarget)} · transfer ${money(s.partnerToTransfer)}" + if (s.partnerShortThisMonth <= 0) " ✓" else " (${money(s.partnerPaidInThisMonth)} paid, ${money(s.partnerShortThisMonth)} to go)")
    s.warnings.firstOrNull()?.let { appendLine("Coming up: ${title(it.label)} ${money(it.amount)} on ${dayFormat.format(it.dueDate)}.") }
}.trim()

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
    var lockOn by remember { mutableStateOf(setup.lockedMySharePct != null) }
    var lockPct by remember { mutableStateOf(setup.lockedMySharePct?.roundToInt()?.toString() ?: "") }
    val lockValue = lockPct.trim().toDoubleOrNull()?.takeIf { it in 1.0..99.0 }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Split the bills account between the two of you by net income (after tax). Your income comes from your salary; type in your partner's. They don't need the app, but if they join the Household their figures fill in by themselves.",
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lock the split", style = MaterialTheme.typography.bodyMedium)
                Text("Keep your % fixed instead of following the incomes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = lockOn, onCheckedChange = { lockOn = it })
        }
        if (lockOn) {
            OutlinedTextField(
                value = lockPct, onValueChange = { lockPct = it },
                label = { Text("Your share in %") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = { SharedBillsSettings.save(accountId, name, incomeValue ?: 0.0, if (lockOn) lockValue else null); onDone() },
            enabled = accountId != null && name.isNotBlank() && (incomeValue ?: 0.0) > 0 && (!lockOn || lockValue != null),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save split") }
        if (setup.isSetUp) TextButton(onClick = onDone) { Text("Cancel") }
    }
}
