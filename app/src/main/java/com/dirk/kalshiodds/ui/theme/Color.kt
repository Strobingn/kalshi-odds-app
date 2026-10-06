package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Classic palette. Chrome tokens (not red or green) are the 0.3.23 colors
 * (tag v0.3.23-debug, commit cfe5f7ba). UP greens and DOWN reds are the
 * deeper 0.3.36 set: about 20% lower HSL lightness on fills, containers,
 * and chart lines. Text-on-dark stops at the WCAG AA floor so it stays
 * readable on #21262D. FieldOps lives in [FieldSwatch] and is opt-in.
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
/** UP text on dark surfaces. Deepest green that still clears 4.5:1 on #21262D. */
val UpColor = Color(0xFF37A146)
/** UP button, chip, and chart line. Deeper than [UpColor]. Near-black label stays AA. */
val UpButton = Color(0xFF329440)
val UpContainer = Color(0xFF102616)
/**
 * DOWN text on #21262D. 0.3.27 already sat on the AA floor; this is one
 * step deeper. Scorecard losses and negative P&L use it, so it cannot
 * go further without failing 4.5:1.
 */
val DownColor = Color(0xFFF85048)
/** DOWN price and AI % on the maroon tile. Deeper than [DownColor], still AA there. */
val DownInk = Color(0xFFDE4439)
/** Paper DOWN / buy DOWN fill and DOWN chart line. White label. */
val DownButton = Color(0xFF9E2020)
/** Darker maroon behind the DOWN tile. */
val DownContainer = Color(0xFF160808)
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
val LightUpColor = Color(0xFF0E4F21)
val LightUpContainer = Color(0xFF9DD2B1)
/** White label on the button still clears 4.5:1. */
val LightDownColor = Color(0xFF721111)
val LightDownButton = Color(0xFF921616)
/** Deeper rose. DOWN text on this wash still clears 4.5:1. */
val LightDownContainer = Color(0xFFE7868E)
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
