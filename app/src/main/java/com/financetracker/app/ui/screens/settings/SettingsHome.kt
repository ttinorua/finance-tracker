package com.financetracker.app.ui.screens.settings

import androidx.compose.material.icons.filled.Group
import com.financetracker.app.data.household.HouseholdSync
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.backup.AutoBackupSettings
import com.financetracker.app.data.bank.SupportedBanks
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.EnableBankingPrefs
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.ThemeSettings
import com.financetracker.app.ui.components.AiOrb
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.gradientBrush
import com.financetracker.app.util.Formatters

/** The pages Settings opens into. */
enum class SettingsPage(val title: String) {
    ACCOUNTS("Accounts"),
    CATEGORIES("Categories"),
    BUDGETS("Budgets"),
    LOANS_GOALS("Loans & goals"),
    RECURRING("Recurring payments"),
    SHARED_BILLS("Shared bills"),
    BILLS_AHEAD("Bills ahead"),
    MORTGAGE("Mortgage"),
    AI("AI advisor"),
    BANK("Bank connection"),
    HOUSEHOLD("Household"),
    BACKUP("Backup & import"),
    ATTENTION("Needs your attention"),
    APPEARANCE("Appearance"),
    GENERAL("Currency & totals")
}

/** Settings' first page: big tiles for the four areas used most, the AI advisor, then the rest as
 * lists. Each line shows its current state, so most things can be checked without opening them. */
@Composable
fun SettingsHome(state: SettingsUiState, onOpen: (SettingsPage) -> Unit) {
    val palette = LocalAppPalette.current
    val currency by CurrencySettings.currencyCode.collectAsState()
    val fixed by FixedExpenseCategories.fixedCategoryIds.collectAsState()
    val fixedMerchants by FixedMerchants.merchants.collectAsState()
    val sharedBills by SharedBillsSettings.setup.collectAsState()
    val overallBudgets by BudgetLimits.overallBudgets.collectAsState()
    val categoryBudgets by BudgetLimits.categoryBudgets.collectAsState()
    val loans by LoansAndGoals.loans.collectAsState()
    val goals by LoansAndGoals.goals.collectAsState()
    val ai by AiSettings.state.collectAsState()
    val connections by EnableBankingPrefs.connections.collectAsState()
    val lastSynced by EnableBankingPrefs.lastSyncedAt.collectAsState()
    val backup by AutoBackupSettings.state.collectAsState()
    val notifications by AttentionMonitor.notificationsEnabled.collectAsState()
    val theme by ThemeSettings.selection.collectAsState()
    val household by HouseholdSync.state.collectAsState()

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Icons.Filled.AccountBalanceWallet, "Accounts", plural(state.accounts.size, "account"), Modifier.weight(1f)) { onOpen(SettingsPage.ACCOUNTS) }
            Tile(
                Icons.Filled.Category, "Categories",
                "${state.categories.size} · ${state.categories.count { it.id in fixed }} fixed", Modifier.weight(1f)
            ) { onOpen(SettingsPage.CATEGORIES) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val budgetLine = overallBudgets[null]?.let { "${Formatters.currency(it, currency)}/month" }
                ?: (overallBudgets.size + categoryBudgets.size).takeIf { it > 0 }?.let { plural(it, "budget") }
                ?: "Not set"
            Tile(Icons.Filled.DonutLarge, "Budgets", budgetLine, Modifier.weight(1f)) { onOpen(SettingsPage.BUDGETS) }
            Tile(Icons.Filled.Flag, "Loans & goals", "${plural(loans.size, "loan")} · ${plural(goals.size, "goal")}", Modifier.weight(1f)) {
                onOpen(SettingsPage.LOANS_GOALS)
            }
        }

        // The AI advisor, with its orb.
        GlassCard(modifier = Modifier.fillMaxWidth(), aiBorder = true, onClick = { onOpen(SettingsPage.AI) }) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AiOrb(42.dp)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("AI advisor", style = MaterialTheme.typography.titleMedium.merge(TextStyle(brush = palette.gradientBrush())))
                    Text(
                        ai.chain.takeIf { it.isNotEmpty() }?.let { chain ->
                            chain.first().label + " first" + (chain.getOrNull(1)?.let { " · ${it.label} backup" } ?: "")
                        } ?: "Not set up — add a key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ArrowButton()
            }
        }

        ListGroup(
            listOf(
                Line(
                    Icons.Filled.Group, "Household",
                    when {
                        !household.joined -> "Share a budget with your partner"
                        household.partner == null -> "Waiting for your partner to join"
                        else -> "With ${household.partnerName} · " + if (household.lines.isEmpty()) "no budget yet" else "${household.lines.size} shared categories"
                    },
                    SettingsPage.HOUSEHOLD
                ),
                Line(
                    Icons.Filled.Payments, "Shared bills",
                    if (sharedBills.isSetUp) "Split by net income with ${sharedBills.partnerName.ifBlank { "your partner" }}" else "Split the bills account by income",
                    SettingsPage.SHARED_BILLS
                ),
                Line(
                    Icons.Filled.Timeline, "Bills ahead",
                    if (sharedBills.isSetUp) "Is the bills account covered? 3, 6 or 12 months" else "Set up Shared bills first",
                    SettingsPage.BILLS_AHEAD
                ),
                Line(
                    Icons.Filled.House, "Mortgage",
                    loans.firstOrNull { it.kind == "mortgage" }?.let { m ->
                        if (m.paymentMerchant.isBlank()) "Link the PBS payment to track the debt" else "Debt tracked from its PBS payment"
                    } ?: "Add it under Loans & goals",
                    SettingsPage.MORTGAGE
                ),
                Line(
                    Icons.Filled.EventRepeat, "Recurring payments",
                    if (fixedMerchants.isEmpty()) "Bills found automatically" else "${plural(fixedMerchants.size, "bill")} marked fixed",
                    SettingsPage.RECURRING
                ),
                Line(
                    Icons.Filled.AccountBalance, "Bank connection",
                    connections.takeIf { it.isNotEmpty() }?.let { list ->
                        list.joinToString { SupportedBanks.byId(it.bankId).displayName } +
                            (lastSynced?.let { " · synced ${Formatters.syncTimestamp(it)}" } ?: "")
                    } ?: "Not connected",
                    SettingsPage.BANK
                ),
                Line(
                    Icons.Filled.CloudUpload, "Backup & import",
                    if (backup.enabled) "Weekly to ${backup.locationLabel ?: backup.destination?.label ?: "your backup place"}" else "Automatic backup off",
                    SettingsPage.BACKUP
                )
            ),
            onOpen
        )
        ListGroup(
            listOf(
                Line(Icons.Filled.NotificationsActive, "Needs your attention", if (notifications) "Notifications on" else "Notifications off", SettingsPage.ATTENTION),
                Line(Icons.Filled.Palette, "Appearance", "${theme.style.label} · ${theme.colour.label}" + if (theme.matchPhone) " · matches phone" else "", SettingsPage.APPEARANCE),
                Line(Icons.Filled.Tune, "Currency & totals", "$currency · transfers, salary and bills", SettingsPage.GENERAL)
            ),
            onOpen
        )
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    GlassCard(modifier = modifier.height(118.dp), onClick = onClick) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = .14f))
                    .border(1.dp, accent.copy(alpha = .35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class Line(val icon: ImageVector, val title: String, val subtitle: String, val page: SettingsPage)

@Composable
private fun ListGroup(lines: List<Line>, onOpen: (SettingsPage) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { i, line ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(line.page) }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(line.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(line.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        line.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ArrowButton()
            }
        }
    }
}

@Composable
private fun ArrowButton() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}
