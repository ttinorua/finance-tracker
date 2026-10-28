package com.financetracker.app.ui.screens.settings

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

private val aheadMonth = SimpleDateFormat("MMM yy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val aheadMonthLong = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Settings > Bills ahead: whether the shared bills account stays covered. Month by month for 3, 6
 * or 12 months: the money coming in (both your transfers) and the bills due on their real dates,
 * the balance after each month, the lowest point, how long today's balance lasts on its own, and
 * what-if sliders (transfers, a new bill, a safety buffer) with the monthly transfer that keeps
 * the account above the buffer.
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
    var transfersIn by remember(plannedIn) { mutableFloatStateOf(plannedIn.toFloat()) }
    var extra by remember { mutableFloatStateOf(0f) }
    var extraMonth by remember { mutableFloatStateOf(4f) }
    var buffer by remember { mutableFloatStateOf(5000f) }
    val partner = setup.partnerName.ifBlank { "Partner" }

    val f = remember(bills, balance, transfersIn, horizon, extra, extraMonth, buffer) {
        BillsAhead.forecast(bills, balance, transfersIn.toDouble(), now, horizon, buffer.toDouble(), extra.toDouble() to extraMonth.roundToInt())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${account?.name ?: "Bills account"} · balance today ${money(balance)}", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCard(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("With your transfers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val ok = f.lowest.endBalance >= 0
                    Text(
                        if (ok) "Covered" else "Short in ${aheadMonthLong.format(f.lowest.start)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (ok) palette.positive else MaterialTheme.colorScheme.error
                    )
                    Text("Lowest ${money(f.lowest.endBalance)} in ${aheadMonth.format(f.lowest.start)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            GlassCard(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("If nothing came in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val r = f.runwayMonths
                    Text(
                        when {
                            r == null -> "$horizon+ months"
                            r == 0 -> "Not this month"
                            else -> "Until end of ${aheadMonthLong.format(f.months[r - 1].start)}"
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                    )
                    Text(if (r == null || r == 0) "today's balance" else "$r month${if (r == 1) "" else "s"} of bills", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Look ahead", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            listOf(3, 6, 12).forEach { h -> FilterChip(selected = horizon == h, onClick = { horizon = h }, label = { Text("$h mo") }) }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                f.months.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(aheadMonth.format(m.start), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("+${money(m.moneyIn)}", style = MaterialTheme.typography.bodySmall, color = palette.positive)
                        }
                        Column(Modifier.weight(1.6f)) {
                            Text("−${money(m.out)}", style = MaterialTheme.typography.bodyMedium)
                            // Name the big ones (anything other than the usual monthly bills).
                            val named = m.bills.filter { b -> bills.firstOrNull { it.name == b.first }?.everyMonths != 1 }
                            Text(
                                if (named.isEmpty()) "Monthly bills" else named.joinToString(" · ") { "${it.first} ${money(it.second)}" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                Text("Next $horizon months", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AheadLine("Bills to pay", money(f.totalOut))
                AheadLine("In the account + transfers", money(f.start + f.totalIn))
                AheadLine(if (f.leftOver >= 0) "Left over" else "Short by", money(abs(f.leftOver)), bold = true)
            }
        }

        // What if…
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What if…", style = MaterialTheme.typography.titleSmall)
                AheadLine("Transfers in per month (both)", money(transfersIn.toDouble()))
                Slider(value = transfersIn, onValueChange = { transfersIn = (it / 250).roundToInt() * 250f }, valueRange = 0f..maxOf(30000f, plannedIn.toFloat() * 2))
                Text(
                    "You ${money(transfersIn * status.myShare)} · $partner ${money(transfersIn * status.partnerShare)} · planned now ${money(plannedIn)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AheadLine("New bill in ${aheadMonthLong.format(f.months.getOrNull(extraMonth.roundToInt())?.start ?: now)}", if (extra == 0f) "none" else money(extra.toDouble()))
                Slider(value = extra, onValueChange = { extra = (it / 500).roundToInt() * 500f }, valueRange = 0f..20000f)
                if (extra > 0) {
                    Slider(value = extraMonth, onValueChange = { extraMonth = it.roundToInt().toFloat() }, valueRange = 0f..(horizon - 1).toFloat(), steps = (horizon - 2).coerceAtLeast(0))
                }
                AheadLine("Never go below", money(buffer.toDouble()))
                Slider(value = buffer, onValueChange = { buffer = (it / 500).roundToInt() * 500f }, valueRange = 0f..20000f)
                BalanceBars(f.months.map { it.endBalance }, buffer.toDouble(), MaterialTheme.colorScheme.primary, palette.warning, MaterialTheme.colorScheme.error)
                val safe = f.lowest.endBalance >= buffer
                Text(
                    if (safe) "Covered for the next $horizon months"
                    else if (f.lowest.endBalance < 0) "The account runs dry in ${aheadMonthLong.format(f.lowest.start)}"
                    else "Dips below your limit in ${aheadMonthLong.format(f.lowest.start)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = if (safe) palette.positive else palette.warning
                )
                Text(
                    "To stay above ${money(buffer.toDouble())}: transfer at least ${money(f.neededPerMonth)} a month in total " +
                        "(you ${money(f.neededPerMonth * status.myShare)}, $partner ${money(f.neededPerMonth * status.partnerShare)}).",
                    style = MaterialTheme.typography.bodySmall
                )
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
private fun AheadLine(left: String, right: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
        Text(right, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/** One bar per month for the end balance: blue fine, orange below the buffer, red below zero. */
@Composable
private fun BalanceBars(ends: List<Double>, buffer: Double, ok: Color, low: Color, negative: Color) {
    val max = (ends.maxOfOrNull { abs(it) } ?: 1.0).coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        ends.forEach { e ->
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((abs(e) / max).toFloat().coerceIn(0.04f, 1f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (e < 0) negative else if (e < buffer) low else ok)
                )
            }
        }
    }
}
