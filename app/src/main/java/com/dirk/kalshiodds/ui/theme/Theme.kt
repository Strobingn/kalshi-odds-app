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
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color
)

val DarkPalette = DipPalette(
    bg = Bg,
    surface = Surface,
    surfaceAlt = SurfaceAlt,
    border = Border,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    accentBlue = AccentBlue,
    accentGreen = AccentGreen,
    accentOrange = AccentOrange,
    accentRed = AccentRed
)

val LightPalette = DipPalette(
    bg = LightBg,
    surface = LightSurface,
    surfaceAlt = LightSurfaceAlt,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    accentBlue = LightAccentBlue,
    accentGreen = LightAccentGreen,
    accentOrange = LightAccentOrange,
    accentRed = LightAccentRed
)

val LocalDipPalette = staticCompositionLocalOf { DarkPalette }

object DipTheme {
    val colors: DipPalette
        @Composable get() = LocalDipPalette.current
}

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Bg,
    secondary = AccentGreen,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceAlt,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    error = AccentRed
)

private val LightColors = lightColorScheme(
    primary = LightAccentBlue,
    onPrimary = Color.White,
    secondary = LightAccentGreen,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = LightAccentRed
)

@Composable
fun KalshiOddsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalDipPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
