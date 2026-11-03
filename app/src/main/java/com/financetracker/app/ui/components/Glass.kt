package com.financetracker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.financetracker.app.ui.theme.AppPalette
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.gradientBrush
import com.financetracker.app.ui.theme.orbBrush

/** The glass card used across the redesigned screens: the theme's translucent surface with a fine
 * border. Metallic themes get a polished edge (brighter along the top) and a soft top sheen.
 * [aiBorder] draws the border in the AI gradient instead, for the advisor's cards. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    aiBorder: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalAppPalette.current
    val border = when {
        aiBorder -> BorderStroke(1.dp, palette.gradientBrush())
        palette.isMetallic -> BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), palette.border, palette.border))
        )
        else -> BorderStroke(1.dp, palette.border)
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    val sheen = Modifier.drawBehind {
        if (palette.isMetallic) {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .07f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * .45f
                )
            )
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border) {
            Box(Modifier.fillMaxWidth().then(sheen)) { Column(content = content) }
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border) {
            Box(Modifier.fillMaxWidth().then(sheen)) { Column(content = content) }
        }
    }
}

/** The style for a big figure: chrome on metallic themes, the plain text colour otherwise. */
fun AppPalette.figureStyle(): TextStyle =
    if (chrome.size >= 2) TextStyle(brush = Brush.verticalGradient(chrome)) else TextStyle(color = text)

/** The AI orb: a lit sphere in the theme's colours with a sparkle, glowing softly. */
@Composable
fun AiOrb(size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null, onClick: (() -> Unit)? = null) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .size(size)
            .shadow(size / 2.5f, CircleShape, ambientColor = palette.orbGlow, spotColor = palette.orbGlow)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .drawBehind {
                drawRect(palette.orbBrush(this.size.width))
                // A faint inner rim, like light catching the edge of the sphere.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = .28f)),
                        center = Offset(this.size.width / 2, this.size.height / 2),
                        radius = this.size.width / 2
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White.copy(alpha = .95f),
            modifier = Modifier.size(size / 2)
        )
    }
}
