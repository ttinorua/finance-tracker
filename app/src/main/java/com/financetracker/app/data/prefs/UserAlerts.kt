package com.financetracker.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AlertKind {
    /** A category's spending this month goes over [UserAlert.limit]. */
    CATEGORY_LIMIT,
    /** Any single payment of at least [UserAlert.limit]. */
    LARGE_PAYMENT
}

/** An alert the person asked for (usually through the AI advisor). Checked by the app itself with
 * the other "Needs your attention" checks, so it never uses the AI. */
data class UserAlert(
    val id: String = UUID.randomUUID().toString(),
    val kind: AlertKind,
    val limit: Double,
    /** CATEGORY_LIMIT: the category (and its name, for showing it if the category goes away). */
    val categoryId: Long? = null,
    val categoryName: String? = null,
    /** null = all accounts. */
    val accountId: Long? = null,
    /** CATEGORY_LIMIT: also warn at this share of the limit (e.g. 80), or null for no early warning. */
    val warnAtPercent: Int? = 80,
    val enabled: Boolean = true
)

object UserAlerts {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_ALERTS = "user_alerts"

    private var prefs: android.content.SharedPreferences? = null
    private val _alerts = MutableStateFlow<List<UserAlert>>(emptyList())
    val alerts: StateFlow<List<UserAlert>> = _alerts.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _alerts.value = runCatching { decode(p.getString(KEY_ALERTS, null)) }.getOrDefault(emptyList())
    }

    fun save(alert: UserAlert) = update(_alerts.value.filterNot { it.id == alert.id } + alert)
    fun remove(id: String) = update(_alerts.value.filterNot { it.id == id })
    fun setEnabled(id: String, enabled: Boolean) = update(_alerts.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    private fun update(list: List<UserAlert>) {
        _alerts.value = list
        prefs?.edit()?.putString(KEY_ALERTS, encode(list))?.apply()
    }

    /** "Takeaway over 1,000 kr" — how an alert is named in lists and on cards. */
    fun describe(alert: UserAlert, money: (Double) -> String): String = when (alert.kind) {
        AlertKind.CATEGORY_LIMIT -> "${alert.categoryName ?: "Category"} over ${money(alert.limit)}"
        AlertKind.LARGE_PAYMENT -> "Any payment over ${money(alert.limit)}"
    }

    internal fun encode(list: List<UserAlert>): String = JSONArray().apply {
        list.forEach { a ->
            put(
                JSONObject().put("id", a.id).put("kind", a.kind.name).put("limit", a.limit)
                    .put("categoryId", a.categoryId ?: JSONObject.NULL).put("categoryName", a.categoryName ?: JSONObject.NULL)
                    .put("accountId", a.accountId ?: JSONObject.NULL).put("warnAt", a.warnAtPercent ?: JSONObject.NULL)
                    .put("enabled", a.enabled)
            )
        }
    }.toString()

    internal fun decode(raw: String?): List<UserAlert> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val kind = runCatching { AlertKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            UserAlert(
                id = o.getString("id"),
                kind = kind,
                limit = o.getDouble("limit"),
                categoryId = if (o.isNull("categoryId")) null else o.getLong("categoryId"),
                categoryName = if (o.isNull("categoryName")) null else o.getString("categoryName"),
                accountId = if (o.isNull("accountId")) null else o.getLong("accountId"),
                warnAtPercent = if (o.isNull("warnAt")) null else o.getInt("warnAt"),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}
