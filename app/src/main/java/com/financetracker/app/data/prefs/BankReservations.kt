package com.financetracker.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Card payments the bank has reserved but not booked yet (Enable Banking "PDNG" dated in the last
 * two weeks), from the latest sync, per local account. Like [BankScheduledPayments] they never touch
 * balances or totals; they're only used to recognise a store receipt as paid from the person's own
 * account before the payment books (receipts from a shared store account that someone else in the
 * household paid for are left out). Replaced wholesale on every successful sync.
 */
object BankReservations {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY = "bank_reservations"
    private const val KEY_UPDATED_AT = "bank_reservations_updated_at"
    private const val KEY_SEEN_ANY = "bank_reservations_seen_any"

    private lateinit var prefs: android.content.SharedPreferences
    private val _reservations = MutableStateFlow<List<ScheduledPayment>>(emptyList())
    val reservations: StateFlow<List<ScheduledPayment>> get() = _reservations

    /** When the list was last refreshed by a bank sync (0 = never, e.g. before this was added). */
    var updatedAt: Long = 0L
        private set

    /** Whether the bank has ever reported a reservation. Not every bank shares them; until one has,
     * a missing reservation says nothing about whether a purchase was paid from this account. */
    var seenAny: Boolean = false
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _reservations.value = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()
        updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        seenAny = prefs.getBoolean(KEY_SEEN_ANY, false)
    }

    fun replaceForAccount(accountId: Long, reservations: List<ScheduledPayment>) {
        _reservations.update { current -> current.filterNot { it.accountId == accountId } + reservations.distinct() }
        updatedAt = System.currentTimeMillis()
        if (reservations.isNotEmpty()) seenAny = true
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY, encode(_reservations.value))
                .putLong(KEY_UPDATED_AT, updatedAt).putBoolean(KEY_SEEN_ANY, seenAny).apply()
        }
    }

    fun onAccountRemoved(accountId: Long) {
        _reservations.update { current -> current.filterNot { it.accountId == accountId } }
        if (::prefs.isInitialized) prefs.edit().putString(KEY, encode(_reservations.value)).apply()
    }

    private fun encode(list: List<ScheduledPayment>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("accountId", p.accountId).put("date", p.date).put("amount", p.amount).put("note", p.note))
        }
    }.toString()

    private fun decode(json: String): List<ScheduledPayment> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ScheduledPayment(o.getLong("accountId"), o.getLong("date"), o.getDouble("amount"), o.getString("note"), null)
        }
    }
}
