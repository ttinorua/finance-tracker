package com.financetracker.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financetracker.app.ui.components.AiOrb
import com.financetracker.app.ui.theme.LocalAppPalette

/** The rounded glass bar along the bottom: two destinations either side of the raised AI orb,
 * which opens the advisor. */
@Composable
fun FloatingNavBar(
    items: List<Screen>,
    isSelected: (Screen) -> Boolean,
    onSelect: (Screen) -> Unit,
    onOpenAi: () -> Unit
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(24.dp)
    val half = (items.size + 1) / 2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(top = 20.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .background(palette.navBar)
                .border(
                    1.dp,
                    if (palette.isMetallic) {
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), palette.border))
                    } else {
                        Brush.linearGradient(listOf(palette.border, palette.border))
                    },
                    shape
                ),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, screen ->
                if (i == half) Spacer(Modifier.width(56.dp))
                NavItem(screen, isSelected(screen), Modifier.weight(1f)) { onSelect(screen) }
            }
        }
        AiOrb(
            50.dp,
            Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            contentDescription = "AI advisor",
            onClick = onOpenAi
        )
    }
}

@Composable
private fun NavItem(screen: Screen, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            screen.icon,
            contentDescription = null,
            tint = if (selected) palette.accent else palette.muted,
            modifier = Modifier.size(22.dp)
        )
        Text(
            screen.label,
            fontSize = 10.5.sp,
            color = if (selected) palette.text else palette.muted,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
