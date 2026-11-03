package com.financetracker.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.financetracker.app.ui.theme.AppThemes
import com.financetracker.app.ui.theme.ThemeColour
import com.financetracker.app.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class AppThemesTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return max(la, lb).toDouble() / min(la, lb)
    }

    private val all = ThemeStyle.entries.flatMap { s -> ThemeColour.entries.map { c -> AppThemes.palette(s, c) } }

    @Test
    fun `every style comes in every colour`() {
        assertEquals(24, all.size)
        all.forEach { assertTrue(it.orb.size >= 2 && it.gradient.size >= 2 && it.background.isNotEmpty()) }
        assertTrue(all.filter { it.style == ThemeStyle.METALLIC }.all { it.chrome.size >= 2 })
    }

    @Test
    fun `the default is Dark Cobalt`() {
        assertEquals(ThemeStyle.DARK, AppThemes.DEFAULT_STYLE)
        assertEquals(ThemeColour.COBALT, AppThemes.DEFAULT_COLOUR)
    }

    @Test
    fun `text is readable on every theme's background and cards`() {
        val problems = mutableListOf<String>()
        all.forEach { p ->
            (p.background + p.surface.compositeOver(p.base)).forEach { bg ->
                val name = "${p.style} ${p.colour}"
                if (contrast(p.text, bg) < 7.0) problems += "$name text ${contrast(p.text, bg)}"
                if (contrast(p.muted, bg) < 3.0) problems += "$name muted ${contrast(p.muted, bg)}"
                if (contrast(p.accent, bg) < 2.5) problems += "$name accent ${contrast(p.accent, bg)}"
            }
        }
        assertTrue(problems.distinct().joinToString("; "), problems.isEmpty())
    }

    @Test
    fun `match my phone swaps to the partner theme in the same colour`() {
        // Off: always the chosen theme.
        assertEquals(ThemeStyle.DARK, AppThemes.resolve(ThemeStyle.DARK, ThemeColour.COBALT, false, phoneIsDark = false).style)
        // On: a dark theme on a light-mode phone becomes Light in the same colour, and the reverse.
        val day = AppThemes.resolve(ThemeStyle.METALLIC, ThemeColour.GOLD, true, phoneIsDark = false)
        assertEquals(ThemeStyle.LIGHT, day.style)
        assertEquals(ThemeColour.GOLD, day.colour)
        assertEquals(ThemeStyle.DARK, AppThemes.resolve(ThemeStyle.PASTEL, ThemeColour.SILVER, true, phoneIsDark = true).style)
        // On, already matching: unchanged.
        assertEquals(ThemeStyle.PASTEL, AppThemes.resolve(ThemeStyle.PASTEL, ThemeColour.SILVER, true, phoneIsDark = false).style)
    }
}
