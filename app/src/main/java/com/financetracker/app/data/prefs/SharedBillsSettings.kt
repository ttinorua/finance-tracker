package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.util.BillCadence
import com.financetracker.app.util.Contributor
import com.financetracker.app.util.PartnerBill
import org.json.JSONArray
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
    val answers: Map<String, Contributor> = emptyMap(),
    /** Your recurring card bills ticked as shared (merchant keys). */
    val sharedMerchants: Set<String> = emptySet(),
    /** Bills the partner pays by card, typed in (used until they join the Household). */
    val partnerBills: List<PartnerBill> = emptyList(),
    /** Your share locked in %, or null to follow the incomes. */
    val lockedMySharePct: Double? = null,
    /** When the split was set up; the running balance counts from that month. */
    val startedAt: Long? = null,
    /** When the partner's income was last typed in, for the yearly reminder. */
    val partnerIncomeUpdatedAt: Long? = null,
    /** Targets saved per month ("yyyy-MM" → mine to partner's), so later changes don't rewrite the past. */
    val history: Map<String, Pair<Double, Double>> = emptyMap(),
    /** Share your shared-bill figures with the partner through the Household. */
    val shareWithHousehold: Boolean = true
) {
    val isSetUp: Boolean get() = accountId != null
}

/**
 * Settings for [com.financetracker.app.util.SharedBills]. The partner's net income is typed in
 * here (until their phone shares it through the Household); yours comes from your salary. Kept
 * with the other settings, so backups include it.
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

    fun save(accountId: Long?, partnerName: String, partnerNetIncome: Double, lockedMySharePct: Double?) {
        val old = _setup.value
        val now = System.currentTimeMillis()
        update(
            old.copy(
                accountId = accountId, partnerName = partnerName.trim(), partnerNetIncome = partnerNetIncome,
                lockedMySharePct = lockedMySharePct,
                startedAt = old.startedAt ?: now,
                partnerIncomeUpdatedAt = if (old.partnerNetIncome != partnerNetIncome || old.partnerIncomeUpdatedAt == null) now else old.partnerIncomeUpdatedAt
            )
        )
    }

    fun setShared(merchant: String, shared: Boolean) = update(
        _setup.value.let { it.copy(sharedMerchants = if (shared) it.sharedMerchants + merchant else it.sharedMerchants - merchant) }
    )

    fun savePartnerBill(bill: PartnerBill) = update(
        _setup.value.let { s -> s.copy(partnerBills = s.partnerBills.filterNot { it.id == bill.id } + bill) }
    )

    fun removePartnerBill(id: String) = update(_setup.value.let { s -> s.copy(partnerBills = s.partnerBills.filterNot { it.id == id }) })

    fun clearPartnerBills() = update(_setup.value.copy(partnerBills = emptyList()))

    fun setShareWithHousehold(on: Boolean) = update(_setup.value.copy(shareWithHousehold = on))

    /** Saves [month]'s targets while it's the current month; once the month ends they stay as they were. */
    fun recordMonth(month: String, myTarget: Double, partnerTarget: Double) {
        if (_setup.value.history[month] == (myTarget to partnerTarget)) return
        update(_setup.value.let { it.copy(history = it.history + (month to (myTarget to partnerTarget))) })
    }

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
        .put("shared", JSONArray(s.sharedMerchants.toList()))
        .put("partnerBills", JSONArray().apply {
            s.partnerBills.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("amount", it.amount).put("cadence", it.cadence.name)) }
        })
        .putOpt("locked", s.lockedMySharePct)
        .putOpt("startedAt", s.startedAt)
        .putOpt("partnerIncomeAt", s.partnerIncomeUpdatedAt)
        .put("history", JSONObject().apply { s.history.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } })
        .put("shareWithHousehold", s.shareWithHousehold)
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
            }?.toMap().orEmpty(),
            sharedMerchants = o.optJSONArray("shared")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            partnerBills = o.optJSONArray("partnerBills")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val b = a.getJSONObject(i)
                        PartnerBill(b.getString("id"), b.getString("name"), b.getDouble("amount"), BillCadence.valueOf(b.getString("cadence")))
                    }.getOrNull()
                }
            }.orEmpty(),
            lockedMySharePct = if (o.has("locked")) o.optDouble("locked").takeIf { !it.isNaN() } else null,
            startedAt = if (o.has("startedAt")) o.optLong("startedAt") else null,
            partnerIncomeUpdatedAt = if (o.has("partnerIncomeAt")) o.optLong("partnerIncomeAt") else null,
            history = o.optJSONObject("history")?.let { h ->
                h.keys().asSequence().mapNotNull { k -> runCatching { h.getJSONArray(k).let { k to (it.getDouble(0) to it.getDouble(1)) } }.getOrNull() }.toMap()
            }.orEmpty(),
            shareWithHousehold = o.optBoolean("shareWithHousehold", true)
        )
    }
}
