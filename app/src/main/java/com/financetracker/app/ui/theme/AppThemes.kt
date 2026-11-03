package com.financetracker.app.ui.theme

import androidx.compose.ui.graphics.Color

/** The four looks the app comes in. Light and Pastel are light themes; Dark and Metallic are dark. */
enum class ThemeStyle(val label: String, val isDark: Boolean) {
    DARK("Dark", true),
    LIGHT("Light", false),
    PASTEL("Pastel", false),
    METALLIC("Metallic", true)
}

/** The colour range every [ThemeStyle] comes in. */
enum class ThemeColour(val label: String) {
    AURORA("Aurora"),
    EMERALD("Emerald"),
    GOLD("Gold"),
    BLACK("Black"),
    SILVER("Silver"),
    COBALT("Cobalt")
}

/** A soft radial light drawn on the background: centre as a fraction of the screen, radius as a
 * fraction of its longest side. */
data class Glow(val x: Float, val y: Float, val radius: Float, val color: Color)

/** Everything a theme defines. Screens get it from [LocalAppPalette]; the Material colour scheme is
 * derived from it (see [PersonalFinanceTheme]). */
data class AppPalette(
    val style: ThemeStyle,
    val colour: ThemeColour,
    /** Background gradient, top-left to bottom-right (one colour = flat). */
    val background: List<Color>,
    val glows: List<Glow>,
    /** Card fill — translucent, so the background shows through like frosted glass. */
    val surface: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    /** The AI accent gradient (AI insight, orb ring, highlights). */
    val gradient: List<Color>,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    /** The AI orb: highlight to edge. */
    val orb: List<Color>,
    val orbGlow: Color,
    val navBar: Color,
    /** Metallic themes: brushed-metal lines and a chrome gradient for big figures. */
    val chrome: List<Color> = emptyList(),
    /** Uses the serif display font (the gold themes) rather than Sora. */
    val serifDisplay: Boolean = false
) {
    val isDark: Boolean get() = style.isDark
    val isMetallic: Boolean get() = style == ThemeStyle.METALLIC
    /** The colour behind everything, for opaque surfaces (dialogs, menus). */
    val base: Color get() = background.first()
}

private fun hex(value: Long) = Color(value)
private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).toInt())
private val White = Color.White

object AppThemes {

    val DEFAULT_STYLE = ThemeStyle.DARK
    val DEFAULT_COLOUR = ThemeColour.COBALT

    fun palette(style: ThemeStyle, colour: ThemeColour): AppPalette = when (style) {
        ThemeStyle.DARK -> dark(colour)
        ThemeStyle.LIGHT -> light(colour)
        ThemeStyle.PASTEL -> pastel(colour)
        ThemeStyle.METALLIC -> metallic(colour)
    }

    /** The theme to show: the chosen one, or — with "Match my phone" — its Dark or Light partner
     * in the same colour when the phone's dark mode doesn't match the chosen theme. */
    fun resolve(style: ThemeStyle, colour: ThemeColour, matchPhone: Boolean, phoneIsDark: Boolean): AppPalette {
        val shown = when {
            !matchPhone || style.isDark == phoneIsDark -> style
            phoneIsDark -> ThemeStyle.DARK
            else -> ThemeStyle.LIGHT
        }
        return palette(shown, colour)
    }

    // ------------------------------------------------------------------ Dark

    private fun dark(colour: ThemeColour): AppPalette {
        val s = ThemeStyle.DARK
        return when (colour) {
            ThemeColour.AURORA -> AppPalette(s, colour, listOf(hex(0xFF07080F)),
                listOf(Glow(0.85f, 0.08f, 0.42f, rgba(124, 92, 255, .35f)), Glow(0.05f, 0.70f, 0.40f, rgba(34, 211, 238, .18f))),
                rgba(255, 255, 255, .045f), rgba(255, 255, 255, .09f), hex(0xFFEEF0FF), hex(0xFF8C91AB), hex(0xFF9B87FF),
                listOf(hex(0xFF7C5CFF), hex(0xFF22D3EE)), hex(0xFF34D399), hex(0xFFFB7185), hex(0xFFFBBF24),
                listOf(White, hex(0xFFC4B5FD), hex(0xFF7C5CFF), hex(0xFF22D3EE)), rgba(124, 92, 255, .65f), rgba(14, 15, 26, .92f))
            ThemeColour.EMERALD -> AppPalette(s, colour, listOf(hex(0xFF040907)),
                listOf(Glow(0.80f, 0.0f, 0.45f, rgba(16, 185, 129, .28f))),
                rgba(12, 32, 24, .55f), rgba(52, 211, 153, .20f), hex(0xFFE6FFF4), hex(0xFF7FA595), hex(0xFF34D399),
                listOf(hex(0xFF10B981), hex(0xFF22D3EE)), hex(0xFF34D399), hex(0xFFF87171), hex(0xFFFBBF24),
                listOf(hex(0xFFECFDF5), hex(0xFF6EE7B7), hex(0xFF10B981), hex(0xFF0E7490)), rgba(16, 185, 129, .6f), rgba(6, 16, 12, .94f))
            ThemeColour.GOLD -> AppPalette(s, colour, listOf(hex(0xFF0B0A09)),
                listOf(Glow(0.90f, 0.0f, 0.40f, rgba(233, 196, 106, .16f))),
                hex(0xFF15130F), rgba(233, 196, 106, .16f), hex(0xFFF5EFE4), hex(0xFF9E9586), hex(0xFFE9C46A),
                listOf(hex(0xFFF3D98B), hex(0xFFB8860B)), hex(0xFF8FD3A6), hex(0xFFE88B7D), hex(0xFFE9C46A),
                listOf(hex(0xFFFFF8E1), hex(0xFFF3D98B), hex(0xFFC9A227), hex(0xFF6B4F12)), rgba(233, 196, 106, .45f), rgba(17, 15, 12, .94f),
                serifDisplay = true)
            ThemeColour.BLACK -> AppPalette(s, colour, listOf(hex(0xFF000000)),
                listOf(Glow(0.85f, 0.0f, 0.42f, rgba(255, 255, 255, .10f))),
                rgba(255, 255, 255, .045f), rgba(255, 255, 255, .11f), hex(0xFFFAFAFA), hex(0xFF8C8C8C), White,
                listOf(White, hex(0xFF7A7A7A)), hex(0xFF4ADE80), hex(0xFFF87171), hex(0xFFFACC15),
                listOf(White, hex(0xFFDADADA), hex(0xFF5A5A5A), hex(0xFF0A0A0A)), rgba(255, 255, 255, .28f), rgba(12, 12, 12, .94f))
            ThemeColour.SILVER -> AppPalette(s, colour, listOf(hex(0xFF0C0D0F)),
                listOf(Glow(0.85f, 0.0f, 0.44f, rgba(214, 220, 230, .20f)), Glow(0.0f, 0.80f, 0.40f, rgba(180, 188, 200, .08f))),
                rgba(255, 255, 255, .05f), rgba(210, 216, 226, .17f), hex(0xFFEEF0F3), hex(0xFF9298A3), hex(0xFFD9DEE6),
                listOf(White, hex(0xFF9AA3AE)), hex(0xFF7DD3A8), hex(0xFFF19999), hex(0xFFE5C07B),
                listOf(White, hex(0xFFE5E8EC), hex(0xFF9AA3AE), hex(0xFF4B5260)), rgba(210, 216, 226, .35f), rgba(20, 21, 24, .94f))
            ThemeColour.COBALT -> AppPalette(s, colour, listOf(hex(0xFF060A14)),
                listOf(Glow(0.85f, 0.06f, 0.44f, rgba(59, 130, 246, .34f)), Glow(0.05f, 0.72f, 0.40f, rgba(56, 189, 248, .14f))),
                rgba(255, 255, 255, .045f), rgba(130, 170, 240, .17f), hex(0xFFEEF4FF), hex(0xFF8E9CB8), hex(0xFF7AA8FF),
                listOf(hex(0xFF3B82F6), hex(0xFF38BDF8)), hex(0xFF34D399), hex(0xFFFB7185), hex(0xFFFBBF24),
                listOf(White, hex(0xFFCFE1FF), hex(0xFF3B82F6), hex(0xFF1E3A8A)), rgba(59, 130, 246, .6f), rgba(10, 16, 30, .94f))
        }
    }

    // ------------------------------------------------------------------ Light

    private fun light(colour: ThemeColour): AppPalette {
        val s = ThemeStyle.LIGHT
        val nav = rgba(255, 255, 255, .94f)
        return when (colour) {
            ThemeColour.AURORA -> AppPalette(s, colour, listOf(hex(0xFFF6F5FF)),
                listOf(Glow(0.85f, 0.06f, 0.42f, rgba(124, 92, 255, .20f)), Glow(0.05f, 0.75f, 0.40f, rgba(34, 211, 238, .14f))),
                rgba(255, 255, 255, .80f), rgba(109, 74, 255, .14f), hex(0xFF16142C), hex(0xFF6B6889), hex(0xFF6D4AFF),
                listOf(hex(0xFF6D4AFF), hex(0xFF0EA5C6)), hex(0xFF059669), hex(0xFFE11D48), hex(0xFFD97706),
                listOf(White, hex(0xFFC4B5FD), hex(0xFF7C5CFF), hex(0xFF22D3EE)), rgba(124, 92, 255, .45f), nav)
            ThemeColour.EMERALD -> AppPalette(s, colour, listOf(hex(0xFFF2FAF6)),
                listOf(Glow(0.80f, 0.0f, 0.45f, rgba(16, 185, 129, .16f))),
                rgba(255, 255, 255, .85f), rgba(16, 185, 129, .22f), hex(0xFF052E1C), hex(0xFF4E6E60), hex(0xFF059669),
                listOf(hex(0xFF059669), hex(0xFF0891B2)), hex(0xFF059669), hex(0xFFDC2626), hex(0xFFD97706),
                listOf(hex(0xFFECFDF5), hex(0xFF6EE7B7), hex(0xFF10B981), hex(0xFF0E7490)), rgba(16, 185, 129, .4f), nav)
            ThemeColour.GOLD -> AppPalette(s, colour, listOf(hex(0xFFFAF7F0)),
                listOf(Glow(0.90f, 0.0f, 0.40f, rgba(233, 196, 106, .22f))),
                White, rgba(168, 123, 22, .20f), hex(0xFF1E1A14), hex(0xFF7A705F), hex(0xFFA87B16),
                listOf(hex(0xFFD4A72C), hex(0xFF8A6410)), hex(0xFF2F855A), hex(0xFFC0392B), hex(0xFFA87B16),
                listOf(hex(0xFFFFF8E1), hex(0xFFF3D98B), hex(0xFFC9A227), hex(0xFF6B4F12)), rgba(201, 162, 39, .35f), nav,
                serifDisplay = true)
            ThemeColour.BLACK -> AppPalette(s, colour, listOf(hex(0xFFFAFAFA)),
                listOf(Glow(0.85f, 0.0f, 0.42f, rgba(0, 0, 0, .04f))),
                White, hex(0xFFE4E4E4), hex(0xFF0A0A0A), hex(0xFF6B6B6B), hex(0xFF0A0A0A),
                listOf(hex(0xFF0A0A0A), hex(0xFF6B6B6B)), hex(0xFF15803D), hex(0xFFDC2626), hex(0xFFB45309),
                listOf(White, hex(0xFFC4C4C4), hex(0xFF333333), hex(0xFF000000)), rgba(0, 0, 0, .28f), nav)
            ThemeColour.SILVER -> AppPalette(s, colour, listOf(hex(0xFFF1F2F4)),
                listOf(Glow(0.85f, 0.0f, 0.45f, rgba(255, 255, 255, .9f))),
                White, hex(0xFFDCDFE4), hex(0xFF15171B), hex(0xFF6B7280), hex(0xFF4B5563),
                listOf(hex(0xFF9CA3AF), hex(0xFF374151)), hex(0xFF059669), hex(0xFFDC2626), hex(0xFFB45309),
                listOf(White, hex(0xFFE5E7EB), hex(0xFF9CA3AF), hex(0xFF4B5563)), rgba(75, 85, 99, .3f), nav)
            ThemeColour.COBALT -> AppPalette(s, colour, listOf(hex(0xFFF3F6FC)),
                listOf(Glow(0.85f, 0.04f, 0.42f, rgba(59, 130, 246, .18f)), Glow(0.05f, 0.75f, 0.40f, rgba(14, 165, 233, .10f))),
                rgba(255, 255, 255, .85f), rgba(37, 99, 235, .16f), hex(0xFF0B1B3A), hex(0xFF5A6B88), hex(0xFF2563EB),
                listOf(hex(0xFF2563EB), hex(0xFF0EA5E9)), hex(0xFF059669), hex(0xFFE11D48), hex(0xFFD97706),
                listOf(White, hex(0xFFCFE1FF), hex(0xFF3B82F6), hex(0xFF1E3A8A)), rgba(37, 99, 235, .4f), nav)
        }
    }

    // ------------------------------------------------------------------ Pastel

    private fun pastel(colour: ThemeColour): AppPalette {
        fun p(
            bg: List<Long>, g1: Color, g2: Color, border: Color, text: Long, muted: Long, accent: Long,
            grad: List<Long>, orb: List<Long>, glow: Color, warn: Long = 0xFFD9922E
        ) = AppPalette(
            ThemeStyle.PASTEL, colour, bg.map(::hex), listOf(Glow(0.92f, 0.06f, 0.40f, g1), Glow(0.0f, 0.65f, 0.42f, g2)),
            rgba(255, 255, 255, .80f), border, hex(text), hex(muted), hex(accent), grad.map(::hex),
            hex(0xFF2F9E74), hex(0xFFE25C82), hex(warn), orb.map(::hex), glow, rgba(255, 255, 255, .94f)
        )
        return when (colour) {
            ThemeColour.AURORA -> p(listOf(0xFFF6F2FF, 0xFFEFF3FF, 0xFFE9FAFF), rgba(185, 168, 255, .45f), rgba(143, 216, 240, .40f),
                rgba(150, 130, 230, .22f), 0xFF2A2745, 0xFF8681A6, 0xFF8B7CF6, listOf(0xFFB09CFF, 0xFF7FD3EE),
                listOf(0xFFFFFFFF, 0xFFE6DEFF, 0xFFB9A8FF, 0xFF9FE3F2), rgba(185, 168, 255, .7f))
            ThemeColour.EMERALD -> p(listOf(0xFFEFFBF5, 0xFFEDF9F6, 0xFFEAF6FB), rgba(155, 232, 200, .50f), rgba(143, 216, 224, .35f),
                rgba(60, 180, 140, .22f), 0xFF1F3A30, 0xFF6E8C80, 0xFF2FA57C, listOf(0xFF86E2BE, 0xFF86D2E0),
                listOf(0xFFFFFFFF, 0xFFD7F8EA, 0xFF9BE8C8, 0xFF8FD8E0), rgba(120, 220, 180, .7f))
            ThemeColour.GOLD -> p(listOf(0xFFFFF9EC, 0xFFFFF4E9, 0xFFFDF0EE), rgba(255, 217, 154, .55f), rgba(247, 183, 154, .38f),
                rgba(220, 170, 90, .26f), 0xFF3A2E1E, 0xFF85765E, 0xFFC98F25, listOf(0xFFFFD38A, 0xFFF5AE90),
                listOf(0xFFFFFFFF, 0xFFFFF0CC, 0xFFFFD99A, 0xFFF7B79A), rgba(255, 205, 130, .75f), warn = 0xFFC98F25)
            ThemeColour.BLACK -> p(listOf(0xFFF7F7F6, 0xFFF1F0EF, 0xFFECECEE), rgba(200, 200, 205, .45f), rgba(225, 222, 218, .6f),
                rgba(0, 0, 0, .08f), 0xFF2B2B2E, 0xFF76767B, 0xFF4A4A50, listOf(0xFFA6A6AC, 0xFF4A4A50),
                listOf(0xFFFFFFFF, 0xFFECECEE, 0xFFBDBDC3, 0xFF7C7C84), rgba(120, 120, 130, .45f))
            ThemeColour.SILVER -> p(listOf(0xFFF7F8FA, 0xFFEFF1F5, 0xFFEBEEF3), rgba(205, 212, 224, .65f), rgba(220, 226, 236, .7f),
                rgba(110, 120, 140, .16f), 0xFF272B33, 0xFF717884, 0xFF6F7A8C, listOf(0xFFD3D9E2, 0xFF8E97A6),
                listOf(0xFFFFFFFF, 0xFFF1F3F6, 0xFFC9CFD8, 0xFF9AA3B0), rgba(150, 160, 175, .5f))
            ThemeColour.COBALT -> p(listOf(0xFFF1F6FF, 0xFFEEF3FF, 0xFFEAF7FF), rgba(169, 196, 255, .55f), rgba(143, 211, 245, .40f),
                rgba(90, 130, 220, .22f), 0xFF1F2A44, 0xFF7A88A6, 0xFF5B84E8, listOf(0xFFA3BFFF, 0xFF86CFF2),
                listOf(0xFFFFFFFF, 0xFFDCE8FF, 0xFFA9C4FF, 0xFF8FD3F5), rgba(150, 185, 255, .7f))
        }
    }

    // ------------------------------------------------------------------ Metallic

    private fun metallic(colour: ThemeColour): AppPalette {
        fun m(
            bg: List<Long>, glow: Color, tint: Color, border: Color, text: Long, muted: Long, accent: Long,
            grad: List<Long>, chrome: List<Long>, orb: List<Long>, orbGlow: Color
        ) = AppPalette(
            ThemeStyle.METALLIC, colour, bg.map(::hex), listOf(Glow(0.80f, -0.10f, 0.55f, glow)),
            tint, border, hex(text), hex(muted), hex(accent), grad.map(::hex),
            hex(0xFF86E3BA), hex(0xFFFFA0A6), hex(0xFFF3CB80), orb.map(::hex), orbGlow, rgba(20, 22, 26, .92f),
            chrome = chrome.map(::hex)
        )
        return when (colour) {
            ThemeColour.AURORA -> m(listOf(0xFF2B2346, 0xFF151228, 0xFF08070F), rgba(170, 150, 255, .32f), rgba(200, 185, 255, .10f),
                rgba(185, 170, 250, .34f), 0xFFF2EFFF, 0xFFA49EC6, 0xFFC4B7FF, listOf(0xFFF1ECFF, 0xFF8E7CF0, 0xFFD6EEFF, 0xFF3FA9C9),
                listOf(0xFFFFFFFF, 0xFFE4DDFF, 0xFF9A8BE0, 0xFFEAE5FF), listOf(0xFFFFFFFF, 0xFFECE6FF, 0xFFA895FF, 0xFF3B2E80), rgba(160, 140, 255, .55f))
            ThemeColour.EMERALD -> m(listOf(0xFF17322B, 0xFF0B1A16, 0xFF050C0A), rgba(110, 230, 180, .32f), rgba(170, 240, 210, .10f),
                rgba(140, 225, 190, .32f), 0xFFECFFF6, 0xFF93B8A8, 0xFF9DF0CC, listOf(0xFFE6FFF4, 0xFF4FCB98, 0xFFD2FBEB, 0xFF1E8A6A),
                listOf(0xFFFFFFFF, 0xFFD6FBEA, 0xFF6FC9A2, 0xFFE2FCEF), listOf(0xFFFFFFFF, 0xFFDBFBEC, 0xFF7DE3B5, 0xFF14593F), rgba(100, 220, 170, .5f))
            ThemeColour.GOLD -> m(listOf(0xFF372C17, 0xFF1C160C, 0xFF0C0A06), rgba(240, 200, 120, .32f), rgba(255, 225, 160, .10f),
                rgba(240, 205, 130, .36f), 0xFFFFF7E6, 0xFFBBA982, 0xFFF3D98B, listOf(0xFFFFF8E1, 0xFFD9AE4A, 0xFFFCEBC0, 0xFF8A6410),
                listOf(0xFFFFFDF5, 0xFFF7E3A8, 0xFFB88A2E, 0xFFFBEBC0), listOf(0xFFFFFFFF, 0xFFFFF3CF, 0xFFE9C46A, 0xFF7A5A12), rgba(233, 196, 106, .5f))
            ThemeColour.BLACK -> m(listOf(0xFF27282B, 0xFF121315, 0xFF040405), rgba(230, 230, 235, .32f), rgba(255, 255, 255, .09f),
                rgba(220, 220, 225, .26f), 0xFFF4F4F5, 0xFF9C9CA3, 0xFFE4E4E7, listOf(0xFFFFFFFF, 0xFF8A8A92, 0xFFE4E4E7, 0xFF3F3F46),
                listOf(0xFFFFFFFF, 0xFFD4D4D8, 0xFF71717A, 0xFFE4E4E7), listOf(0xFFFFFFFF, 0xFFE4E4E7, 0xFF8A8A92, 0xFF18181B), rgba(230, 230, 235, .32f))
            ThemeColour.SILVER -> m(listOf(0xFF2A2D33, 0xFF17191D, 0xFF0D0E10), rgba(235, 240, 247, .30f), rgba(255, 255, 255, .11f),
                rgba(214, 220, 229, .34f), 0xFFF2F4F7, 0xFFA3AAB5, 0xFFE3E7EC, listOf(0xFFFFFFFF, 0xFFB9C0CA, 0xFFF4F6F8, 0xFF8A93A0),
                listOf(0xFFFFFFFF, 0xFFE6E9ED, 0xFF9AA2AD, 0xFFEEF0F3), listOf(0xFFFFFFFF, 0xFFF1F3F5, 0xFFB8BFC9, 0xFF6C7480), rgba(225, 230, 238, .45f))
            ThemeColour.COBALT -> m(listOf(0xFF1C2C48, 0xFF0E1728, 0xFF070B15), rgba(125, 175, 255, .34f), rgba(170, 200, 255, .11f),
                rgba(150, 188, 245, .36f), 0xFFEEF4FF, 0xFF97A9C8, 0xFF9CC2FF, listOf(0xFFE8F1FF, 0xFF6E9DF0, 0xFFCFE0FF, 0xFF3A61B6),
                listOf(0xFFFFFFFF, 0xFFDCE8FF, 0xFF7DA3E2, 0xFFE6EFFF), listOf(0xFFFFFFFF, 0xFFE3EEFF, 0xFF8DB4F7, 0xFF2B4F99), rgba(110, 160, 255, .55f))
        }
    }
}
