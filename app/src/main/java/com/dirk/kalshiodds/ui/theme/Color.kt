package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw palette tokens. Composables must read [DipTheme.colors], not these
 * dark-only vals. Importing [Bg] / [TextSecondary] / [UpColor] from
 * here is what made light mode unreadable: scaffolds stayed `#0D1117`
 * while [androidx.compose.material3.MaterialTheme] `onBackground` flipped
 * to [LightTextPrimary] (`#1F2328`).
 *
 * Green = buying UP. Red = buying DOWN. Containers are the tile fills.
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
val DownColor = Color(0xFFF85149)
val DownContainer = Color(0xFF301616)
val AccentOrange = Color(0xFFD29922)
val AccentRed = DownColor
val OnAccentDark = Color(0xFF0D1117)

val LightBg = Color(0xFFF6F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceAlt = Color(0xFFEEF2F6)
val LightBorder = Color(0xFFD0D7DE)
val LightTextPrimary = Color(0xFF1F2328)
val LightTextSecondary = Color(0xFF57606A)
val LightAccentBlue = Color(0xFF0550AE)
val LightUpColor = Color(0xFF116329)
val LightUpContainer = Color(0xFFDCEFE3)
val LightDownColor = Color(0xFFA0111F)
val LightDownContainer = Color(0xFFF8D6D9)
val LightAccentOrange = Color(0xFF7D4E00)
val LightAccentRed = LightDownColor
val OnAccentLight = Color(0xFFFFFFFF)
