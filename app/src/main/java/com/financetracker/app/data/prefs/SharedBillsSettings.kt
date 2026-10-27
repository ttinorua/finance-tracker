package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.util.Contributor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** The shared bills setup: which account pays the household bills, who the partner is and what
 * they earn after tax, and the answers to "Who sent this?". */
data class SharedBillsSetup(
    val accountId: Long? = null,
    val partnerName: String = "",
    val partnerNetIncome: Double = 0.0,
    /** Sender (see [com.financetracker.app.util.SharedBills.senderKey]) → who it is. */
    val answers: Map<String, Contributor> = emptyMap()
) {
    val isSetUp: Boolean get() = accountId != null
}

/**
 * Settings for [com.financetracker.app.util.SharedBills]. The partner doesn't use the app, so
 * their net income is typed in here; yours comes from your salary. Kept with the other settings,
 * so backups include it.
 */
object SharedBillsSettings {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY = "shared_bills"

    private lateinit var prefs: android.content.SharedPreferences
    private val _setup = MutableStateFlow(SharedBillsSetup())
    val setup: StateFlow<SharedBillsSetup> get() = _setup

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _setup.value = runCatching { decode(prefs.getString(KEY, null)) }.getOrNull() ?: SharedBillsSetup()
    }

    fun save(accountId: Long?, partnerName: String, partnerNetIncome: Double) =
        update(_setup.value.copy(accountId = accountId, partnerName = partnerName.trim(), partnerNetIncome = partnerNetIncome))

    fun answer(senderKey: String, who: Contributor) =
        update(_setup.value.copy(answers = _setup.value.answers + (senderKey to who)))

    /** A deleted account clears the setup's account; a merged one moves to [replacementId]. */
    fun onAccountRemoved(accountId: Long, replacementId: Long? = null) {
        if (_setup.value.accountId == accountId) update(_setup.value.copy(accountId = replacementId))
    }

    private fun update(value: SharedBillsSetup) {
        _setup.value = value
        if (::prefs.isInitialized) prefs.edit().putString(KEY, encode(value)).apply()
    }

    private fun encode(s: SharedBillsSetup): String = JSONObject()
        .put("accountId", s.accountId ?: -1L)
        .put("partnerName", s.partnerName)
        .put("partnerNetIncome", s.partnerNetIncome)
        .put("answers", JSONObject().apply { s.answers.forEach { (k, v) -> put(k, v.name) } })
        .toString()

    private fun decode(raw: String?): SharedBillsSetup? {
        val o = JSONObject(raw ?: return null)
        val answers = o.optJSONObject("answers")
        return SharedBillsSetup(
            accountId = o.optLong("accountId", -1L).takeIf { it >= 0 },
            partnerName = o.optString("partnerName"),
            partnerNetIncome = o.optDouble("partnerNetIncome", 0.0),
            answers = answers?.keys()?.asSequence()?.mapNotNull { k ->
                runCatching { k to Contributor.valueOf(answers.getString(k)) }.getOrNull()
            }?.toMap().orEmpty()
        )
    }
}
