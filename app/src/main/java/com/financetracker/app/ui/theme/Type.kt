package com.financetracker.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.financetracker.app.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Sora: the clean, techy display face used for titles and figures. */
val Sora = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.sora, it) })

/** Playfair Display: the elegant serif the gold themes use for titles and figures. */
val PlayfairDisplay = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.playfair_display, it) })

@Composable
fun appTypography(palette: AppPalette): Typography = remember(palette.serifDisplay) {
    val display = if (palette.serifDisplay) PlayfairDisplay else Sora
    Typography(
        displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = 18.sp),
        titleSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
    )
}
