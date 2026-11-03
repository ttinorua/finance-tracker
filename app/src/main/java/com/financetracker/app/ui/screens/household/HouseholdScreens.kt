package com.financetracker.app.ui.screens.household

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.household.HouseholdBudgetLine
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.household.HouseholdMath
import com.financetracker.app.data.household.HouseholdSplit
import com.financetracker.app.data.household.HouseholdState
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.household.Payer
import com.financetracker.app.ui.components.CategoryIconTile
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.MainCategoryIconTile
import com.financetracker.app.ui.components.readable
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.advisor.AttentionItem
import com.financetracker.app.util.advisor.AttentionLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

// ---- Shared bits ---------------------------------------------------------------------------------

/** Colours for who paid: you, your partner, and a joint account you both connect. */
@Composable
fun payerColour(payer: Payer): Color {
    val palette = LocalAppPalette.current
    return when (payer) {
        Payer.ME -> palette.accent
        Payer.PARTNER -> palette.readable(Color(0xFFF472B6))
        Payer.JOINT -> palette.readable(Color(0xFF2DD4BF))
    }
}

/** The month's spending per household line. */
fun HouseholdState.currentSplits(): Map<String, HouseholdSplit> {
    val uid = myUid ?: return emptyMap()
    val month = HouseholdSync.monthKey(System.currentTimeMillis())
    return HouseholdMath.splits(reports.filter { it.month == month }, uid)
}

private fun kr() = Formatters.currencySymbol(CurrencySettings.currencyCode.value)

private fun ago(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> SimpleDateFormat("d MMM", Locale.US).format(time)
    }
}

@Composable
private fun Avatar(name: String, colour: Color, size: Int = 30, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(colour),
        contentAlignment = Alignment.Center
    ) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color(0xFF0A0F1C), fontWeight = FontWeight.SemiBold, fontSize = (size * .42f).sp)
    }
}

/** A bar split into what you, your partner and joint accounts spent, against [limit]. */
@Composable
private fun SplitBar(split: HouseholdSplit, limit: Double, modifier: Modifier = Modifier) {
    val over = split.total > limit && limit > 0
    val scale = if (over || limit <= 0) split.total.coerceAtLeast(1.0) else limit
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
            .then(if (over) Modifier.border(1.5.dp, LocalAppPalette.current.negative, RoundedCornerShape(4.dp)) else Modifier)
    ) {
        listOf(Payer.ME to split.me, Payer.PARTNER to split.partner, Payer.JOINT to split.joint).forEach { (payer, amount) ->
            val w = (amount / scale).toFloat()
            if (w > 0.002f) Box(Modifier.weight(w).fillMaxHeight().background(payerColour(payer)))
        }
        val rest = (1 - split.total / scale).toFloat()
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun Legend(split: HouseholdSplit, partnerName: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
        LegendItem(Payer.ME, "You ${Formatters.whole(split.me)}")
        LegendItem(Payer.PARTNER, "$partnerName ${Formatters.whole(split.partner)}")
        if (split.joint > 0) LegendItem(Payer.JOINT, "Joint ${Formatters.whole(split.joint)}")
    }
}

@Composable
private fun LegendItem(payer: Payer, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(payerColour(payer)))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
    }
}

// ---- Home ------------------------------------------------------------------------------------------

/** The Household card on Home: spent together against the household budget. */
@Composable
fun HouseholdHomeCard(state: HouseholdState, onClick: () -> Unit) {
    val splits = state.currentSplits()
    val limit = state.lines.sumOf { it.limit }
    val total = state.lines.fold(HouseholdSplit()) { acc, line ->
        val s = splits[line.mainCategory] ?: HouseholdSplit()
        HouseholdSplit(acc.me + s.me, acc.partner + s.partner, acc.joint + s.joint)
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    "Household · you & ${state.partnerName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.lines.isEmpty()) {
                Text(
                    "No household budget yet — tap to set one up together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                    Text(Formatters.whole(total.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val left = limit - total.total
                    Text(
                        "of ${Formatters.whole(limit)} ${kr()} · " + if (left >= 0) "${Formatters.whole(left)} left" else "${Formatters.whole(-left)} over",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (left >= 0) MaterialTheme.colorScheme.onSurfaceVariant else LocalAppPalette.current.negative,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
                    )
                }
                SplitBar(total, limit, Modifier.padding(top = 8.dp))
                Legend(total, state.partnerName)
            }
        }
    }
}

/** Household lines over budget this month, for "Needs your attention". */
fun HouseholdState.attentionItems(): List<AttentionItem> {
    if (!joined) return emptyList()
    val splits = currentSplits()
    val month = HouseholdSync.monthKey(System.currentTimeMillis())
    return lines.mapNotNull { line ->
        val s = splits[line.mainCategory] ?: return@mapNotNull null
        if (line.limit <= 0 || s.total <= line.limit) return@mapNotNull null
        AttentionItem(
            key = "household-over-${line.mainCategory}-$month",
            level = AttentionLevel.WARNING,
            title = "Household ${line.mainCategory} is over budget",
            detail = "${Formatters.whole(s.total)} of ${Formatters.whole(line.limit)} ${kr()} · " +
                "$partnerName ${Formatters.whole(s.partner)}, you ${Formatters.whole(s.me)}" +
                if (s.joint > 0) ", joint ${Formatters.whole(s.joint)}" else "",
            notify = false
        )
    }
}

// ---- The household budget ---------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
    mainCategories: List<String>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransactions: (mainCategory: String?) -> Unit
) {
    val state by HouseholdSync.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Household")
                        if (state.joined) {
                            Text(
                                Formatters.month(System.currentTimeMillis()) + " · you & ${state.partnerName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        if (!state.joined) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Share a budget with your partner", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    "Start a household, or join your partner's with their code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )
                Button(onClick = onOpenSettings) { Text("Set up household") }
            }
            return@Scaffold
        }
        val splits = state.currentSplits()
        val limit = state.lines.sumOf { it.limit }
        val total = state.lines.fold(HouseholdSplit()) { acc, line ->
            val s = splits[line.mainCategory] ?: HouseholdSplit()
            HouseholdSplit(acc.me + s.me, acc.partner + s.partner, acc.joint + s.joint)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(state.myName.ifBlank { "You" }, payerColour(Payer.ME))
                            Avatar(state.partnerName, payerColour(Payer.PARTNER), modifier = Modifier.offset(x = (-8).dp))
                            Column(Modifier.padding(start = 6.dp)) {
                                Text("Spent together", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(Formatters.whole(total.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        " of ${Formatters.whole(limit)} ${kr()}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                            }
                        }
                        SplitBar(total, limit, Modifier.padding(top = 10.dp))
                        Legend(total, state.partnerName)
                        val lastChange = state.lines.maxByOrNull { it.updatedAt }
                        Text(
                            listOfNotNull(
                                if (state.partner == null) "Waiting for your partner to join" else "● Live with ${state.partnerName}",
                                lastChange?.let { "budget changed by ${if (it.updatedBy == state.myUid) "you" else state.memberName(it.updatedBy)} ${ago(it.updatedAt)}" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { editing = true }, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Edit budget", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onOpenTransactions(null) }, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("All transactions", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (state.lines.isEmpty()) {
                item {
                    EmptyState(message = "No household budget yet. Tap Edit budget to add the categories you share, like Food and Home.")
                }
            } else {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        state.lines.forEachIndexed { i, line ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            LineRow(line, splits[line.mainCategory] ?: HouseholdSplit(), state.partnerName) { onOpenTransactions(line.mainCategory) }
                        }
                    }
                }
            }
        }
    }
    if (editing) EditBudgetDialog(state.lines, mainCategories, onDismiss = { editing = false })
}

@Composable
private fun LineRow(line: HouseholdBudgetLine, split: HouseholdSplit, partnerName: String, onClick: () -> Unit) {
    val over = split.total - line.limit
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MainCategoryIconTile(line.mainCategory)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(line.mainCategory, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${Formatters.whole(split.total)} of ${Formatters.whole(line.limit)} ${kr()}" + if (over > 0) " · over by ${Formatters.whole(over)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (over > 0) LocalAppPalette.current.negative else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SplitBar(split, line.limit, Modifier.padding(top = 8.dp))
        Legend(split, partnerName)
    }
}

/** Add, change or remove household lines. Each line is saved on its own, so a change your
 * partner makes to another line at the same time is kept. */
@Composable
private fun EditBudgetDialog(lines: List<HouseholdBudgetLine>, mainCategories: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val edits = remember { mutableStateMapOf<String, String>().apply { lines.forEach { put(it.mainCategory, Formatters.whole(it.limit).replace(",", "")) } } }
    val removed = remember { mutableStateMapOf<String, Boolean>() }
    var adding by remember { mutableStateOf(false) }
    val available = mainCategories.filter { m -> edits.keys.none { it.equals(m, ignoreCase = true) } }.sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Household budget") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Monthly limit for each category you share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                edits.keys.sortedBy { it.lowercase() }.filter { removed[it] != true }.forEach { main ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MainCategoryIconTile(main, size = 32.dp)
                        OutlinedTextField(
                            value = edits[main].orEmpty(),
                            onValueChange = { v -> edits[main] = v.filter { it.isDigit() || it == '.' } },
                            label = { Text(main, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            suffix = { Text(kr()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).padding(start = 10.dp)
                        )
                        IconButton(onClick = { removed[main] = true }) { Icon(Icons.Filled.Delete, contentDescription = "Remove $main") }
                    }
                }
                Box {
                    TextButton(onClick = { adding = true }, enabled = available.isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add a category", modifier = Modifier.padding(start = 6.dp))
                    }
                    DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        available.forEach { main ->
                            DropdownMenuItem(
                                text = { Text(main) },
                                leadingIcon = { MainCategoryIconTile(main, size = 28.dp) },
                                onClick = {
                                    edits[main] = ""
                                    removed.remove(main)
                                    adding = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val before = lines.associateBy { it.mainCategory }
                    removed.filterValues { it }.keys.filter { it in before }.forEach { HouseholdSync.removeLine(it) }
                    edits.filterKeys { removed[it] != true }.forEach { (main, text) ->
                        val limit = text.toDoubleOrNull() ?: return@forEach
                        if (before[main]?.limit != limit) HouseholdSync.setLine(main, limit)
                    }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---- Transactions -------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdTransactionsScreen(mainCategory: String?, onBack: () -> Unit) {
    val state by HouseholdSync.state.collectAsState()
    val month = HouseholdSync.monthKey(System.currentTimeMillis())
    val reports = state.reports.filter { it.month == month }
    val list = state.myUid?.let { HouseholdMath.transactions(reports, it, mainCategory) }.orEmpty()
    val partnerTotalsOnly = state.partner?.sharesDetails == false
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(mainCategory ?: "Household spending")
                        Text("Household · ${Formatters.month(System.currentTimeMillis())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (partnerTotalsOnly) {
                item {
                    Text(
                        "${state.partnerName} shares totals only, so their transactions aren't listed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            if (list.isEmpty()) item { EmptyState(message = "No shared transactions this month.") }
            items(list, key = { it.payer.name + it.transaction.id }) { item ->
                val tx = item.transaction
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconTile(null, tx.category.ifBlank { null }, tx.mainCategory, null, size = 36.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(tx.text.ifBlank { tx.category }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Formatters.date(tx.date)} · ${tx.category}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("-" + Formatters.amount(tx.amount), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val who = when (item.payer) {
                            Payer.ME -> "You"
                            Payer.PARTNER -> state.partnerName
                            Payer.JOINT -> "Joint"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(payerColour(item.payer)))
                            Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- Settings → Household -----------------------------------------------------------------------

/** [HouseholdSettings] as its own screen (opened from the household budget). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdSettingsScreen(accounts: List<Account>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Household") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            HouseholdSettings(accounts)
        }
    }
}

@Composable
fun HouseholdSettings(accounts: List<Account>) {
    val state by HouseholdSync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.configured) {
            Text(
                "The household budget isn't switched on in this version of the app yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        if (!state.joined) {
            var name by rememberSaveable { mutableStateOf(state.myName) }
            var code by rememberSaveable { mutableStateOf("") }
            var joinName by rememberSaveable { mutableStateOf(state.myName) }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Title(Icons.Filled.GroupAdd, "Start a household")
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Your name (shown to your partner)") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                    Button(
                        onClick = { scope.launch { HouseholdSync.create(name.trim()) } },
                        enabled = name.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) { Text("Start and get a code") }
                }
            }
            Text("or", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Title(Icons.Filled.Key, "Join with a code")
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.uppercase() }, singleLine = true,
                        label = { Text("Code from your partner") },
                        placeholder = { Text("XXXX-XXXX-XX", fontFamily = FontFamily.Monospace) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                    OutlinedTextField(
                        value = joinName, onValueChange = { joinName = it }, singleLine = true,
                        label = { Text("Your name") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedButton(
                        onClick = { scope.launch { HouseholdSync.join(code.trim(), joinName.trim()) } },
                        enabled = code.count { it.isLetterOrDigit() } >= 10 && joinName.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) { Text("Join") }
                }
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            LockNote("No sign-up or password. The code also carries the key that encrypts your household, so only your two phones can read it.")
            return@Column
        }

        val partner = state.partner
        if (partner == null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Title(Icons.Filled.GroupAdd, "Invite your partner")
                    val code = state.inviteCode
                    val expired = code == null || state.inviteExpiresAt < System.currentTimeMillis()
                    if (!expired) {
                        Text(
                            "Give your partner this code. They enter it under Settings → Household.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            code!!,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 26.sp,
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f))
                                .padding(vertical = 14.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { shareCode(context, code) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Share code", modifier = Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = { copyCode(context, code) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Copy", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        Text(
                            "Works once · valid until ${SimpleDateFormat("d MMM 'at' HH:mm", Locale.US).format(state.inviteExpiresAt)} · waiting for your partner…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            "The last code has expired.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    TextButton(onClick = { scope.launch { HouseholdSync.newInvite() } }, enabled = !state.busy) { Text("Make a new code") }
                }
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(partner.name, payerColour(Payer.PARTNER), size = 40)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(partner.name.ifBlank { "Partner" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            state.partnerUpdatedAt?.let { "● Live · updated ${ago(it)}" } ?: "● Live",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalAppPalette.current.positive
                        )
                    }
                }
            }
        }

        SectionLabel("Which of your accounts count")
        GlassCard(Modifier.fillMaxWidth()) {
            if (accounts.isEmpty()) {
                Text("No accounts yet.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            accounts.forEachIndexed { i, account ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val checked = account.id in state.includedAccountIds
                Row(
                    Modifier.fillMaxWidth().clickable {
                        HouseholdSync.setIncludedAccounts(if (checked) state.includedAccountIds - account.id else state.includedAccountIds + account.id)
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text(account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            "Only spending in household categories from these accounts is shared. A joint account you both connect is counted once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionLabel("What ${state.partnerName} can see")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Totals per category", style = MaterialTheme.typography.bodyLarge)
                    Text("Always shared", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("The transactions behind them", style = MaterialTheme.typography.bodyLarge)
                    Text("Shop, date and amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.shareDetails, onCheckedChange = HouseholdSync::setShareDetails)
            }
        }
        LockNote("Encrypted on your phones. The server stores it, but only you and ${state.partnerName} can read it.")
        TextButton(onClick = { confirmLeave = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Leave household", color = LocalAppPalette.current.negative)
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the household?") },
            text = { Text("Your shared spending is removed from it and you stop seeing ${state.partnerName}'s. Nothing on this phone is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch { HouseholdSync.leave() }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Title(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun LockNote(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = LocalAppPalette.current.positive, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun shareCode(context: Context, code: String) {
    val text = "Join our household budget in Finance Tracker: Settings → Household → Join with a code: $code (valid for 48 hours)"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share code"))
}

private fun copyCode(context: Context, code: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Household code", code))
}
