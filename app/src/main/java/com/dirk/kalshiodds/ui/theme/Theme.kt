package com.dirk.kalshiodds.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class DipPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentBlue: Color,
    val up: Color,
    val upContainer: Color,
    val down: Color,
    val downContainer: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val onAccentBlue: Color,
    val onUp: Color,
    val onDown: Color,
    val onAccentOrange: Color,
    val onAccentRed: Color
)

val DarkPalette = DipPalette(
    bg = Bg,
    surface = Surface,
    surfaceAlt = SurfaceAlt,
    border = Border,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    accentBlue = AccentBlue,
    up = UpColor,
    upContainer = UpContainer,
    down = DownColor,
    downContainer = DownContainer,
    accentOrange = AccentOrange,
    accentRed = AccentRed,
    onAccentBlue = OnAccentDark,
    onUp = OnAccentDark,
    onDown = OnAccentDark,
    onAccentOrange = OnAccentDark,
    onAccentRed = OnAccentDark
)

val LightPalette = DipPalette(
    bg = LightBg,
    surface = LightSurface,
    surfaceAlt = LightSurfaceAlt,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    accentBlue = LightAccentBlue,
    up = LightUpColor,
    upContainer = LightUpContainer,
    down = LightDownColor,
    downContainer = LightDownContainer,
    accentOrange = LightAccentOrange,
    accentRed = LightAccentRed,
    onAccentBlue = OnAccentLight,
    onUp = OnAccentLight,
    onDown = OnAccentLight,
    onAccentOrange = OnAccentLight,
    onAccentRed = OnAccentLight
)

val LocalDipPalette = staticCompositionLocalOf { DarkPalette }

object DipTheme {
    val colors: DipPalette
        @Composable get() = LocalDipPalette.current

    fun palette(dark: Boolean): DipPalette = if (dark) DarkPalette else LightPalette

    fun contrastPairs(dark: Boolean): List<ThemeRoles.Pair> = ThemeRoles.forPalette(palette(dark))
}

object ThemeRoles {
    data class Pair(
        val screen: String,
        val role: String,
        val foreground: Color,
        val background: Color
    )

    fun forPalette(p: DipPalette): List<Pair> = listOf(
        Pair("odds", "title on scaffold", p.textPrimary, p.bg),
        Pair("odds", "secondary on scaffold", p.textSecondary, p.bg),
        Pair("odds", "UP on scaffold", p.up, p.bg),
        Pair("odds", "DOWN on scaffold", p.down, p.bg),
        Pair("odds", "AI on scaffold", p.accentBlue, p.bg),
        Pair("odds", "orange on scaffold", p.accentOrange, p.bg),
        Pair("card", "primary on surface", p.textPrimary, p.surface),
        Pair("card", "secondary on surface", p.textSecondary, p.surface),
        Pair("card", "UP on surface", p.up, p.surface),
        Pair("card", "DOWN on surface", p.down, p.surface),
        Pair("card", "AI on surface", p.accentBlue, p.surface),
        Pair("card", "orange on surface", p.accentOrange, p.surface),
        Pair("card", "primary on surfaceAlt", p.textPrimary, p.surfaceAlt),
        Pair("card", "secondary on surfaceAlt", p.textSecondary, p.surfaceAlt),
        Pair("card", "UP on surfaceAlt", p.up, p.surfaceAlt),
        Pair("card", "DOWN on surfaceAlt", p.down, p.surfaceAlt),
        Pair("card", "UP on upContainer", p.up, p.upContainer),
        Pair("card", "DOWN on downContainer", p.down, p.downContainer),
        Pair("button", "UP label", p.onUp, p.up),
        Pair("button", "DOWN label", p.onDown, p.down),
        Pair("card", "BET UP on surface", p.up, p.surface),
        Pair("card", "BET DOWN on surface", p.down, p.surface),
        Pair("card", "NO BET on surface", p.textSecondary, p.surface),
        Pair("paper", "title on surface", p.textPrimary, p.surface),
        Pair("ticket", "body on surface", p.textPrimary, p.surface),
        Pair("ticket", "hint on surface", p.textSecondary, p.surface),
        Pair("history", "row on surfaceAlt", p.textPrimary, p.surfaceAlt),
        Pair("settings", "title on bg", p.textPrimary, p.bg),
        Pair("settings", "hint on bg", p.textSecondary, p.bg),
        Pair("data", "stat on surface", p.textPrimary, p.surface),
        Pair("scorecard", "title on bg", p.textPrimary, p.bg),
        Pair("chart", "axis on surface", p.textSecondary, p.surface)
    )
}

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = OnAccentDark,
    secondary = AccentBlue,
    onSecondary = OnAccentDark,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = AccentRed,
    onError = OnAccentDark,
    errorContainer = DownContainer,
    onErrorContainer = DownColor
)

private val LightColors = lightColorScheme(
    primary = LightAccentBlue,
    onPrimary = OnAccentLight,
    secondary = LightAccentBlue,
    onSecondary = OnAccentLight,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = LightAccentRed,
    onError = OnAccentLight,
    errorContainer = LightDownContainer,
    onErrorContainer = LightDownColor
)

@Composable
fun KalshiOddsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalDipPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
