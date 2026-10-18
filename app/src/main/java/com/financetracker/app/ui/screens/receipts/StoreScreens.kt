package com.financetracker.app.ui.screens.receipts

import com.financetracker.app.data.db.entity.Receipt
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.data.stores.Store
import com.financetracker.app.data.stores.StoreAccount
import com.financetracker.app.data.stores.StoreAccounts
import com.financetracker.app.data.stores.StoreConnector
import com.financetracker.app.data.stores.LoginMode
import com.financetracker.app.data.stores.StoreConsents
import com.financetracker.app.data.stores.StoreLoginExpired
import com.financetracker.app.data.stores.StoreSync
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.ui.components.StoreLogo
import com.financetracker.app.ui.theme.IncomeGreen
import com.financetracker.app.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private fun receiptCountText(count: Int) = if (count == 1) "1 receipt" else "$count receipts"

/** "" while the page has something for the person to fill in or press; otherwise where it is and
 * what it shows (host and path, never the query; the store's screen name; the first visible
 * words), so a login that stops after the password can be reported. A loading overlay counts as
 * nothing to do. */
private const val PAGE_STATE_SCRIPT = """(function(){
  function shown(e){ if(!e||e.offsetParent===null) return false; var r=e.getBoundingClientRect(); return r.width>0&&r.height>0; }
  var busy=Array.prototype.some.call(document.querySelectorAll('.gigya-screen-loader,.gigya-screen-dimmer,.gigya-loader'),shown);
  var fields=document.querySelectorAll('input:not([type=hidden]),button,select,textarea,a[href]');
  if(!busy&&!window.__reportAnyway&&Array.prototype.some.call(fields,shown)) return '';
  var b=document.body, t=(b&&b.innerText||'').replace(/\s+/g,' ').trim();
  var screen=b&&b.getAttribute('data-page');
  return location.host+location.pathname+(screen?', screen '+screen:'')+(busy?', loading':'')+(t?', shows \u201c'+t.slice(0,60)+'\u201d':', empty');
})()"""

/** [PAGE_STATE_SCRIPT], reporting even when the page has something to fill in or press. */
private const val PAGE_STATE_SCRIPT_ALWAYS = "window.__reportAnyway=true;$PAGE_STATE_SCRIPT"

/** The string a page script returned, from the JSON evaluateJavascript hands back ("\"done\""). */
private fun jsString(json: String?): String? =
    runCatching { org.json.JSONTokener(json ?: return null).nextValue() as? String }.getOrNull()

/** A store's colours and short mark, for its tile. */
private fun brand(store: Store): Triple<Color, Color, String> = when (store) {
    Store.LIDL -> Triple(Color(0xFF0050AA), Color(0xFFFFF000), "L+")
    Store.NEMLIG -> Triple(Color(0xFFE8642C), Color.White, "n")
    Store.NETTO -> Triple(Color(0xFFFCD700), Color(0xFF1D1D1B), "N+")
    Store.BILKA -> Triple(Color(0xFF0066B3), Color.White, "B+")
    Store.FOETEX -> Triple(Color(0xFF0A2240), Color.White, "F+")
    Store.REMA -> Triple(Color(0xFF0A3B8C), Color.White, "R")
    Store.COOP -> Triple(Color(0xFFE2001A), Color.White, "coop")
}

@Composable
private fun StoreTile(background: Color, foreground: Color, mark: String) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(mark, color = foreground, fontWeight = FontWeight.Bold, fontSize = if (mark.length > 2) 11.sp else 16.sp)
    }
}

@Composable
private fun StoreTile(store: Store) {
    val (bg, fg, mark) = brand(store)
    StoreLogo(store.company, 40.dp) { StoreTile(bg, fg, mark) }
}

/** Settings for store accounts: each chain on its own row with its connected accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreConnectionsScreen(repository: FinanceRepository, onBack: () -> Unit, onConnect: (Store, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { StoreAccounts.load(context); StoreConsents.load(context) }
    val accounts by StoreAccounts.accounts.collectAsState()
    val consents by StoreConsents.consents.collectAsState()
    val running by StoreSync.running.collectAsState()
    // How many of each store's receipts are in the app. Counted from the receipts themselves, so
    // it stays right when an account is reconnected (its receipts are already there and not
    // fetched twice) and is shared by all accounts of a store.
    val receiptsByStore by remember { repository.observeReceipts().map { list -> list.groupingBy { it.source }.eachCount() } }
        .collectAsState(initial = emptyMap())
    var managing by remember { mutableStateOf<StoreAccount?>(null) }
    var disconnecting by remember { mutableStateOf<StoreAccount?>(null) }
    var clearConsents by remember { mutableStateOf(false) }
    // Receipts found by "Check receipts" that weren't paid from the person's own bank account.
    var notYours by remember { mutableStateOf<List<Receipt>?>(null) }
    var checking by remember { mutableStateOf(false) }
    // Accounts the person pressed "Fetch now" for, so the result can be shown when it's done.
    val fetchRequested = remember { mutableSetOf<String>() }
    LaunchedEffect(Unit) {
        StoreSync.finished.collect { result ->
            if (fetchRequested.remove(result.accountId)) {
                Toast.makeText(context, StoreSync.resultText(result), Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect your stores") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val connected = Store.entries.filter { s -> accounts.any { it.store == s } }
            connected.forEach { store ->
                item(key = "c_${store.id}") {
                    GlassCard {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StoreTile(store)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(store.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    receiptCountText(receiptsByStore[store.id] ?: 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "✓ Connected",
                                color = IncomeGreen,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(IncomeGreen.copy(alpha = .15f)).padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        accounts.filter { it.store == store }.forEach { account ->
                            HorizontalDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable { managing = account }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                    val status = when {
                                        account.id in running -> "Fetching receipts…"
                                        account.problem != null -> account.problem
                                        account.lastSyncAt == 0L -> "Waiting to fetch"
                                        else -> (if (account.lastReceiptAt > 0) "Latest ${Formatters.date(account.lastReceiptAt)} · " else "") +
                                            "synced ${Formatters.shortWhen(account.lastSyncAt)} · " +
                                            when (account.lastFetchAdded) {
                                                0 -> "no new receipts"
                                                1 -> "1 new receipt"
                                                else -> "${account.lastFetchAdded} new receipts"
                                            }
                                    }
                                    Text(
                                        status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (account.problem != null && account.id !in running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (account.id in running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                        TextButton(onClick = { onConnect(store, true) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Add another (e.g. your partner's)")
                        }
                    }
                }
            }
            if (connected.isNotEmpty()) {
                item(key = "check_receipts") {
                    GlassCard {
                        Column(Modifier.padding(14.dp)) {
                            Text("Only your own receipts", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "New receipts are kept only when they match a payment from your bank account, so " +
                                    "purchases someone else in the household paid with their own card are left out. " +
                                    "Check the receipts fetched before for any that don't belong to you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                enabled = !checking,
                                onClick = {
                                    checking = true
                                    scope.launch {
                                        val found = StoreSync.receiptsNotPaidByYou(repository)
                                        checking = false
                                        if (found.isEmpty()) {
                                            Toast.makeText(context, "All receipts match your own payments", Toast.LENGTH_LONG).show()
                                        } else {
                                            notYours = found
                                        }
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text(if (checking) "Checking…" else "Check receipts") }
                        }
                    }
                }
            }
            item {
                Text(
                    "ADD CONNECTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Store.entries.filter { s -> accounts.none { it.store == s } }.forEach { store ->
                item(key = "a_${store.id}") {
                    GlassCard {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StoreTile(store)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(store.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (store.available) store.loginNote else "Coming later — share receipts from the ${store.label} app for now",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (store.available) OutlinedButton(onClick = { onConnect(store, false) }) { Text("Connect") }
                        }
                    }
                }
            }
            item {
                Text(
                    "REMA 1000 brings in only Scan Selv purchases (other REMA receipts aren't digital: photograph them). " +
                        "You sign in on each store's own page; " +
                        "the app keeps the store's sign-in (encrypted on this phone), never your password. Stores can change " +
                        "their systems, so a connection may need signing in again now and then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (consents.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "YOUR CONSENTS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { clearConsents = true }) { Text("Clear") }
                        }
                        GlassCard {
                            consents.sortedByDescending { it.grantedAt }.forEachIndexed { i, consent ->
                                if (i > 0) HorizontalDivider()
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            consent.store.label + if (consent.forAnother) " · another account" else "",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Agreed ${Formatters.syncTimestamp(consent.grantedAt)} · terms v${consent.textVersion}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    managing?.let { account ->
        AlertDialog(
            onDismissRequest = { managing = null },
            title = { Text(account.name) },
            text = {
                Text(
                    receiptCountText(receiptsByStore[account.store.id] ?: 0) + " from ${account.store.label} in the app" +
                        (if (account.lastSyncAt > 0) ", last fetched ${Formatters.syncTimestamp(account.lastSyncAt)}" else "") +
                        ". Receipts are fetched again after every bank sync. When you disconnect, you choose whether to keep or delete the receipts already fetched."
                )
            },
            confirmButton = {
                if (account.problem == "Sign in again") {
                    TextButton(onClick = {
                        managing = null
                        StoreAccounts.remove(context, account.id)
                        onConnect(account.store, false)
                    }) { Text("Sign in again") }
                } else {
                    TextButton(onClick = {
                        managing = null
                        fetchRequested += account.id
                        StoreSync.enqueue(context, account.id)
                        Toast.makeText(context, "Fetching receipts from ${account.store.label}…", Toast.LENGTH_SHORT).show()
                    }) { Text("Fetch now") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    disconnecting = account
                    managing = null
                }) { Text("Disconnect") }
            }
        )
    }

    disconnecting?.let { account ->
        // Receipts are stored per chain, not per account, so they can only be told apart (and
        // offered for deletion) when this is the last account of that store.
        val lastOfStore = accounts.count { it.store == account.store } <= 1
        val close = { disconnecting = null }
        val disconnect = { StoreAccounts.remove(context, account.id) }
        if (lastOfStore) {
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Disconnect ${account.store.label}?") },
                text = { Text("Keep the ${receiptCountText(receiptsByStore[account.store.id] ?: 0)} already fetched, or delete them too? Either way, no more receipts are fetched.") },
                confirmButton = {
                    TextButton(onClick = { close(); disconnect() }) { Text("Keep receipts") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        close()
                        disconnect()
                        scope.launch { repository.deleteReceiptsFromSource(account.store.id) }
                    }) { Text("Delete receipts") }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Disconnect this account?") },
                text = { Text("The fetched receipts stay, because another ${account.store.label} account is still connected and shares them. No more receipts are fetched for this account.") },
                confirmButton = { TextButton(onClick = { close(); disconnect() }) { Text("Disconnect") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
            )
        }
    }

    notYours?.let { found ->
        val total = found.sumOf { it.total }
        AlertDialog(
            onDismissRequest = { notYours = null },
            title = { Text(if (found.size == 1) "1 receipt isn't yours" else "${found.size} receipts aren't yours") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "No payment from your bank account matches ${if (found.size == 1) "it" else "them"} " +
                            "(${Formatters.amount(total)} in all), so they were most likely paid by someone else in your " +
                            "household. Removing them takes them out of your shopping overview; your transactions don't change.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    found.take(12).forEach { r ->
                        Text(
                            "${Formatters.date(r.purchasedAt)} · ${r.storeName} · ${Formatters.amount(r.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (found.size > 12) {
                        Text("… and ${found.size - 12} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    notYours = null
                    scope.launch {
                        found.forEach { repository.deleteReceipt(it.id) }
                        Toast.makeText(
                            context,
                            if (found.size == 1) "Removed 1 receipt" else "Removed ${found.size} receipts",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { notYours = null }) { Text("Keep") } }
        )
    }

    if (clearConsents) {
        AlertDialog(
            onDismissRequest = { clearConsents = false },
            title = { Text("Clear consent records?") },
            text = { Text("This removes the record of when you agreed to each store connection. It doesn't disconnect anything.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConsents = false
                    StoreConsents.clear(context)
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearConsents = false }) { Text("Cancel") } }
        )
    }
}

enum class ConnectStep { INTRO, CONSENT, LOGIN, FINISHING, DONE }

class StoreConnectViewModel(
    private val repository: FinanceRepository,
    private val context: Context,
    val store: Store,
    private val forAnother: Boolean
) : ViewModel() {
    val connector: StoreConnector? = StoreConnector.forStore(store)

    private val _step = MutableStateFlow(ConnectStep.INTRO)
    val step: StateFlow<ConnectStep> = _step
    private val _spent = MutableStateFlow<Double?>(null)
    /** Spent at this chain in the last 12 months, from the bank transactions. */
    val spent: StateFlow<Double?> = _spent
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _emailHint = MutableStateFlow<String?>(null)
    /** The e-mail/phone entered before a Salling login window opens. */
    val emailHint: StateFlow<String?> = _emailHint

    /** The password typed in the app for a store whose page gets it filled in
     * ([StoreConnector.fillPasswordScript]); handed to the login window once, never stored. */
    private var password: String? = null

    /** The last e-mail entered, to fill the form in again after a turned-down sign-in. */
    var lastEmail: String = ""
        private set

    fun setEmailHint(email: String, password: String? = null) {
        if (email.isBlank()) return
        _error.value = null
        lastEmail = email.trim()
        this.password = password?.takeIf { it.isNotEmpty() }
        _emailHint.value = email.trim()
    }

    /** The store's page turned the sign-in down ([StoreConnector.loginErrorScript]): back to the
     * e-mail and password form with the reason. */
    fun loginRejected(reason: String) {
        if (_step.value != ConnectStep.LOGIN) return
        password = null
        _error.value = if (reason == "invalid") "${store.company} says the e-mail or password is wrong. Check them and try again."
            else "${store.company}'s login page says: $reason"
        _emailHint.value = null
    }

    /** The login window got stuck (a blank page, or an address it can't follow): back to the
     * start of the sign-in with what was seen, so it can be reported. */
    fun loginStuck(message: String) {
        if (_step.value != ConnectStep.LOGIN) return
        password = null
        _error.value = message
        if (connector?.needsEmailFirst == true) _emailHint.value = null else _step.value = ConnectStep.CONSENT
    }

    /** The typed password, once; it's forgotten here as soon as the login window has it. */
    fun takePassword(): String? = password.also { password = null }

    override fun onCleared() {
        password = null
    }

    init {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 365L * 86_400_000L
            _spent.value = repository.observeTransactions().first()
                .filter { tx -> tx.type == TransactionType.EXPENSE && tx.date >= since && store.bankWords.any { tx.note.contains(it, ignoreCase = true) } }
                .sumOf { it.amount }
        }
    }

    fun go(step: ConnectStep) {
        _error.value = null
        if (step != ConnectStep.LOGIN) {
            _emailHint.value = null
            password = null
        }
        _step.value = step
    }

    /** The login window got what the store hands back after signing in ([LoginMode.WEBVIEW]). */
    fun captured(value: String) {
        if (_step.value != ConnectStep.LOGIN) return
        _step.value = ConnectStep.FINISHING
        viewModelScope.launch {
            runCatching { connector!!.finishLogin(value) }
                .onSuccess { finishConnected(it) }
                .onFailure {
                    _error.value = "Couldn't finish signing in to ${store.label}: ${it.message ?: "no connection"}"
                    _step.value = ConnectStep.CONSENT
                }
            clearCookies()
        }
    }

    /** The person typed their e-mail and password ([LoginMode.CREDENTIALS]). */
    fun submitCredentials(email: String, password: String) {
        if (_step.value != ConnectStep.LOGIN || email.isBlank() || password.isBlank()) return
        _step.value = ConnectStep.FINISHING
        viewModelScope.launch {
            runCatching { connector!!.loginWithCredentials(email, password) }
                .onSuccess { finishConnected(it) }
                .onFailure {
                    _error.value = (it as? StoreLoginExpired)?.message
                        ?: "Couldn't sign in to ${store.label}: ${it.message ?: "no connection"}"
                    // Back to the form so they can try again.
                    _step.value = ConnectStep.LOGIN
                }
        }
    }

    /** Records the consent as proof, saves the account and starts fetching. */
    private suspend fun finishConnected(secret: String) {
        StoreConsents.record(context, store, forAnother)
        val account = StoreAccounts.add(context, store, secret)
        StoreSync.enqueue(context, account.id)
        _step.value = ConnectStep.DONE
    }

    companion object {
        /** The login window starts signed out (so a second account can sign in) and keeps no
         * store cookies afterwards: what the app keeps is stored encrypted instead. */
        fun clearCookies() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            // Sign-in pages also keep a session in the page's own storage (Salling's login does);
            // left over from another store's login it can stop the next one.
            WebStorage.getInstance().deleteAllData()
        }
    }
}

/** Connecting a store: what you get, what happens (with your consent), then the store's own
 * login page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreConnectScreen(viewModel: StoreConnectViewModel, another: Boolean, onClose: () -> Unit) {
    val step by viewModel.step.collectAsState()
    val spent by viewModel.spent.collectAsState()
    val error by viewModel.error.collectAsState()
    val emailHint by viewModel.emailHint.collectAsState()
    val currency by CurrencySettings.currencyCode.collectAsState()
    val store = viewModel.store
    var agreed by remember { mutableStateOf(false) }

    LaunchedEffect(step) { if (step == ConnectStep.DONE) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            ConnectStep.INTRO -> ""
                            ConnectStep.CONSENT -> "Connect ${store.label}"
                            else -> "${store.company} login"
                        }
                    )
                },
                navigationIcon = {
                    if (step == ConnectStep.CONSENT) {
                        IconButton(onClick = { viewModel.go(ConnectStep.INTRO) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    } else {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    }
                }
            )
        }
    ) { padding ->
        when (step) {
            ConnectStep.INTRO -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StoreTile(store)
                Spacer(Modifier.height(16.dp))
                Text("Connect ${store.label}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                val amount = spent
                if (amount != null && amount > 0) {
                    Text(
                        "You've spent ${Formatters.currency(amount, currency)} at ${store.company} in the last 12 months",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Benefit("See receipts in the app", "Every item, split into your categories")
                Benefit("Budgets that know what you bought", "Food, household and sweets counted separately")
                Benefit("Price changes", "Know when your usual items get more expensive")
                Spacer(Modifier.height(20.dp))
                Button(onClick = { viewModel.go(ConnectStep.CONSENT) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Yes, connect")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
                TextButton(onClick = onClose) { Text("I don't have an account") }
            }
            ConnectStep.CONSENT -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("What happens", style = MaterialTheme.typography.titleLarge)
                Fact("What is fetched", "Your receipts: items, amounts, discounts, store and date. Never payment details or your password.")
                Fact(
                    "How you sign in",
                    if (viewModel.connector?.loginMode == LoginMode.CREDENTIALS) {
                        "You type your ${store.company} e-mail and password in the app. The password is sent once to ${store.company} to sign in and is never stored; the app keeps only the sign-in ${store.company} hands back."
                    } else {
                        "On ${store.company}'s own login page, in a secure window. The app only receives the sign-in that ${store.company} hands back, not your password."
                    }
                )
                Fact("When", "The last 12 months now, then new receipts after each bank sync (about every 6 hours).")
                Fact("Where it's kept", "Only on your phone; the sign-in is encrypted. Disconnect any time in Spending → Shopping → stores.")
                Fact(
                    "Good to know",
                    "This uses ${store.company}'s service unofficially, which their terms don't allow, so it can stop working if they change it."
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                GlassCard {
                    Row(Modifier.clickable { agreed = !agreed }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                        Text(
                            if (another) "I have the account owner's permission, and they want their receipts fetched as described."
                            else "This ${store.label} account is mine, and I want my receipts fetched as described.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Button(
                    onClick = {
                        StoreConnectViewModel.clearCookies()
                        viewModel.go(ConnectStep.LOGIN)
                    },
                    enabled = agreed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue to ${store.company} login")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
            }
            ConnectStep.LOGIN -> if (viewModel.connector?.loginMode == LoginMode.CREDENTIALS) {
                CredentialsLogin(
                    store = store,
                    error = error,
                    onSubmit = viewModel::submitCredentials,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            } else if (viewModel.connector?.needsEmailFirst == true && emailHint == null) {
                // Salling's login page needs the e-mail/phone in the address, so ask for it first,
                // with the password, which is then filled into Salling's own page.
                EmailPrompt(
                    store = store,
                    askPassword = viewModel.connector?.fillPasswordScript("") != null,
                    initialEmail = viewModel.lastEmail,
                    error = error,
                    onSubmit = viewModel::setEmailHint,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            } else Column(Modifier.fillMaxSize().padding(padding)) {
                val connector = viewModel.connector
                if (connector != null) {
                    val password = remember { viewModel.takePassword() }
                    StoreLoginWebView(
                        connector,
                        emailHint = emailHint,
                        password = password,
                        onCaptured = viewModel::captured,
                        onRejected = viewModel::loginRejected,
                        onStuck = viewModel::loginStuck,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = IncomeGreen, modifier = Modifier.size(16.dp))
                    Text(
                        "  This is ${store.company}'s own page. Your password goes only to ${store.company}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ConnectStep.FINISHING, ConnectStep.DONE -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Connecting ${store.label}…", modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun Benefit(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = IncomeGreen, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(title: String, detail: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (title == "Good to know") Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The e-mail/phone, for a store whose own login page needs it in the address before it opens
 * (Salling), and with [askPassword] the password too, which is filled into the store's own page
 * (its password box doesn't take typing well). Otherwise the password is typed on that page. */
@Composable
private fun EmailPrompt(
    store: Store,
    askPassword: Boolean,
    initialEmail: String,
    error: String?,
    onSubmit: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = email.isNotBlank() && (!askPassword || password.isNotEmpty())
    val submit = { if (canSubmit) onSubmit(email, password.takeIf { askPassword }) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoreTile(store)
            Text("Sign in to ${store.company}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            if (askPassword) "Enter the e-mail or phone number and password for your ${store.company} account. ${store.company}'s own login page opens next and signs you in."
            else "Enter the e-mail or phone number for your ${store.company} account. You'll type your password on ${store.company}'s own page next.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail or phone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (askPassword) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        if (askPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to ${store.company} login")
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
        if (askPassword) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = IncomeGreen, modifier = Modifier.size(16.dp))
                Text(
                    "  Your password is put into ${store.company}'s own login page and goes only to ${store.company}. " +
                        "The app never stores it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** E-mail and password for a store whose login page can't be shown in a window (Nemlig.com).
 * The password is sent once to the store to make a session and then forgotten. */
@Composable
private fun CredentialsLogin(store: Store, error: String?, onSubmit: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = email.isNotBlank() && password.isNotBlank()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoreTile(store)
            Text(
                "Sign in with your ${store.company} account",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit(email, password) }),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { onSubmit(email, password) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in")
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = IncomeGreen, modifier = Modifier.size(16.dp))
            Text(
                "  Your password is sent once to ${store.company} to sign in and is never stored. " +
                    "The app keeps only ${store.company}'s sign-in, encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The store's own login page. Nothing is read from the page itself: the app only looks at the
 * address the store sends the browser to at the end, or at the store's login cookie. A [password]
 * typed in the app is put into the page's password box (see [StoreConnector.fillPasswordScript]). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StoreLoginWebView(
    connector: StoreConnector,
    emailHint: String? = null,
    password: String? = null,
    onCaptured: (String) -> Unit,
    onRejected: (String) -> Unit = {},
    onStuck: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Always call the latest callback without rebuilding the WebView — rebuilding it, or recomposing
    // this composable while typing, is what makes the text cursor jump back to the start.
    val captured by rememberUpdatedState(onCaptured)
    val rejected by rememberUpdatedState(onRejected)
    val stuck by rememberUpdatedState(onStuck)
    // The last address the window started to open (host and path), for a stuck-login report.
    val lastPage = remember { mutableStateOf("") }

    fun checkCookies(agent: String?) {
        val url = connector.cookieUrl ?: return
        connector.captureFromCookies(CookieManager.getInstance().getCookie(url), agent ?: "")?.let(captured)
    }

    // One WebView for the whole login, created once and never recreated. This keeps the login
    // form's text input and taps working normally.
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            isFocusableInTouchMode = true
            // The phone's password manager hooks into the page's password box on every key press,
            // which on some phones (Samsung) puts the cursor back at the start. Typing works without it.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    connector.captureFromUrl(url)?.let { captured(it); return true }
                    if (url.startsWith("https://") || url.startsWith("http://")) return false
                    // Never hand the login over to another app; say where it tried to go, so a
                    // store sending its code somewhere unexpected can be fixed.
                    if (!url.startsWith("intent:") && !url.startsWith("about:")) {
                        stuck("${connector.store.company}'s page tried to open ${url.substringBefore('?').take(60)}, which the app can't follow. Please report this message.")
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let { connector.captureFromUrl(it) }?.let(captured)
                    url?.let { lastPage.value = Uri.parse(it).let { u -> "${u.host.orEmpty()}${u.path.orEmpty()}" } }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame != true) return
                    if (connector.captureFromUrl(request.url.toString()) != null) return
                    val where = "${request.url.host.orEmpty()}${request.url.path.orEmpty()}"
                    stuck("${connector.store.company}'s page couldn't open $where (${error?.description ?: "error"}). Please report this message.")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    checkCookies(view?.settings?.userAgentString)
                }
            }
            loadUrl(connector.loginUrl(emailHint))
        }
    }

    // A password typed in the app: put it into the store's page once its login form is showing
    // (for about a minute; after that, or if the page shows something else, it's typed there).
    // Then watch the page: a turned-down sign-in only shows as a small line on it, so the app
    // goes back to its own form with the reason instead of seeming stuck.
    LaunchedEffect(Unit) {
        suspend fun run(script: String): String? = suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { cont.resume(jsString(it)) }
        }
        val fill = password?.let { connector.fillPasswordScript(it) } ?: return@LaunchedEffect
        var filled = false
        var waiting = 0
        for (i in 0 until 120) {
            delay(500)
            if (run(fill) == "done") { filled = true; break }
            // The store's login form never showing (a blank page, or one that didn't load) is
            // reported too, not only a stop after the password went in.
            val at = run(PAGE_STATE_SCRIPT) ?: "${lastPage.value} (no page loaded)"
            waiting = if (at.isEmpty()) 0 else waiting + 1
            if (waiting >= 30 || i == 119) {
                val shown = at.ifEmpty { run(PAGE_STATE_SCRIPT_ALWAYS) ?: lastPage.value }
                stuck("${connector.store.company}'s login form didn't show (at $shown). Please report this message.")
                return@LaunchedEffect
            }
        }
        val check = connector.loginErrorScript()
        if (!filled || check == null) return@LaunchedEffect
        var idle = 0
        val repair = connector.stuckPageScript()
        var repairNote: String? = null
        for (i in 0 until 480) {
            delay(500)
            run(check)?.takeIf { it.isNotEmpty() }?.let { rejected(it); return@LaunchedEffect }
            // Nothing to fill in or press for 15 seconds after signing in: report what the page
            // shows. No answer at all means an error page or a page that never loaded.
            val state = run(PAGE_STATE_SCRIPT)
            val at = state?.let { if (it.isNotEmpty() && repairNote != null) "$it; $repairNote" else it }
                ?: "${lastPage.value} (no page loaded)"
            idle = if (at.isEmpty()) 0 else idle + 1
            // After a few seconds with nothing to do, the store may bring back a hidden step once.
            if (idle == 6 && repair != null && repairNote == null && state != null) {
                repairNote = run(repair) ?: "no answer"
                idle = 0
                continue
            }
            // Still not through a minute after the password went in: report wherever it is,
            // unless the store is asking the person to finish joining (a form they fill in).
            val shown = when {
                idle >= 30 -> at
                i == 120 -> at.ifEmpty { run(PAGE_STATE_SCRIPT_ALWAYS) ?: lastPage.value }
                    .takeUnless { it.contains("complete-registration") }
                else -> null
            }
            if (shown != null) {
                stuck("${connector.store.company}'s page stopped after signing in (at $shown). Please report this message.")
                return@LaunchedEffect
            }
        }
    }

    // Some sites sign in without loading a new page (Nemlig sets a cookie): check now and then.
    LaunchedEffect(Unit) {
        if (connector.cookieUrl != null) {
            while (true) {
                delay(1_500)
                checkCookies(webView.settings.userAgentString)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize())
}
