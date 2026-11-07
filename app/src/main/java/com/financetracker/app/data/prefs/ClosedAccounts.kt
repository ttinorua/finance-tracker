package com.financetracker.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accounts marked "Closed" in Settings > Accounts, e.g. an old main account after moving to a new
 * one. A closed account keeps all its transactions (they still count in totals, trends, the
 * household and the advisor) but is left out of the account pickers and bank sync skips it.
 * "Reopen" undoes it. Kept in settings rather than the database, so it needs no database change,
 * and as plain text so full backups carry it.
 */
object ClosedAccounts {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY = "closed_account_ids"

    private var prefs: SharedPreferences? = null
    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _ids.value = parse(prefs?.getString(KEY, null))
    }

    fun isClosed(accountId: Long?): Boolean = accountId != null && accountId in _ids.value

    fun setClosed(accountId: Long, closed: Boolean) {
        _ids.value = if (closed) _ids.value + accountId else _ids.value - accountId
        prefs?.edit()?.putString(KEY, _ids.value.sorted().joinToString(","))?.apply()
    }

    internal fun parse(raw: String?): Set<Long> = raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
}
