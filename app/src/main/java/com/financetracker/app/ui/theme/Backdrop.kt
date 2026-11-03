package com.financetracker.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

/** Draws a theme's background: its gradient, soft glows and, for metallic themes, fine brushed-metal
 * lines and a light streak. */
fun Modifier.appBackdrop(palette: AppPalette): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val longest = max(w, h)
    val base = if (palette.background.size == 1) {
        Brush.linearGradient(listOf(palette.background[0], palette.background[0]))
    } else {
        Brush.linearGradient(palette.background, start = Offset(0f, 0f), end = Offset(w * .35f, h))
    }
    val glows = palette.glows.map { glow ->
        Brush.radialGradient(
            listOf(glow.color, glow.color.copy(alpha = 0f)),
            center = Offset(glow.x * w, glow.y * h),
            radius = (glow.radius * longest).coerceAtLeast(1f)
        )
    }
    val brushed = if (palette.isMetallic) {
        Path().apply {
            var y = 0f
            while (y < h) {
                moveTo(0f, y)
                lineTo(w, y)
                y += 3f
            }
        }
    } else {
        null
    }
    val streak = if (palette.isMetallic) {
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = .05f), Color.Transparent),
            start = Offset(w * .2f, 0f), end = Offset(w * .8f, h * .6f)
        )
    } else {
        null
    }
    onDrawBehind {
        drawRect(base)
        glows.forEach { drawRect(it) }
        brushed?.let { drawPath(it, Color.White.copy(alpha = .028f), style = Stroke(width = 1f)) }
        streak?.let { drawRect(it) }
    }
}

/** The AI orb's fill: a lit sphere in the theme's colours. */
fun AppPalette.orbBrush(diameter: Float): Brush = Brush.radialGradient(
    colorStops = orb.mapIndexed { i, c -> (i.toFloat() / (orb.size - 1).coerceAtLeast(1)) to c }.toTypedArray(),
    center = Offset(diameter * .34f, diameter * .28f),
    radius = diameter * .85f
)

/** The accent gradient, left to right. */
fun AppPalette.gradientBrush(): Brush = Brush.horizontalGradient(gradient)
