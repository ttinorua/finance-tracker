package com.financetracker.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.app.FinanceApp
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.advisor.FinanceAnalysis
import com.financetracker.app.util.isHabitCategory
import com.financetracker.app.util.isTransferCategory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Settings > Recurring payments: every repeating payment the app found across all categories,
 * soonest first, with what it costs per month and when the next one is due. The switch marks a
 * merchant as a fixed bill ([FixedMerchants]); one already fixed through its category shows as
 * fixed and is changed under Categories instead.
 */
@Composable
fun RecurringSettings() {
    val repository = (LocalContext.current.applicationContext as FinanceApp).repository
    val transactions by remember { repository.observeTransactions() }.collectAsState(initial = emptyList())
    val fixedMerchants by FixedMerchants.merchants.collectAsState()
    val fixedCategories by FixedExpenseCategories.fixedCategoryIds.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val now = remember { System.currentTimeMillis() }
    val payments = remember(transactions) {
        val counted = FinanceAnalysis.counted(
            transactions, null, BudgetSettings.excludeTransfersFromSpending.value,
            BudgetSettings.shiftSalaryToNextMonth.value, MainAccountSettings.mainAccountId.value
        ).filter { !isTransferCategory(it.mainCategoryName, it.categoryName) }
        FinanceAnalysis.recurringPayments(counted, now).filter { !isHabitCategory(it.category) }.sortedBy { it.nextDue }
    }
    fun isFixed(p: FinanceAnalysis.RecurringPayment) = p.merchant in fixedMerchants || p.categoryId in fixedCategories
    val fixedTotal = payments.filter(::isFixed).sumOf { it.monthlyCost }
    val day = remember { SimpleDateFormat("d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Repeating payments found in all your categories. Mark the bills as fixed so they're counted in upcoming expenses and shared bills.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Fixed bills per month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Formatters.currency(fixedTotal, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${payments.count(::isFixed)} of ${payments.size} marked fixed · quarterly and yearly bills spread over the months",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (payments.isEmpty()) {
            Text(
                "Nothing repeating found yet. A payment shows here after it has come back at least 3 times at a regular interval.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    payments.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val byCategory = p.categoryId in fixedCategories
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.merchant.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${Formatters.currency(p.lastAmount, currency)} ${p.cadence.label} · next ~${day.format(p.nextDue)}" +
                                        (p.accountName?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${Formatters.currency(p.monthlyCost, currency)}/month" + if (byCategory) " · fixed by its category" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isFixed(p),
                                enabled = !byCategory,
                                onCheckedChange = { FixedMerchants.setFixed(p.merchant, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
