package com.dirk.kalshiodds.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * FieldOps chrome, with DipHunter's stable token names kept for contrast tests.
 *
 * Composables must read [DipTheme.colors], not the dark-only vals. Importing
 * [Bg] / [TextSecondary] / [UpColor] from here is what made light mode unreadable.
 *
 * Accent names are gray aliases. Do not paint them blue. [AccentOrange] is gray,
 * never amber. Green = buying UP. Red = buying DOWN.
 *
 * [TextPrimary], [LightTextPrimary], and [Surface] stay fixed (not theme getters)
 * so [com.dirk.kalshiodds.ui.theme.Contrast.readable] defaults do not flip when
 * [ThemeMode] changes during a screenshot.
 */
object ThemeMode {
    var isDark by mutableStateOf(true)
}

private fun pick(dark: Long, light: Long): Color =
    Color(if (ThemeMode.isDark) dark else light)

private fun swatch(argb: Long): Color = Color(argb)

val PrimaryGreen: Color get() = pick(FieldSwatch.Dark.Primary, FieldSwatch.Light.Primary)
val PrimaryGreenDark: Color get() = pick(FieldSwatch.Dark.PrimaryDark, FieldSwatch.Light.PrimaryDark)
val PrimaryGreenLight: Color get() = pick(FieldSwatch.Dark.PrimaryLight, FieldSwatch.Light.PrimaryLight)
val PrimaryContainer: Color get() = pick(FieldSwatch.Dark.PrimaryContainer, FieldSwatch.Light.PrimaryContainer)
val OnPrimaryContainer: Color get() = pick(FieldSwatch.Dark.OnPrimaryContainer, FieldSwatch.Light.OnPrimaryContainer)
/** Text/icons on primary fills — white on dark gray (light theme), near-black on light gray (dark theme). */
val OnPrimary: Color get() = pick(FieldSwatch.Dark.OnPrimary, FieldSwatch.Light.OnPrimary)

val BackgroundDark: Color get() = pick(FieldSwatch.Dark.Background, FieldSwatch.Light.Background)
val BackgroundCard: Color get() = pick(FieldSwatch.Dark.Card, FieldSwatch.Light.Elevated)
val BackgroundElevated: Color get() = pick(FieldSwatch.Dark.Elevated, FieldSwatch.Light.Elevated)
/**
 * Bottom bar fill. Dark is [FieldSwatch.Dark.NavBar]. Light matches
 * surfaceContainerLow so the light bar does not shift.
 */
val NavBar: Color get() = pick(FieldSwatch.Dark.NavBar, FieldSwatch.Light.Elevated)
/** Solid selected-tab pill for the dark bar. Light theme keeps its translucent wash. */
val NavIndicator: Color get() = swatch(FieldSwatch.Dark.NavIndicator)
val SurfaceDark: Color get() = pick(FieldSwatch.Dark.Card, FieldSwatch.Light.Elevated)
val SurfaceVariant: Color get() = pick(FieldSwatch.Dark.SurfaceVariant, FieldSwatch.Light.SurfaceVariant)
val SurfaceBright: Color get() = pick(FieldSwatch.Dark.SurfaceBright, FieldSwatch.Light.SurfaceBright)

val TextTertiary: Color get() = pick(FieldSwatch.Dark.OnSurfaceMuted, FieldSwatch.Light.OnSurfaceMuted)

val StatusPending: Color get() = pick(FieldSwatch.Dark.StatusPending, FieldSwatch.Light.StatusPending)
val StatusInProgress: Color get() = pick(FieldSwatch.Dark.StatusInProgress, FieldSwatch.Light.StatusInProgress)
val StatusCompleted: Color get() = pick(FieldSwatch.Dark.StatusCompleted, FieldSwatch.Light.StatusCompleted)
val StatusCancelled: Color get() = pick(FieldSwatch.Dark.StatusCancelled, FieldSwatch.Light.StatusCancelled)
val StatusUrgent: Color get() = pick(FieldSwatch.Dark.StatusUrgent, FieldSwatch.Light.StatusUrgent)
/**
 * Urgent label on an inverse hero. Dark theme hero is light, so this is the
 * inverse luminance of [StatusUrgent].
 */
val OnHeroWarning: Color get() = pick(FieldSwatch.Dark.OnHeroWarning, FieldSwatch.Light.OnHeroWarning)

val AccentPurple: Color get() = pick(FieldSwatch.Dark.AccentPurple, FieldSwatch.Light.AccentPurple)
val AccentCyan: Color get() = pick(FieldSwatch.Dark.AccentCyan, FieldSwatch.Light.AccentCyan)
val AccentPink: Color get() = pick(FieldSwatch.Dark.AccentPink, FieldSwatch.Light.AccentPink)
val AccentAmber: Color get() = pick(FieldSwatch.Dark.AccentAmber, FieldSwatch.Light.AccentAmber)

val BorderDark: Color get() = pick(FieldSwatch.Dark.Outline, FieldSwatch.Light.Outline)
val DividerDark: Color get() = pick(FieldSwatch.Dark.OutlineVariant, FieldSwatch.Light.OutlineVariant)
val ScrimDark: Color get() = pick(FieldSwatch.Dark.Scrim, FieldSwatch.Light.Scrim)

val ErrorRed: Color get() = pick(FieldSwatch.Dark.Error, FieldSwatch.Light.Error)
val ErrorRedDark: Color get() = pick(FieldSwatch.Dark.ErrorContainer, FieldSwatch.Light.Error)
val SuccessGreen: Color get() = pick(FieldSwatch.Dark.StatusCompleted, FieldSwatch.Light.Success)
/** Legacy name. Value is the strong red [StatusUrgent], never a yellow. */
val WarningYellow: Color get() = pick(FieldSwatch.Dark.StatusUrgent, FieldSwatch.Light.StatusUrgent)
val InfoBlue: Color get() = pick(FieldSwatch.Dark.AccentBlue, FieldSwatch.Light.AccentBlue)

val GradientStart: Color get() = pick(FieldSwatch.Dark.GradientStart, FieldSwatch.Light.GradientStart)
val GradientMid: Color get() = pick(FieldSwatch.Dark.GradientMid, FieldSwatch.Light.GradientMid)
val GradientEnd: Color get() = pick(FieldSwatch.Dark.GradientEnd, FieldSwatch.Light.GradientEnd)

/** Camera / AR / map HUDs sit on video or tiles — always light-on-dark, independent of app theme. */
val OverlayOnDark: Color get() = swatch(FieldSwatch.OverlayOnDark)
val OverlayOnDarkMuted: Color get() = swatch(FieldSwatch.OverlayOnDarkMuted)
val OverlayScrim: Color get() = swatch(FieldSwatch.OverlayScrim)

/** Paper surfaces stay white regardless of theme. */
val PaperWhite: Color get() = swatch(FieldSwatch.Paper)
val OnPaper: Color get() = swatch(FieldSwatch.OnPaper)

// --- Stable dark tokens. Contrast tests compare these by identity. ---

val Bg = Color(FieldSwatch.Dark.Background)
/** Dark card. Light-theme body text on this fill must fail AA (the old bug). */
val Surface = Color(FieldSwatch.Dark.Card)
val SurfaceAlt = Color(FieldSwatch.Dark.Surface)
val Border = Color(FieldSwatch.Dark.OutlineVariant)
/** Light text for dark surfaces. */
val TextPrimary = Color(FieldSwatch.Dark.OnSurface)
val TextSecondary = Color(FieldSwatch.Dark.OnSurfaceVariant)
/** Gray alias. Not blue. */
val AccentBlue = Color(FieldSwatch.Dark.AccentBlue)
/** UP on dark cards. Green, AA on #333333 and on the 15% wash. */
val UpColor = Color(FieldSwatch.Dark.StatusCompleted)
val UpContainer = Color(FieldSwatch.Dark.UpContainer)
/** DOWN on dark cards. Red, AA on #333333 and on the 15% wash. */
val DownColor = Color(FieldSwatch.Dark.Error)
val DownContainer = Color(FieldSwatch.Dark.DownContainer)
/** Gray alias. Not amber. */
val AccentOrange = Color(FieldSwatch.Dark.AccentOrange)
val AccentRed = DownColor
val OnAccentDark = Color(FieldSwatch.Dark.OnPrimary)

val LightBg = Color(FieldSwatch.Light.Background)
/** Light list cards are surfaceContainerLow, not pure white. */
val LightSurface = Color(FieldSwatch.Light.Elevated)
val LightSurfaceAlt = Color(0xFFE8E8E8)
val LightBorder = Color(FieldSwatch.Light.OutlineVariant)
/** Dark text for light surfaces. */
val LightTextPrimary = Color(FieldSwatch.Light.OnSurface)
val LightTextSecondary = Color(FieldSwatch.Light.OnSurfaceVariant)
/** Gray alias. Not blue. */
val LightAccentBlue = Color(FieldSwatch.Light.AccentBlue)
val LightUpColor = Color(FieldSwatch.Light.Success)
val LightUpContainer = Color(FieldSwatch.Light.UpContainer)
val LightDownColor = Color(FieldSwatch.Light.Error)
val LightDownContainer = Color(FieldSwatch.Light.DownContainer)
/** Gray alias. Not amber. */
val LightAccentOrange = Color(FieldSwatch.Light.AccentOrange)
val LightAccentRed = LightDownColor
val OnAccentLight = Color(FieldSwatch.Light.OnPrimary)
