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

    private lateinit var prefs: android.content.SharedPreferences
    private val _reservations = MutableStateFlow<List<ScheduledPayment>>(emptyList())
    val reservations: StateFlow<List<ScheduledPayment>> get() = _reservations

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _reservations.value = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()
    }

    fun replaceForAccount(accountId: Long, reservations: List<ScheduledPayment>) {
        _reservations.update { current -> current.filterNot { it.accountId == accountId } + reservations.distinct() }
        if (::prefs.isInitialized) prefs.edit().putString(KEY, encode(_reservations.value)).apply()
    }

    fun onAccountRemoved(accountId: Long) = replaceForAccount(accountId, emptyList())

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
