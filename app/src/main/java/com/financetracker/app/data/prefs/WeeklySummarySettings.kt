package com.financetracker.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the AI writes a short summary of the week every Sunday evening (Home card and a
 * phone notification). On by default; one short AI request a week. */
object WeeklySummarySettings {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_ENABLED = "weekly_summary_enabled"

    private var prefs: android.content.SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private var onChange: ((Boolean) -> Unit)? = null

    fun init(context: Context, onChange: (Boolean) -> Unit) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        this.onChange = onChange
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        onChange?.invoke(on)
    }
}
