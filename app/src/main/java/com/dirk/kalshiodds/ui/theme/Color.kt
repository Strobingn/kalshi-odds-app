package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Classic palette. Non-red tokens are the 0.3.23 colors (tag v0.3.23-debug,
 * commit cfe5f7ba). DOWN / danger reds are the deeper 0.3.27 set.
 * FieldOps lives in [FieldSwatch] and is opt-in.
 *
 * Composables must read [DipTheme.colors]. Green = buying UP. Red = buying DOWN.
 */
val Bg = Color(0xFF0D1117)
val Surface = Color(0xFF161B22)
val SurfaceAlt = Color(0xFF21262D)
val Border = Color(0xFF30363D)
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)
val AccentBlue = Color(0xFF58A6FF)
val UpColor = Color(0xFF3FB950)
val UpContainer = Color(0xFF14301C)
/**
 * Brightest Classic dark red that still clears 4.5:1 on #21262D.
 * Scorecard losses and negative P&L sit on that gray, so this token
 * cannot move further down without failing AA.
 */
val DownColor = Color(0xFFF85149)
/** DOWN price and AI % on the maroon tile. Deeper than [DownColor], still AA there. */
val DownInk = Color(0xFFE04E44)
/** Paper DOWN / buy DOWN fill. White label. */
val DownButton = Color(0xFFC62828)
/** Darker maroon behind the DOWN tile. */
val DownContainer = Color(0xFF1C0A0A)
val AccentOrange = Color(0xFFD29922)
val AccentRed = DownColor
val OnAccentDark = Color(0xFF0D1117)
val OnUpDark = OnAccentDark
val OnDownDark = OnAccentDark
val OnDownButton = Color(0xFFFFFFFF)

val LightBg = Color(0xFFF6F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceAlt = Color(0xFFEEF2F6)
val LightBorder = Color(0xFFD0D7DE)
val LightTextPrimary = Color(0xFF1F2328)
val LightTextSecondary = Color(0xFF57606A)
val LightAccentBlue = Color(0xFF0550AE)
val LightUpColor = Color(0xFF116329)
val LightUpContainer = Color(0xFFDCEFE3)
/** Deeper than 0.3.23 #A0111F. White label on the button still clears 4.5:1. */
val LightDownColor = Color(0xFF8E1515)
val LightDownButton = Color(0xFFB71C1C)
/** Slightly deeper rose than 0.3.23 #F8D6D9. Secondary labels stay AA. */
val LightDownContainer = Color(0xFFF6D2D5)
val LightAccentOrange = Color(0xFF7D4E00)
val LightAccentRed = LightDownColor
val OnAccentLight = Color(0xFFFFFFFF)
val OnUpLight = OnAccentLight
val OnDownLight = OnAccentLight

val GradientStartDark = Color(0xFF58A6FF)
val GradientEndDark = Color(0xFF1F6FEB)
val GradientStartLight = Color(0xFF0550AE)
val GradientEndLight = Color(0xFF0969DA)

object ColorStyles {
    const val CLASSIC = "classic"
    const val FIELDOPS = "fieldops"

    fun normalize(raw: String?): String =
        if (raw.equals(FIELDOPS, ignoreCase = true)) FIELDOPS else CLASSIC

    fun isFieldOps(raw: String?): Boolean = normalize(raw) == FIELDOPS
}
