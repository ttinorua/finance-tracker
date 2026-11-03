package com.financetracker.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import com.financetracker.app.BuildConfig
import com.financetracker.app.data.ai.chatgpt.ChatGptAuth
import com.financetracker.app.data.backup.DeviceSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The AIs the app can use, in their default fallback order. ChatGPT runs on the person's own
 * ChatGPT plan through "Sign in with ChatGPT" ([ChatGptAuth]) instead of a key. */
enum class AiProvider(val label: String, val free: Boolean) {
    GEMINI("Gemini", free = true),
    GROQ("Groq", free = true),
    CHATGPT("ChatGPT", free = false),
    CLAUDE("Claude", free = false);

    /** False for ChatGPT, which is connected with a sign-in rather than a key. */
    val usesKey: Boolean get() = this != CHATGPT

    /** The key built into the app for this AI (empty if the build has none). */
    val builtInKey: String
        get() = when (this) {
            GEMINI -> BuildConfig.GEMINI_API_KEY
            GROQ -> BuildConfig.GROQ_API_KEY
            CHATGPT -> ""
            CLAUDE -> BuildConfig.ANTHROPIC_API_KEY
        }
}

data class AiSettingsState(
    val primary: AiProvider = AiProvider.GEMINI,
    val ownKeys: Set<AiProvider> = emptySet(),
    /** The ChatGPT account whose plan the app may use, or null when ChatGPT isn't connected. */
    val chatGptEmail: String? = null
) {
    fun hasOwnKey(provider: AiProvider): Boolean = provider.usesKey && provider in ownKeys
    fun hasBuiltInKey(provider: AiProvider): Boolean = provider.builtInKey.isNotBlank()
    /** Whether [provider] can be used: a key for the others, a connected sign-in for ChatGPT. */
    fun hasKey(provider: AiProvider): Boolean =
        if (provider.usesKey) hasOwnKey(provider) || hasBuiltInKey(provider) else chatGptEmail != null

    /** The order AI requests are tried in: [primary] first, then the rest in their default order,
     * leaving out any AI with no key. */
    val chain: List<AiProvider> get() = aiChain(primary, ::hasKey)
}

/** [primary] first, then the other AIs in their default order, keeping only those [hasKey] allows. */
fun aiChain(primary: AiProvider, hasKey: (AiProvider) -> Boolean): List<AiProvider> =
    (listOf(primary) + AiProvider.entries.filter { it != primary }).filter(hasKey)

/**
 * Which AI the app tries first (Settings > General > AI assistant) and each person's own API keys.
 *
 * Gemini and Groq both have free tiers, and a build can include a shared free key for each
 * (GEMINI_API_KEY, GROQ_API_KEY) so AI works out of the box for everyone the app is shared with.
 * Claude is paid, so it's normally each person's own key, entered here. When the first AI fails
 * (its free limit is used up, it's down, …) the next one in [AiSettingsState.chain] takes over.
 *
 * Keys entered in the app are stored encrypted with this phone's Android Keystore key, so they
 * never leave the phone (and are left out of backups).
 */
object AiSettings {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_PRIMARY = "ai_provider"
    private fun ownKeyPref(provider: AiProvider) = "ai_own_${provider.name.lowercase()}_key"

    /** Phone-specific secrets, never written into a backup. */
    val SECRET_KEYS: Set<String> = AiProvider.entries.map(::ownKeyPref).toSet() + ChatGptAuth.SECRET_KEYS

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(AiSettingsState())
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        refresh()
    }

    /** Re-reads the settings; also called when ChatGPT is connected or disconnected. */
    internal fun refresh() {
        if (!::prefs.isInitialized) return
        val ownKeys = AiProvider.entries.filter { prefs.contains(ownKeyPref(it)) }.toSet()
        // With no choice made yet, a build that includes a Claude key keeps using Claude first (as
        // it did before this setting existed); anything else starts on the free Gemini.
        val default = if (AiProvider.CLAUDE in ownKeys || AiProvider.CLAUDE.builtInKey.isNotBlank()) {
            AiProvider.CLAUDE
        } else {
            AiProvider.GEMINI
        }
        _state.value = AiSettingsState(
            primary = prefs.getString(KEY_PRIMARY, null)
                ?.let { name -> AiProvider.entries.firstOrNull { it.name == name } }
                ?: default,
            ownKeys = ownKeys,
            chatGptEmail = ChatGptAuth.connectedEmail
        )
    }

    val chain: List<AiProvider> get() = _state.value.chain

    fun setPrimary(provider: AiProvider) {
        prefs.edit().putString(KEY_PRIMARY, provider.name).apply()
        refresh()
    }

    /** Saves (or with a blank [key], removes) the user's own key for [provider]. */
    fun setOwnKey(provider: AiProvider, key: String?) {
        val trimmed = key?.trim().orEmpty()
        prefs.edit().apply {
            if (trimmed.isEmpty()) {
                remove(ownKeyPref(provider))
            } else {
                putString(ownKeyPref(provider), DeviceSecret.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
            }
        }.commit()
        refresh()
    }

    /** The key to use for [provider]: the user's own if set, otherwise the one built into the app.
     * For ChatGPT a placeholder while it's connected (its sign-in supplies the real token). */
    fun keyFor(provider: AiProvider): String? {
        if (!provider.usesKey) return if (ChatGptAuth.isConnected) "chatgpt-plan" else null
        val own = if (::prefs.isInitialized) {
            prefs.getString(ownKeyPref(provider), null)?.let { DeviceSecret.decrypt(it) }?.let { String(it, Charsets.UTF_8) }
        } else {
            null
        }
        return own?.takeIf { it.isNotBlank() } ?: provider.builtInKey.takeIf { it.isNotBlank() }
    }
}
