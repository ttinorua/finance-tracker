package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.util.PeriodOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The account and period you're looking at, shared by Home, Activity and Spending (Trends
 * follows the account): pick "Bills account · Last month" on one tab and every other tab shows
 * the same. Remembered when the app is closed. Until something is picked it starts on the main
 * account (Settings > Accounts) and this month; changing the main account moves the view to it.
 *
 * Screens read and set these directly; every change is saved in the background.
 */
object ViewFilter {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_ACCOUNT = "view_filter_account"
    private const val KEY_PERIOD = "view_filter_period"
    private const val KEY_CUSTOM = "view_filter_custom_range"
    private const val ALL_ACCOUNTS = "all"

    /** null = All accounts. */
    val accountId = MutableStateFlow<Long?>(null)
    val period = MutableStateFlow(PeriodOption.THIS_MONTH)
    /** [start, endExclusive) when [period] is CUSTOM. */
    val customRange = MutableStateFlow<Pair<Long, Long>?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        accountId.value = when (val stored = prefs.getString(KEY_ACCOUNT, null)) {
            null -> MainAccountSettings.mainAccountId.value
            ALL_ACCOUNTS -> null
            else -> stored.toLongOrNull()
        }
        period.value = prefs.getString(KEY_PERIOD, null)
            ?.let { runCatching { PeriodOption.valueOf(it) }.getOrNull() } ?: PeriodOption.THIS_MONTH
        customRange.value = prefs.getString(KEY_CUSTOM, null)?.split(":")?.takeIf { it.size == 2 }
            ?.let { (a, b) -> a.toLongOrNull()?.let { start -> b.toLongOrNull()?.let { end -> start to end } } }
        if (period.value == PeriodOption.CUSTOM && customRange.value == null) period.value = PeriodOption.THIS_MONTH

        scope.launch {
            combine(accountId, period, customRange) { a, p, c -> Triple(a, p, c) }.drop(1).collect { (a, p, c) ->
                prefs.edit()
                    .putString(KEY_ACCOUNT, a?.toString() ?: ALL_ACCOUNTS)
                    .putString(KEY_PERIOD, p.name)
                    .apply { if (c == null) remove(KEY_CUSTOM) else putString(KEY_CUSTOM, "${c.first}:${c.second}") }
                    .apply()
            }
        }
        scope.launch { MainAccountSettings.mainAccountId.drop(1).collect { accountId.value = it } }
    }

    fun selectCustomRange(start: Long, endExclusive: Long) {
        customRange.value = start to endExclusive
        period.value = PeriodOption.CUSTOM
    }

    /** A deleted or merged account: the view moves to [replacementId], or to All accounts. */
    fun onAccountRemoved(removedAccountId: Long, replacementId: Long? = null) {
        if (accountId.value == removedAccountId) accountId.value = replacementId
    }
}
