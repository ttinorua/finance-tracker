package com.financetracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.financetracker.app.data.prefs.ThemeSettings

/** The theme in use, for anything the Material colour scheme can't express (the background
 * gradient, the AI orb, the accent gradient, chrome). */
val LocalAppPalette = staticCompositionLocalOf { AppThemes.palette(AppThemes.DEFAULT_STYLE, AppThemes.DEFAULT_COLOUR) }

@Composable
fun PersonalFinanceTheme(content: @Composable () -> Unit) {
    val selection by ThemeSettings.selection.collectAsState()
    val phoneIsDark = isSystemInDarkTheme()
    val palette = remember(selection, phoneIsDark) {
        AppThemes.resolve(selection.style, selection.colour, selection.matchPhone, phoneIsDark)
    }
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = remember(palette) { palette.colorScheme() }, typography = appTypography(palette)) {
            Box(modifier = Modifier.fillMaxSize().appBackdrop(palette)) {
                content()
            }
        }
    }
}

/** The Material colours for [this] theme. Screens and app bars are transparent so the themed
 * background shows through; cards are translucent ("glass"); dialogs and menus stay opaque. */
fun AppPalette.colorScheme() = run {
    val solid = surface.compositeOver(base)
    val raised = (if (isDark) Color.White.copy(alpha = .06f) else Color.Black.copy(alpha = .03f)).compositeOver(solid)
    // Whichever of near-black or white reads better on the accent (e.g. dark text on light blue).
    val ink = Color(0xFF0A0A0A)
    val onAccent = if (contrast(ink, accent) >= contrast(Color.White, accent)) ink else Color.White
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = .22f).compositeOver(solid),
        onPrimaryContainer = text,
        inversePrimary = accent,
        secondary = gradient.last(),
        onSecondary = onAccent,
        secondaryContainer = gradient.last().copy(alpha = .20f).compositeOver(solid),
        onSecondaryContainer = text,
        tertiary = gradient.first(),
        onTertiary = onAccent,
        tertiaryContainer = gradient.first().copy(alpha = .20f).compositeOver(solid),
        onTertiaryContainer = text,
        background = Color.Transparent,
        onBackground = text,
        surface = Color.Transparent,
        onSurface = text,
        // Kept a hair different from the card colour, so text on cards gets the full text colour
        // (Material picks content colours by matching the container colour).
        surfaceVariant = if (surface.alpha < .98f) surface.copy(alpha = surface.alpha + .02f) else text.copy(alpha = .03f).compositeOver(surface),
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = text,
        inverseOnSurface = base,
        error = negative,
        onError = Color.White,
        errorContainer = negative.copy(alpha = .20f).compositeOver(solid),
        onErrorContainer = text,
        outline = border.copy(alpha = (border.alpha * 2.2f).coerceAtMost(1f)),
        outlineVariant = border,
        scrim = Color.Black.copy(alpha = .55f),
        surfaceBright = raised,
        surfaceDim = solid,
        surfaceContainerLowest = solid,
        surfaceContainerLow = solid,
        surfaceContainer = navBar.compositeOver(base),
        surfaceContainerHigh = raised,
        surfaceContainerHighest = surface
    )
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + .05f
    val lb = b.luminance() + .05f
    return maxOf(la, lb) / minOf(la, lb)
}
