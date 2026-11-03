package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.ui.theme.AppThemes
import com.financetracker.app.ui.theme.ThemeColour
import com.financetracker.app.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The theme chosen in Settings > General > Appearance. With [matchPhone], the app switches to the
 * Dark or Light theme of the same colour when the phone's dark mode doesn't match. */
data class ThemeSelection(
    val style: ThemeStyle = AppThemes.DEFAULT_STYLE,
    val colour: ThemeColour = AppThemes.DEFAULT_COLOUR,
    val matchPhone: Boolean = false
)

/** Read by [com.financetracker.app.ui.theme.PersonalFinanceTheme]. */
object ThemeSettings {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_STYLE = "theme_style"
    private const val KEY_COLOUR = "theme_colour"
    private const val KEY_MATCH_PHONE = "theme_match_phone"

    private lateinit var prefs: android.content.SharedPreferences
    private val _selection = MutableStateFlow(ThemeSelection())
    val selection: StateFlow<ThemeSelection> get() = _selection

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _selection.value = ThemeSelection(
            style = prefs.getString(KEY_STYLE, null)?.let { runCatching { ThemeStyle.valueOf(it) }.getOrNull() } ?: AppThemes.DEFAULT_STYLE,
            colour = prefs.getString(KEY_COLOUR, null)?.let { runCatching { ThemeColour.valueOf(it) }.getOrNull() } ?: AppThemes.DEFAULT_COLOUR,
            matchPhone = prefs.getBoolean(KEY_MATCH_PHONE, false)
        )
    }

    fun setTheme(style: ThemeStyle, colour: ThemeColour) {
        _selection.value = _selection.value.copy(style = style, colour = colour)
        if (::prefs.isInitialized) prefs.edit().putString(KEY_STYLE, style.name).putString(KEY_COLOUR, colour.name).apply()
    }

    fun setMatchPhone(enabled: Boolean) {
        _selection.value = _selection.value.copy(matchPhone = enabled)
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_MATCH_PHONE, enabled).apply()
    }
}
