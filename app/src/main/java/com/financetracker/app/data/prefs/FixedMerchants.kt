package com.financetracker.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Merchants the person confirmed as a fixed bill, one by one (from a "Looks monthly, mark as
 * fixed?" suggestion or the Recurring payments list), keyed by
 * [com.financetracker.app.util.advisor.FinanceAnalysis.merchantOf]. Works alongside
 * [FixedExpenseCategories]: a bill counts as fixed when its category is fixed OR its merchant is
 * here, so a Netflix in "Shopping" can be fixed without making all shopping fixed.
 *
 * Kept in the same SharedPreferences file as the other settings, so backups include it.
 */
object FixedMerchants {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_FIXED = "fixed_merchants"

    private lateinit var prefs: android.content.SharedPreferences
    private val _merchants = MutableStateFlow<Set<String>>(emptySet())
    val merchants: StateFlow<Set<String>> get() = _merchants

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _merchants.value = prefs.getStringSet(KEY_FIXED, emptySet()).orEmpty()
    }

    fun setFixed(merchant: String, fixed: Boolean) {
        if (merchant.isBlank()) return
        val updated = if (fixed) _merchants.value + merchant else _merchants.value - merchant
        _merchants.value = updated
        if (::prefs.isInitialized) prefs.edit().putStringSet(KEY_FIXED, updated).apply()
    }
}
