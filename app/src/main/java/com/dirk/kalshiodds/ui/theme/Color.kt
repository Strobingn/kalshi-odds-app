package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * FieldOps palette, split into dark and light constants so [DipPalette]
 * does not depend on a process-wide theme flag.
 *
 * Composables must read [DipTheme.colors]. The legacy names [AccentBlue]
 * and [AccentOrange] in [FieldSwatch] are gray aliases and are never blue.
 * Warning text uses [StatusUrgent] (a red), never yellow or amber.
 * Green is buying UP. Red is buying DOWN.
 */
val Bg = Color(FieldSwatch.Dark.Background)
val Surface = Color(FieldSwatch.Dark.Card)
val SurfaceAlt = Color(FieldSwatch.Dark.SurfaceBright)
val Border = Color(FieldSwatch.Dark.OutlineVariant)
val TextPrimary = Color(FieldSwatch.Dark.OnSurface)
val TextSecondary = Color(FieldSwatch.Dark.OnSurfaceVariant)
/** Gray accent. Not blue. */
val AccentBlue = Color(FieldSwatch.Dark.AccentBlue)
val UpColor = Color(FieldSwatch.Dark.Success)
val UpContainer = Color(0xFF0E2A14)
val DownColor = Color(FieldSwatch.Dark.Error)
val DownContainer = Color(FieldSwatch.Dark.ErrorContainer)
/** Urgent red for warnings. Not yellow, amber, or gold. */
val AccentOrange = Color(FieldSwatch.Dark.StatusUrgent)
val AccentRed = DownColor
val OnAccentDark = Color(FieldSwatch.Dark.OnPrimary)
val OnUpDark = Color(FieldSwatch.Dark.OnPrimary)
val OnDownDark = Color(FieldSwatch.Dark.OnError)

val LightBg = Color(FieldSwatch.Light.Background)
val LightSurface = Color(FieldSwatch.Light.Elevated)
val LightSurfaceAlt = Color(FieldSwatch.Light.SurfaceVariant)
val LightBorder = Color(FieldSwatch.Light.OutlineVariant)
val LightTextPrimary = Color(FieldSwatch.Light.OnSurface)
val LightTextSecondary = Color(FieldSwatch.Light.OnSurfaceVariant)
/** Gray accent. Not blue. */
val LightAccentBlue = Color(FieldSwatch.Light.AccentBlue)
val LightUpColor = Color(FieldSwatch.Light.Success)
val LightUpContainer = Color(0xFFE8F5E9)
val LightDownColor = Color(FieldSwatch.Light.Error)
val LightDownContainer = Color(FieldSwatch.Light.ErrorContainer)
/** Urgent red for warnings. Not yellow, amber, or gold. */
val LightAccentOrange = Color(FieldSwatch.Light.StatusUrgent)
val LightAccentRed = LightDownColor
val OnAccentLight = Color(FieldSwatch.Light.OnPrimary)
val OnUpLight = Color(FieldSwatch.Light.OnPrimary)
val OnDownLight = Color(FieldSwatch.Light.OnError)

val GradientStartDark = Color(FieldSwatch.Dark.GradientStart)
val GradientEndDark = Color(FieldSwatch.Dark.GradientEnd)
val GradientStartLight = Color(FieldSwatch.Light.GradientStart)
val GradientEndLight = Color(FieldSwatch.Light.GradientEnd)
