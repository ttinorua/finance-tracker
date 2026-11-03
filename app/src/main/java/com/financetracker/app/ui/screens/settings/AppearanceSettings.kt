package com.financetracker.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.prefs.ThemeSettings
import com.financetracker.app.ui.theme.AppPalette
import com.financetracker.app.ui.theme.AppThemes
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.ThemeColour
import com.financetracker.app.ui.theme.ThemeStyle
import com.financetracker.app.ui.theme.appBackdrop
import com.financetracker.app.ui.theme.gradientBrush
import com.financetracker.app.ui.theme.orbBrush

/** Settings > General > Appearance: every theme as a small preview, grouped by style, plus
 * "Match my phone". */
@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val selection by ThemeSettings.selection.collectAsState()
    val current = LocalAppPalette.current
    Column(modifier = modifier) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a theme. Each style comes in the same six colours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp, top = 4.dp)
        )
        ThemeStyle.entries.forEach { style ->
            Text(
                style.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeColour.entries.forEach { colour ->
                    ThemeSwatch(
                        palette = AppThemes.palette(style, colour),
                        selected = selection.style == style && selection.colour == colour,
                        current = current,
                        onClick = { ThemeSettings.setTheme(style, colour) }
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Match my phone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Switch to the Dark or Light theme in the same colour when your phone's dark mode changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = selection.matchPhone, onCheckedChange = ThemeSettings::setMatchPhone, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** A miniature of [palette]: its background, a card, the AI orb and the accent line. */
@Composable
private fun ThemeSwatch(palette: AppPalette, selected: Boolean, current: AppPalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(92.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) current.accent else current.border, shape)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(11.dp))
                .appBackdrop(palette)
        ) {
            Box(
                Modifier
                    .offset(8.dp, 9.dp)
                    .size(34.dp, 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.text.copy(alpha = .85f))
            )
            Box(
                Modifier
                    .offset(8.dp, 21.dp)
                    .size(52.dp, 22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.border, RoundedCornerShape(7.dp))
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset((-7).dp, 7.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .drawBehind { drawRect(palette.orbBrush(size.width)) }
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.gradientBrush())
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                palette.colour.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = current.accent, modifier = Modifier.size(15.dp))
            }
        }
    }
}
