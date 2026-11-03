package com.financetracker.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.financetracker.app.util.MerchantLogos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's own name and logo for a company: "every transaction containing HBO is called
 * HBO Max and shows hbo.com's logo". Wins over the built-in list and the AI lookup.
 *
 * Kept in finance_prefs, so it's part of every full backup and comes back with a restore.
 */
data class MerchantOverride(
    /** Normalised word(s) a transaction's text must contain, e.g. "hbo". */
    val keyword: String,
    /** Name shown in the transaction list; null = automatic. */
    val name: String?,
    /** Website whose logo is shown; null = automatic, "" = no logo (category icon). */
    val domain: String?,
    val updatedAt: Long,
    /** The person's own picture as the logo (a small square image, Base64); wins over [domain]. */
    val photo: String? = null
)

object MerchantOverrides {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY = "merchant_overrides"

    private var prefs: SharedPreferences? = null
    private val _rules = MutableStateFlow<List<MerchantOverride>>(emptyList())
    val rules: StateFlow<List<MerchantOverride>> get() = _rules

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _rules.value = deserialize(prefs?.getString(KEY, null))
    }

    /** Saves the name and logo for every transaction containing [keyword], replacing an earlier
     * choice for the same word. Blank [name] = automatic; [domain] null = automatic, "" = no logo;
     * [photo] = the person's own picture (then [domain] is ignored). */
    fun set(keyword: String, name: String?, domain: String?, photo: String? = null) {
        val key = MerchantLogos.normalize(keyword)
        if (key.isEmpty()) return
        val rule = MerchantOverride(
            key, name?.trim()?.ifEmpty { null }, if (photo != null) null else domain, System.currentTimeMillis(), photo
        )
        save(_rules.value.filter { it.keyword != key } + rule)
    }

    /** Back to automatic for [keyword]. */
    fun remove(keyword: String) {
        val key = MerchantLogos.normalize(keyword)
        save(_rules.value.filter { it.keyword != key })
    }

    private fun save(list: List<MerchantOverride>) {
        _rules.value = list
        prefs?.edit()?.putString(KEY, serialize(list))?.apply()
    }

    /** The rule [description] falls under: the longest matching word wins (so "hbo max" beats
     * "hbo"), then the newest. */
    fun ruleFor(description: String, rules: List<MerchantOverride> = _rules.value): MerchantOverride? =
        best(description, rules) { true }

    /** The person's name for [description], or null to fall back to the automatic one. */
    fun nameFor(description: String, rules: List<MerchantOverride> = _rules.value): String? =
        best(description, rules) { it.name != null }?.name

    /** The person's logo choice for [description]: a domain, "" for no website logo (no logo, or
     * their own photo — see [photoFor]), or null (automatic). */
    fun domainFor(description: String, rules: List<MerchantOverride> = _rules.value): String? =
        logoRule(description, rules)?.let { if (it.photo != null) "" else it.domain }

    /** The person's own picture for [description] (Base64), or null. */
    fun photoFor(description: String, rules: List<MerchantOverride> = _rules.value): String? =
        logoRule(description, rules)?.photo

    private fun logoRule(description: String, rules: List<MerchantOverride>): MerchantOverride? =
        best(description, rules) { it.domain != null || it.photo != null }

    /** Rule keywords are stored normalised, so the description is normalised once here rather
     * than once per rule (this runs for every row and every search). */
    private inline fun best(description: String, rules: List<MerchantOverride>, use: (MerchantOverride) -> Boolean): MerchantOverride? {
        if (rules.isEmpty()) return null
        val padded = " ${MerchantLogos.normalize(description)} "
        return rules.filter { use(it) && " ${it.keyword} " in padded }
            .maxWithOrNull(compareBy<MerchantOverride> { it.keyword.length }.thenBy { it.updatedAt })
    }

    internal fun serialize(list: List<MerchantOverride>): String = JSONArray().apply {
        list.forEach { r ->
            put(
                JSONObject()
                    .put("keyword", r.keyword)
                    .put("name", r.name ?: JSONObject.NULL)
                    .put("domain", r.domain ?: JSONObject.NULL)
                    .put("updatedAt", r.updatedAt)
                    .apply { r.photo?.let { put("photo", it) } }
            )
        }
    }.toString()

    internal fun deserialize(text: String?): List<MerchantOverride> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MerchantOverride(
                // Normalised again on reading, in case the normalisation changed since it was saved.
                keyword = MerchantLogos.normalize(o.getString("keyword")),
                name = if (o.isNull("name")) null else o.getString("name"),
                domain = if (o.isNull("domain")) null else o.getString("domain"),
                updatedAt = o.optLong("updatedAt"),
                photo = if (o.isNull("photo")) null else o.optString("photo").ifEmpty { null }
            )
        }.filter { it.keyword.isNotEmpty() }
    }.getOrDefault(emptyList())
}
