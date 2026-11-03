package com.financetracker.app.data.stores

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A record of each time the person agreed to connect a store, kept as proof that the fetching was
 * consented to (time, store, which version of the consent text, own account or a partner's). The
 * log is append-only and stays even after the account is disconnected; it holds no sign-in and
 * nothing personal. Only the person can clear it, in Settings.
 */
object StoreConsents {
    private const val PREFS = "store_consents"
    private const val KEY = "consents"

    private val _consents = MutableStateFlow<List<StoreConsent>>(emptyList())
    val consents: StateFlow<List<StoreConsent>> = _consents
    @Volatile private var loaded = false

    fun load(context: Context): List<StoreConsent> {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    _consents.value = read(context)
                    loaded = true
                }
            }
        }
        return _consents.value
    }

    /** Adds a record that [store] was connected now. */
    fun record(context: Context, store: Store, forAnother: Boolean, textVersion: Int = Store.CONSENT_VERSION) {
        val consent = StoreConsent(store, System.currentTimeMillis(), textVersion, forAnother)
        save(context, load(context) + consent)
    }

    /** Removes the whole consent log (the person clears their records). */
    fun clear(context: Context) {
        save(context, emptyList())
    }

    @Synchronized
    private fun save(context: Context, list: List<StoreConsent>) {
        val array = JSONArray()
        list.forEach { c ->
            array.put(
                JSONObject()
                    .put("store", c.store.id)
                    .put("grantedAt", c.grantedAt)
                    .put("textVersion", c.textVersion)
                    .put("forAnother", c.forAnother)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        _consents.value = list
    }

    private fun read(context: Context): List<StoreConsent> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val store = Store.byId(o.getString("store")) ?: return@mapNotNull null
                StoreConsent(store, o.optLong("grantedAt"), o.optInt("textVersion"), o.optBoolean("forAnother"))
            }
        }.getOrDefault(emptyList())
    }
}
