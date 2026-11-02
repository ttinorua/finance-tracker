package com.financetracker.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.LoanSchedule
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val WHOLE = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
private val PERCENT = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))
private const val DAY = 24L * 60 * 60 * 1000

private fun money(value: Double, currency: String) = "${WHOLE.format(value)} ${Formatters.currencySymbol(currency)}"
private fun pct(value: Double) = "${PERCENT.format(value)}%"

private fun monthNames(months: List<Int>): String {
    val names = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val list = months.sorted().map { names[it - 1] }
    return if (list.size <= 1) list.joinToString() else list.dropLast(1).joinToString(", ") + " and " + list.last()
}

private fun ordinal(day: Int) = "$day" + when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

/**
 * In the loan dialog: how the AI's copy of the payment table checks out. Rows that don't add up
 * are listed with what they should be, so the user can fix a misread number in one tap.
 */
@Composable
internal fun ScheduleCheckCard(loan: Loan, currency: String, onChange: (Loan) -> Unit, onView: () -> Unit) {
    val issues = LoanSchedule.check(loan)
    val next = LoanSchedule.nextPayment(loan, System.currentTimeMillis())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Payment schedule · ${loan.schedule.size} payments", style = MaterialTheme.typography.labelLarge)
            next?.let {
                Text(
                    "Next ${Formatters.date(it.date)}: ${money(it.youPay, currency)} " +
                        "(${money(it.interestAndFee - it.discount, currency)} interest & fee, ${money(it.principal, currency)} paid off)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (issues.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("Every row adds up", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        "${issues.size} ${if (issues.size == 1) "row doesn't" else "rows don't"} add up — the AI may have misread a number. Compare with your document.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp)
                    )
                }
                issues.take(5).forEach { issue ->
                    Column {
                        Text("${issue.date}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
                        issue.expectedDebt?.let { expected ->
                            Text(
                                "Debt left should be ${Formatters.amount(expected)}, read ${Formatters.amount(loan.schedule[issue.index].debtAfter)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { onChange(LoanSchedule.withDebt(loan, issue.index, expected)) }) {
                                Text("Use ${Formatters.amount(expected)}")
                            }
                        }
                    }
                }
                if (issues.size > 5) Text("…and ${issues.size - 5} more.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth()) { Text("View full schedule") }
        }
    }
}

@Composable
private fun FullScreen(title: String, subtitle: String, onDismiss: () -> Unit, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    action?.invoke()
                }
                content()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = color, fontWeight = if (bold) FontWeight.Medium else null)
        Text(value, color = color, fontWeight = if (bold) FontWeight.Medium else null)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A loan read from its document: next payment, reminders, discount, rates, resets, total cost and tax. */
@Composable
internal fun LoanDetailsDialog(loan: Loan, currency: String, onEdit: () -> Unit, onChange: (Loan) -> Unit, onViewSchedule: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as FinanceApp
    val transactions by remember { app.repository.observeTransactions() }.collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()
    val next = LoanSchedule.nextPayment(loan, now)
    val nextDate = LoanSchedule.nextPaymentDate(loan, now)
    val last = LoanSchedule.lastPayment(loan, now)
    val primary = MaterialTheme.colorScheme.primary

    FullScreen(
        title = loan.name.ifBlank { "Loan" },
        subtitle = listOf(loan.lender, loan.loanType).filter { it.isNotBlank() }.joinToString(" · "),
        onDismiss = onDismiss,
        action = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (next != null) {
                Section("Next payment") {
                    val days = ((next.date - (now - Math.floorMod(now, DAY))) / DAY).toInt()
                    Note("${Formatters.date(next.date)} · ${if (days == 0) "today" else "in $days days"}")
                    Text(money(next.youPay, currency), style = MaterialTheme.typography.headlineSmall)
                    if (next.interest != null && next.fee != null) {
                        Line("Interest", money(next.interest, currency))
                        Line("Fee (bidrag)", money(next.fee, currency))
                    } else {
                        Line("Interest & fee", money(next.interestAndFee, currency))
                    }
                    Line("Paid off the loan", money(next.principal, currency), color = primary)
                    if (next.discount > 0) {
                        Line("Payment in the lender's table", money(next.bankPayment, currency), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Line("${loan.discountName.ifBlank { "Discount" }}", "−${money(next.discount, currency)}", color = primary)
                    }
                    HorizontalDivider()
                    Line("You pay", money(next.youPay, currency), bold = true)
                    next.afterTax?.let { Line("After tax deduction" + (loan.taxDeductionPct?.let { t -> " (${pct(t)})" } ?: ""), money(it, currency)) }
                    if (next.estimate) Note("This payment is the lender's estimate — the rate has been reset since the document.")
                }
            }

            Section("Payments") {
                if (loan.paymentDay != null && loan.paymentMonths.isNotEmpty()) {
                    Text("Due on the ${ordinal(loan.paymentDay)} of ${monthNames(loan.paymentMonths)}.")
                } else if (nextDate != null) {
                    Text("Next due ${Formatters.date(nextDate)}.")
                }
                if (loan.schedule.isNotEmpty()) {
                    val total = loan.totalPayments ?: loan.schedule.size
                    val paid = LoanSchedule.paidCount(loan, now)
                    Line("$paid of $total paid", "last ${Formatters.date(LoanSchedule.payments(loan).lastOrNull()?.date ?: now)}")
                    LinearProgressIndicator(progress = { paid.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    LoanSchedule.debtNow(loan, now)?.let { Line("Debt left now", money(it, currency)) }
                }
                if (last != null) {
                    val match = LoanSchedule.findBankPayment(loan, last, transactions)
                    when {
                        match != null -> Note("Last payment ${Formatters.date(last.date)}: paid ${money(kotlin.math.abs(match.amount), currency)} on ${Formatters.date(match.date)} (${match.accountName}).")
                        LoanSchedule.windowClosed(last, now) -> Note("Last payment ${Formatters.date(last.date)}: not found in your transactions.")
                        else -> Note("Last payment ${Formatters.date(last.date)}: not in your transactions yet.")
                    }
                } else if (next != null) {
                    Note("The app will look for the ${loan.lender.ifBlank { "loan" }} payment in your bank transactions.")
                }
                Toggle("Remind me 3 days before", loan.remindPayments) { onChange(loan.copy(remindPayments = it)) }
            }

            loan.discountPct?.takeIf { it > 0 }?.let { discountPct ->
                Section(loan.discountName.ifBlank { "Fee discount" }) {
                    Line("Discount", "${pct(discountPct)} a year")
                    val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }.get(Calendar.YEAR) + 1
                    LoanSchedule.yearTotals(loan).firstOrNull { it.year == year }?.takeIf { it.discount > 0 }?.let { Line("In $year", "≈ ${money(it.discount, currency)}", color = primary) }
                    if (loan.discountUntil.isNotBlank()) Line("Guaranteed until", loan.discountUntil)
                    Note("Taken off the fee each payment. The lender's table leaves it out, so the app subtracts it.")
                }
            }

            if (loan.interestRatePct != null || loan.contributionRatePct != null) {
                Section("Interest rate") {
                    loan.interestRatePct?.let { Line("Interest", pct(it)) }
                    loan.contributionRatePct?.let { Line("Fee (bidrag)", pct(it)) }
                    if (loan.interestRatePct != null && loan.contributionRatePct != null) {
                        Line("Together", pct(loan.interestRatePct + loan.contributionRatePct), bold = true)
                    }
                    Note("A year, on the debt left." + if (loan.rateResets.isNotEmpty() || loan.nextRateReset.isNotBlank()) " Fixed until the next rate reset." else "")
                }
            }

            val resets = LoanSchedule.resetDates(loan)
            if (resets.isNotEmpty() || loan.nextRateReset.isNotBlank()) {
                Section("Rate resets") {
                    Note("On each date the rate is set again at the market rate, so payments can go up or down.")
                    if (resets.isEmpty()) Text(loan.nextRateReset)
                    resets.forEach { reset ->
                        Line(
                            Formatters.date(reset) + if (reset < now) " (passed)" else "",
                            LoanSchedule.debtOn(loan, reset)?.let { "debt ${money(it, currency)}" } ?: "",
                            color = if (reset < now) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                        )
                    }
                    Toggle("Remind me 3 months before", loan.remindRateResets) { onChange(loan.copy(remindRateResets = it)) }
                }
            }

            if (loan.totalRepayable != null || loan.totalInterestAndFees != null || loan.setupCosts != null || loan.aopPct != null) {
                Section("What the loan costs in total") {
                    loan.originalAmount?.let { Line("Borrowed", money(it, currency)) }
                    loan.totalInterestAndFees?.let { Line("Interest & fee over the loan", money(it, currency)) }
                    loan.totalRepayable?.let { Line("Total you pay back", money(it, currency), bold = true) }
                    loan.setupCosts?.let { Line("Setup costs (paid once)", money(it, currency)) }
                    loan.aopPct?.let { Line("Yearly cost incl. all fees (ÅOP)", pct(it)) }
                    Note("Assumes today's rate for the whole loan.")
                }
            }

            loan.taxDeductionPct?.let {
                Section("Tax deduction") {
                    Line("Deduction on interest & fee", pct(it))
                    Note("You get this share of interest and fee back through your tax. Used for the after-tax figures. Change it under Edit.")
                }
            }

            if (loan.schedule.isNotEmpty()) {
                OutlinedButton(onClick = onViewSchedule, modifier = Modifier.fillMaxWidth()) {
                    Text("View full schedule (${loan.schedule.size} payments)")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Every payment in the lender's table, with a year-by-year chart of interest vs. paid off. */
@Composable
internal fun LoanScheduleDialog(loan: Loan, currency: String, onDismiss: () -> Unit) {
    val payments = remember(loan) { LoanSchedule.payments(loan) }
    val years = remember(loan) { LoanSchedule.yearTotals(loan) }
    val resets = remember(loan) { LoanSchedule.resetDates(loan) }
    var open by remember { mutableStateOf<Int?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val max = years.maxOfOrNull { it.interestAndFee - it.discount + it.principal }?.takeIf { it > 0 } ?: 1.0

    FullScreen(title = "Payment schedule", subtitle = loan.name, onDismiss = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Section("Each year: interest & fee vs paid off") {
                    Note("Bars fade where the figures are the lender's estimates (after the first rate reset).")
                    years.forEach { y ->
                        val alpha = if (y.estimates == y.payments) 0.45f else 1f
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("${y.year}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                            Row(modifier = Modifier.weight(1f).height(12.dp)) {
                                val cost = ((y.interestAndFee - y.discount) / max).toFloat()
                                val paid = (y.principal / max).toFloat()
                                if (cost > 0f) Box(Modifier.weight(cost).fillMaxHeight().background(muted.copy(alpha = alpha)))
                                if (paid > 0f) Box(Modifier.weight(paid).fillMaxHeight().background(primary.copy(alpha = alpha)))
                                val rest = 1f - cost - paid
                                if (rest > 0.001f) Spacer(Modifier.weight(rest))
                            }
                            Text(
                                WHOLE.format(y.principal), style = MaterialTheme.typography.bodySmall, color = primary,
                                textAlign = TextAlign.End, modifier = Modifier.width(64.dp)
                            )
                        }
                    }
                    Note("Grey: interest & fee (after any discount). Green: paid off, with the amount on the right.")
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    listOf("Date", "Interest & fee", "Paid off", "Debt left").forEachIndexed { i, label ->
                        Text(
                            label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = if (i == 0) TextAlign.Start else TextAlign.End, modifier = Modifier.weight(if (i == 0) 1.1f else 1f)
                        )
                    }
                }
                HorizontalDivider()
            }
            items(payments, key = { it.index }) { p ->
                val previous = payments.getOrNull(payments.indexOf(p) - 1)?.date ?: Long.MIN_VALUE
                resets.filter { it in (previous + 1)..p.date }.forEach { reset ->
                    Text(
                        "${Formatters.date(reset)} · rate reset — rows below are estimates",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp)).padding(8.dp)
                    )
                }
                Column(modifier = Modifier.fillMaxWidth().clickable { open = if (open == p.index) null else p.index }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    val style = if (p.estimate) MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic) else MaterialTheme.typography.bodySmall
                    Row {
                        Text(Formatters.date(p.date), style = style, modifier = Modifier.weight(1.1f))
                        Text(WHOLE.format(p.interestAndFee), style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(WHOLE.format(p.principal), style = style, color = primary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(WHOLE.format(p.debtAfter), style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                    if (open == p.index) {
                        Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            p.interest?.let { Line("Interest", money(it, currency)) }
                            p.fee?.let { Line("Fee (bidrag)", money(it, currency)) }
                            Line("Payment in the table", money(p.bankPayment, currency))
                            if (p.discount > 0) Line(loan.discountName.ifBlank { "Discount" }, "−${money(p.discount, currency)}", color = primary)
                            Line("You pay", money(p.youPay, currency), bold = true)
                            p.afterTax?.let { Line("After tax", money(it, currency)) }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            item { Note("Tap a payment to see it before and after tax.") }
        }
    }
}
