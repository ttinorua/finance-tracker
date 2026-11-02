package com.financetracker.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.appBackdrop
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.LoanSchedule
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val WHOLE = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
private val shortDate = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/**
 * Whether the AI's copy of the payment table adds up. Rows that don't are listed with what they
 * should be, so a misread number is fixed in one tap.
 */
@Composable
internal fun ScheduleCheck(loan: Loan, onChange: (Loan) -> Unit) {
    val issues = LoanSchedule.check(loan)
    if (issues.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "${loan.schedule.size} payments read · every row adds up",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp)
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(
                "${issues.size} ${if (issues.size == 1) "row doesn't" else "rows don't"} add up. The AI may have misread a number: compare with your document.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp)
            )
        }
        issues.take(5).forEach { issue ->
            Text("${issue.date}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
            issue.expectedDebt?.let { expected ->
                Text(
                    "Debt left should be ${Formatters.amount(expected)}, read as ${Formatters.amount(loan.schedule[issue.index].debtAfter)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { onChange(LoanSchedule.withDebt(loan, issue.index, expected)) }) { Text("Use ${Formatters.amount(expected)}") }
            }
        }
        if (issues.size > 5) Text("…and ${issues.size - 5} more.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (bold) FontWeight.Medium else null)
        Text(value, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (bold) FontWeight.Medium else null)
    }
}

/** Every payment in the lender's table, with a year-by-year chart of interest & fee against repayment. */
@Composable
internal fun LoanScheduleDialog(loan: Loan, currency: String, onDismiss: () -> Unit) {
    val payments = remember(loan) { LoanSchedule.payments(loan) }
    val years = remember(loan) { LoanSchedule.yearTotals(loan) }
    val resets = remember(loan) { LoanSchedule.resetDates(loan) }
    var open by remember { mutableStateOf<Int?>(null) }
    val palette = LocalAppPalette.current
    // Repayment green against grey interest, told apart by lightness too, not only colour.
    val primary = palette.positive
    val muted = palette.muted.copy(alpha = 0.55f)
    val max = years.maxOfOrNull { it.interestAndFee - it.discount + it.principal }?.takeIf { it > 0 } ?: 1.0
    fun money(v: Double) = Formatters.currency(v, currency)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The app's theme is see-through (cards over a gradient), so paint the gradient here like other full-screen dialogs.
        Surface(modifier = Modifier.fillMaxSize().appBackdrop(palette), color = Color.Transparent) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Payment table", style = MaterialTheme.typography.titleLarge)
                        Text(loan.name.ifBlank { "Mortgage" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item {
                        Text("Each year: interest & fee vs repayment", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Grey: interest & fee (after any discount). Green: repayment (afdrag), the amount on the right. Faded: the lender's estimates, after the first rate reset.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
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
                                Text(WHOLE.format(y.principal), style = MaterialTheme.typography.bodySmall, color = primary, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                            listOf("Date", "Interest & fee", "Repayment", "Debt left").forEachIndexed { i, label ->
                                Text(
                                    label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End, modifier = Modifier.weight(if (i == 0) 1.15f else 1f)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    itemsIndexed(payments, key = { _, p -> p.index }) { i, p ->
                        val previous = payments.getOrNull(i - 1)?.date ?: Long.MIN_VALUE
                        resets.filter { it in (previous + 1)..p.date }.forEach { reset ->
                            Text(
                                "${shortDate.format(reset)} · rate reset: rows below are the lender's estimates",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp)).padding(8.dp)
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().clickable { open = if (open == p.index) null else p.index }.padding(horizontal = 4.dp, vertical = 8.dp)) {
                            val style = MaterialTheme.typography.bodySmall.let { if (p.estimate) it.copy(fontStyle = FontStyle.Italic) else it }
                            Row {
                                Text(shortDate.format(p.date), style = style, modifier = Modifier.weight(1.15f))
                                Text(WHOLE.format(p.interestAndFee), style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                                Text(WHOLE.format(p.principal), style = style, color = primary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                                Text(WHOLE.format(p.debtAfter), style = style, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                            }
                            if (open == p.index) {
                                Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    p.interest?.let { Line("Interest", money(it)) }
                                    p.fee?.let { Line("Administration fee (bidrag)", money(it)) }
                                    Line("Repayment (afdrag)", money(p.principal), color = primary)
                                    Line("Payment in the table", money(p.bankPayment))
                                    if (p.discount > 0) Line(loan.discountName.ifBlank { "Discount" }, "−" + money(p.discount), color = primary)
                                    Line("You pay", money(p.youPay), bold = true)
                                    Line("After tax deduction", money(p.afterTax))
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    item {
                        Text(
                            "Tap a payment to see its parts, before and after tax.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
