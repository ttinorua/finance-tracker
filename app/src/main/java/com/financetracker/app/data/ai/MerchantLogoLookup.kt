package com.financetracker.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.financetracker.app.data.prefs.MerchantOverride
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.util.MerchantLogos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds the website of merchants the built-in list in [MerchantLogos] doesn't know, by asking the
 * AI chain, and remembers each answer on the phone so a merchant is only ever asked about once.
 *
 * Rows ask via [request] as they scroll into view; requests are gathered for a moment and sent
 * as one batched question, so opening a long list costs a few AI calls, not one per row. A failed
 * call isn't saved, so those merchants are tried again on the next app start.
 */
object MerchantLogoLookup {
    private const val PREFS_NAME = "merchant_logos"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_NAMES = "names"
    private const val BATCH_SIZE = 40

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private val askedThisSession = HashSet<String>()
    private var batchJob: Job? = null

    /** Merchant key → domain; "" means the AI found no company (no logo). */
    private val _domains = MutableStateFlow<Map<String, String>>(emptyMap())
    val domains: StateFlow<Map<String, String>> get() = _domains

    /** Merchant key → short company name ("McDonald's"); "" means none (the bank's text is shown). */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> get() = _names

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Answers saved before card codes like "MCD" were ignored were looked up with the code in
        // the key (and often came back as McDonald's); drop them so they're asked again cleanly.
        val stale = { key: String -> key.split(' ').any { it in STALE_WORDS } }
        _domains.value = readMap(KEY_DOMAINS).filterKeys { !stale(it) }
        _names.value = readMap(KEY_NAMES).filterKeys { !stale(it) }
    }

    private val STALE_WORDS = setOf("mcd", "mc", "vd", "debit")

    private fun readMap(key: String): Map<String, String> = runCatching {
        val json = JSONObject(prefs.getString(key, null) ?: "{}")
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    /** The company domain for a transaction description, or null when there's no logo (yet).
     * The person's own choice ([MerchantOverrides]) comes first. */
    fun domainFor(
        description: String,
        cache: Map<String, String> = _domains.value,
        overrides: List<MerchantOverride> = MerchantOverrides.rules.value
    ): String? {
        MerchantOverrides.domainFor(description, overrides)?.let { return it.ifBlank { null } }
        return MerchantLogos.knownDomain(description)
            ?: MerchantLogos.merchantKey(description)?.let { cache[it] }?.ifBlank { null }
    }

    /** The short company name to show for a transaction description, or null to show the bank's
     * text: the person's own name first, then the built-in list, then the AI's answer. */
    fun nameFor(
        description: String,
        cache: Map<String, String> = _names.value,
        overrides: List<MerchantOverride> = MerchantOverrides.rules.value
    ): String? =
        MerchantOverrides.nameFor(description, overrides)
            ?: MerchantLogos.knownName(description)
            ?: MerchantLogos.merchantKey(description)?.let { cache[it] }?.ifBlank { null }

    /** Queues an AI lookup for [description] unless it's already known, cached or in flight. */
    fun request(description: String) {
        if (!AiService.isConfigured) return
        if (MerchantLogos.knownDomain(description) != null && MerchantLogos.knownName(description) != null) return
        val key = MerchantLogos.merchantKey(description) ?: return
        synchronized(lock) {
            // Merchants looked up before names were added only have a domain; ask once more.
            if ((key in _domains.value && key in _names.value) || !askedThisSession.add(key)) return
            pending += key
            if (batchJob == null) {
                batchJob = scope.launch {
                    delay(800) // let the visible rows all queue up first
                    drain()
                }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            // Clearing batchJob under the same lock as request() means a merchant queued just
            // as this finishes always starts a fresh batch instead of being stranded.
            val batch = synchronized(lock) {
                pending.take(BATCH_SIZE).also {
                    pending.removeAll(it.toSet())
                    if (it.isEmpty()) batchJob = null
                }
            }
            if (batch.isEmpty()) return
            val reply = AiService.ask(SYSTEM_PROMPT, JSONArray(batch).toString(), maxTokens = 2048L, quick = true)
            val (domains, names) = reply.getOrNull()?.let { MerchantLogos.parseLookupReply(it, batch) } ?: continue
            save(domains, names)
        }
    }

    private fun save(domains: Map<String, String>, names: Map<String, String>) {
        val updatedDomains = _domains.value + domains
        val updatedNames = _names.value + names
        _domains.value = updatedDomains
        _names.value = updatedNames
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_DOMAINS, JSONObject(updatedDomains).toString())
                .putString(KEY_NAMES, JSONObject(updatedNames).toString())
                .apply()
        }
    }

    private const val SYSTEM_PROMPT =
        "You identify companies from bank transaction descriptions (mostly Danish). You get a JSON " +
            "array of descriptions. For each, give the company's main website domain, e.g. \"netto.dk\", " +
            "and its short everyday name as people write it, e.g. \"McDonald's\", \"HBO Max\", \"Netto\". " +
            "Codes at the start like MCD, MC, VD, Visa or Dankort are the card type, not the company. " +
            "Use null for both for private persons, transfers between accounts, or when you are not sure. " +
            "Reply with only a JSON object mapping each description, exactly as given, to an object " +
            "{\"domain\": string or null, \"name\": string or null}."
}
