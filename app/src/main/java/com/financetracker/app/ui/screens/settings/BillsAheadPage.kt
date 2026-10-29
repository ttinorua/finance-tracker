package com.financetracker.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.BillsAhead
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.SharedBillsInput
import com.financetracker.app.util.advisor.FinanceAnalysis
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

private val aheadMonth = SimpleDateFormat("MMM yy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val aheadDay = SimpleDateFormat("d MMM", Locale.US)
private val aheadMonthLong = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Settings > Bills ahead for the shared bills account: how long today's balance lasts, the
 * expected monthly bills next to what was actually paid this month, a goal (bills for N months)
 * with when you reach it, what-if sliders, and below that the month-by-month list.
 */
@Composable
fun BillsAheadPage(accounts: List<Account>) {
    val setup by SharedBillsSettings.setup.collectAsState()
    if (!setup.isSetUp) {
        Text("Set up Shared bills first (pick the bills account). Bills ahead looks at that account.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val repository = (LocalContext.current.applicationContext as FinanceApp).repository
    val transactions by remember { repository.observeTransactions() }.collectAsState(initial = emptyList())
    val fixedMerchants by FixedMerchants.merchants.collectAsState()
    val household by HouseholdSync.state.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val account = accounts.firstOrNull { it.id == setup.accountId }
    var balance by remember { mutableStateOf(0.0) }
    LaunchedEffect(account, transactions) { balance = account?.let { repository.getAccountBalance(it) } ?: 0.0 }
    val now = remember { System.currentTimeMillis() }
    fun money(v: Double) = Formatters.currency(v, currency)
    val palette = LocalAppPalette.current

    val input = remember(transactions, setup, balance, fixedMerchants, household.partnerSharedBills) {
        SharedBillsInput(
            setup.accountId!!, transactions, balance, SharedBills.averageNetSalary(transactions, now), setup.partnerNetIncome,
            setup.partnerName, setup.answers, setup.sharedMerchants, fixedMerchants, setup.partnerBills, setup.lockedMySharePct,
            setup.startedAt, setup.history, household.partnerSharedBills?.side, now
        )
    }
    val status = remember(input) { SharedBills.status(input) }
    var includeTicked by remember { mutableStateOf(true) }
    val bills = remember(input, includeTicked) { SharedBills.upcomingBills(input, includeTicked) }
    // Money in: what you both transfer now, plus your ticked card bills when they're counted as moved here.
    val plannedIn = status.myToTransfer + status.partnerToTransfer + if (includeTicked) status.myCardThisMonth else 0.0
    var horizon by remember { mutableIntStateOf(3) }
    val partner = setup.partnerName.ifBlank { "Partner" }
    val monthlyBills = remember(bills) { BillsAhead.monthlyAverage(bills) }
    val paid = remember(input, includeTicked) { SharedBills.paidThisMonth(input, includeTicked) }
    // What still comes in this month: what's left of both transfers.
    val restIn = status.myShortThisMonth + status.partnerShortThisMonth
    var transfersIn by remember(plannedIn) { mutableFloatStateOf(plannedIn.toFloat()) }
    var billsWhatIf by remember(monthlyBills) { mutableFloatStateOf(monthlyBills.toFloat()) }
    var goalMonths by remember { mutableFloatStateOf(6f) }

    val f = remember(bills, balance, plannedIn, horizon, restIn) {
        BillsAhead.forecast(bills, balance, plannedIn, now, horizon, firstMonthIn = restIn)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(account?.name ?: "Bills account", style = MaterialTheme.typography.titleMedium)

        // How long today's balance lasts.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's balance ${money(balance)} lasts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val lasts = BillsAhead.lastsMonths(balance, monthlyBills)
                Text(
                    if (lasts >= 24) "24+ months" else "${oneDecimal(lasts)} months",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    if (monthlyBills <= 0) "No bills found yet for this account."
                    else "if nothing more came in, at ${money(monthlyBills)} of bills a month" +
                        if (lasts < 24) ": until about ${aheadMonth.format(FinanceAnalysis.monthStart(now, lasts.toInt()))}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Expected vs actually paid.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Our monthly bills", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(monthlyBills), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("expected, yearly ones spread out", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Actually paid in ${aheadMonthLong.format(FinanceAnalysis.monthStart(now))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(money(paid.sumOf { it.amount }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = palette.positive)
                        Text("real payments this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val later = bills.filter { it.everyMonths > 1 }.sortedBy { it.nextDue }
                if (paid.isNotEmpty() || later.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                paid.take(10).forEach { p ->
                    val where = if (p.fromBudget) account?.name ?: "bills account" else "your account"
                    AheadLine("✓ ${p.name} · $where, ${aheadDay.format(p.date)}", money(p.amount), small = true)
                }
                if (paid.size > 10) Text("+ ${paid.size - 10} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                later.forEach { b ->
                    Text(
                        "○ ${b.name} · next in ${aheadMonth.format(b.nextDue)}, ${money(b.amount / b.everyMonths)}/mo saved",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Goal: bills for N months in the account.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val g = goalMonths.roundToInt()
                val goal = g * billsWhatIf.toDouble()
                AheadLine("Goal: bills for", "$g month${if (g == 1) "" else "s"} = ${money(goal)}", bold = true)
                Slider(value = goalMonths, onValueChange = { goalMonths = it.roundToInt().toFloat() }, valueRange = 1f..12f, steps = 10)
                val net = transfersIn.toDouble() - billsWhatIf
                val n = BillsAhead.monthsToGoal(balance, goal, net)
                val (head, sub, color) = when {
                    n == 0 -> Triple(
                        "Reached ✓",
                        "You already have ${oneDecimal(BillsAhead.lastsMonths(balance, billsWhatIf.toDouble()).coerceAtMost(99.0))} months of bills in the account.",
                        palette.positive
                    )
                    n != null -> Triple(
                        "$n month${if (n == 1) "" else "s"} to go",
                        "Reached around ${aheadMonth.format(FinanceAnalysis.monthStart(now, n))}, ${money(goal - balance)} still to build up.",
                        MaterialTheme.colorScheme.primary
                    )
                    else -> Triple(
                        "Not reachable like this",
                        "More goes out than comes in. Transfer more than ${money(billsWhatIf.toDouble())} a month to start building up.",
                        MaterialTheme.colorScheme.error
                    )
                }
                Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // What if…
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What if…", style = MaterialTheme.typography.titleSmall)
                AheadLine("You both transfer per month", money(transfersIn.toDouble()))
                Slider(value = transfersIn, onValueChange = { transfersIn = (it / 250).roundToInt() * 250f }, valueRange = 0f..maxOf(25000f, plannedIn.toFloat() * 2))
                Text(
                    "You ${money(transfersIn * status.myShare)} · $partner ${money(transfersIn * status.partnerShare)} · now ${money(plannedIn)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AheadLine("Bills per month", money(billsWhatIf.toDouble()))
                Slider(value = billsWhatIf, onValueChange = { billsWhatIf = (it / 250).roundToInt() * 250f }, valueRange = 0f..maxOf(25000f, monthlyBills.toFloat() * 2))
                val net = transfersIn.toDouble() - billsWhatIf
                AheadLine("Each month the account", (if (net >= 0) "grows " else "shrinks ") + money(abs(net)), bold = true)
                Text(
                    if (net >= 0) "That builds up your buffer."
                    else "The balance runs out in about ${oneDecimal(BillsAhead.lastsMonths(balance, -net))} months.",
                    style = MaterialTheme.typography.bodySmall, color = if (net >= 0) palette.positive else MaterialTheme.colorScheme.error
                )
            }
        }

        // Month by month.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Month by month", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            listOf(3, 6, 12).forEach { h -> FilterChip(selected = horizon == h, onClick = { horizon = h }, label = { Text("$h mo") }) }
        }
        bills.filter { it.everyMonths > 1 }.minByOrNull { it.nextDue }?.let { b ->
            Text(
                "Saving for: ${b.name}, ${money(b.amount)} in ${aheadMonth.format(b.nextDue)}. Part of each transfer goes towards it, so the balance grows until then.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                f.months.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(aheadMonth.format(m.start), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (i == 0) Text("rest of month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (i == 0 && m.moneyIn <= 0) "Transfers done ✓" else "+${money(m.moneyIn)}",
                                style = MaterialTheme.typography.bodySmall, color = palette.positive
                            )
                        }
                        Column(Modifier.weight(1.6f)) {
                            if (m.out <= 0) {
                                Text(if (i == 0) "Bills already paid" else "No bills", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("−${money(m.out)}", style = MaterialTheme.typography.bodyMedium)
                                // Name the big ones (anything other than the usual monthly bills).
                                val named = m.bills.filter { b -> bills.firstOrNull { it.name == b.first }?.everyMonths != 1 }
                                Text(
                                    if (named.isEmpty()) "Monthly bills" else named.joinToString(" · ") { "${it.first} ${money(it.second)}" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                            Text(
                                money(m.endBalance), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                color = if (m.endBalance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            if (i == f.lowestIndex) Text("Lowest point", style = MaterialTheme.typography.bodySmall, color = palette.warning)
                        }
                    }
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rest of this month + next ${horizon - 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AheadLine("Bills still to pay", money(f.totalOut))
                AheadLine("In the account + transfers to come", money(f.start + f.totalIn))
                AheadLine(if (f.leftOver >= 0) "Left over" else "Short by", money(abs(f.leftOver)), bold = true)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Count my shared bills as moved here", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Your bills ticked as shared but still paid from your own account, as if already paid from ${account?.name ?: "the bills account"}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = includeTicked, onCheckedChange = { includeTicked = it })
        }
        Text(
            "Bills and dates come from what the account pays: ones that repeat keep their rhythm (a yearly bill in its month), the rest count as monthly.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AheadLine(left: String, right: String, bold: Boolean = false, small: Boolean = false) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, modifier = Modifier.weight(1f), style = style, fontWeight = if (bold) FontWeight.Bold else null)
        Text(right, style = style, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)
