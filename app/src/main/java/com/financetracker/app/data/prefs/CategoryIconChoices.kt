package com.financetracker.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Icons the user picked for categories, by category id. Categories without an entry use the
 * icon guessed from their name ([com.financetracker.app.util.CategoryIconRules]). Kept in
 * SharedPreferences rather than a Category column for the same reason as [FixedExpenseCategories]
 * (a Room schema change would wipe the database); being in finance_prefs, it's part of full
 * backups too.
 */
object CategoryIconChoices {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_ICONS = "category_icons"

    private var prefs: android.content.SharedPreferences? = null
    private val _icons = MutableStateFlow<Map<Long, String>>(emptyMap())
    val icons: StateFlow<Map<Long, String>> get() = _icons

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Stored as "id=key" strings.
        _icons.value = prefs?.getStringSet(KEY_ICONS, emptySet()).orEmpty().mapNotNull { entry ->
            val id = entry.substringBefore('=').toLongOrNull() ?: return@mapNotNull null
            id to entry.substringAfter('=').ifBlank { return@mapNotNull null }
        }.toMap()
    }

    /** Sets [categoryId]'s icon, or goes back to the guessed one when [key] is null. */
    fun set(categoryId: Long, key: String?) {
        val updated = if (key == null) _icons.value - categoryId else _icons.value + (categoryId to key)
        _icons.value = updated
        prefs?.edit()?.putStringSet(KEY_ICONS, updated.map { (id, k) -> "$id=$k" }.toSet())?.apply()
    }
}
