package com.financetracker.app.data.stores

import android.content.Context
import com.financetracker.app.data.backup.DeviceSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The connected store accounts. The list is kept in app storage with every account's sign-in
 * encrypted by a key in this phone's Android Keystore; nothing is sent anywhere but the store. */
object StoreAccounts {
    private const val PREFS = "store_accounts"
    private const val KEY = "accounts"

    private val _accounts = MutableStateFlow<List<StoreAccount>>(emptyList())
    val accounts: StateFlow<List<StoreAccount>> = _accounts
    @Volatile private var loaded = false

    fun load(context: Context): List<StoreAccount> {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    _accounts.value = read(context)
                    loaded = true
                }
            }
        }
        return _accounts.value
    }

    fun get(context: Context, id: String): StoreAccount? = load(context).firstOrNull { it.id == id }

    fun add(context: Context, store: Store, secret: String): StoreAccount {
        val existing = load(context).filter { it.store == store }
        val account = StoreAccount(
            id = UUID.randomUUID().toString(),
            store = store,
            name = if (existing.isEmpty()) "Your ${store.label} account" else "${store.label} account ${existing.size + 1}",
            secret = secret,
            connectedAt = System.currentTimeMillis()
        )
        save(context, load(context) + account)
        return account
    }

    fun update(context: Context, id: String, change: (StoreAccount) -> StoreAccount) {
        save(context, load(context).map { if (it.id == id) change(it) else it })
    }

    /** Makes every account fetch its last 12 months again on the next fetch. Used after a backup
     * restore: the restore replaces the receipts but not the store connections, so without this
     * the accounts would only look back from their last fetch and miss older receipts. */
    fun resetFetchDates(context: Context) {
        save(context, withFetchDatesReset(load(context)))
    }

    internal fun withFetchDatesReset(list: List<StoreAccount>): List<StoreAccount> =
        list.map { it.copy(lastSyncAt = 0L, lastFetchAdded = 0) }

    fun remove(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    @Synchronized
    private fun save(context: Context, list: List<StoreAccount>) {
        val array = JSONArray()
        list.forEach { a ->
            array.put(
                JSONObject()
                    .put("id", a.id)
                    .put("store", a.store.id)
                    .put("name", a.name)
                    .put("secret", DeviceSecret.encrypt(a.secret.toByteArray(Charsets.UTF_8)))
                    .put("connectedAt", a.connectedAt)
                    .put("lastSyncAt", a.lastSyncAt)
                    .put("lastReceiptAt", a.lastReceiptAt)
                    .put("receiptCount", a.receiptCount)
                    .put("lastFetchAdded", a.lastFetchAdded)
                    .put("problem", a.problem ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        _accounts.value = list
    }

    private fun read(context: Context): List<StoreAccount> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val store = Store.byId(o.getString("store")) ?: return@mapNotNull null
                // A secret this phone can't decrypt (e.g. restored onto another phone) means
                // signing in again.
                val secret = DeviceSecret.decrypt(o.getString("secret"))?.toString(Charsets.UTF_8)
                StoreAccount(
                    id = o.getString("id"),
                    store = store,
                    name = o.optString("name", store.label),
                    secret = secret.orEmpty(),
                    connectedAt = o.optLong("connectedAt"),
                    lastSyncAt = o.optLong("lastSyncAt"),
                    lastReceiptAt = o.optLong("lastReceiptAt"),
                    receiptCount = o.optInt("receiptCount"),
                    lastFetchAdded = o.optInt("lastFetchAdded"),
                    problem = if (secret == null) "Sign in again" else o.optString("problem").takeUnless { o.isNull("problem") || it.isEmpty() }
                )
            }
        }.getOrDefault(emptyList())
    }
}
