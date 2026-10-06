package com.dirk.kalshiodds.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class DipPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentBlue: Color,
    val up: Color,
    val upButton: Color,
    val upContainer: Color,
    val down: Color,
    val downInk: Color,
    val downButton: Color,
    val downContainer: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val onAccentBlue: Color,
    val onUp: Color,
    val onDown: Color,
    val onAccentOrange: Color,
    val onAccentRed: Color,
    val gradientStart: Color,
    val gradientEnd: Color
)

val ClassicDarkPalette = DipPalette(
    bg = Bg,
    surface = Surface,
    surfaceAlt = SurfaceAlt,
    border = Border,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    accentBlue = AccentBlue,
    up = UpColor,
    upButton = UpButton,
    upContainer = UpContainer,
    down = DownColor,
    downInk = DownInk,
    downButton = DownButton,
    downContainer = DownContainer,
    accentOrange = AccentOrange,
    accentRed = AccentRed,
    onAccentBlue = OnAccentDark,
    onUp = OnUpDark,
    onDown = OnDownButton,
    onAccentOrange = OnDownDark,
    onAccentRed = OnDownButton,
    gradientStart = GradientStartDark,
    gradientEnd = GradientEndDark
)

val ClassicLightPalette = DipPalette(
    bg = LightBg,
    surface = LightSurface,
    surfaceAlt = LightSurfaceAlt,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    accentBlue = LightAccentBlue,
    up = LightUpColor,
    upButton = LightUpColor,
    upContainer = LightUpContainer,
    down = LightDownColor,
    downInk = LightDownColor,
    downButton = LightDownButton,
    downContainer = LightDownContainer,
    accentOrange = LightAccentOrange,
    accentRed = LightAccentRed,
    onAccentBlue = OnAccentLight,
    onUp = OnUpLight,
    onDown = OnDownLight,
    onAccentOrange = OnAccentLight,
    onAccentRed = OnAccentLight,
    gradientStart = GradientStartLight,
    gradientEnd = GradientEndLight
)

/** Default palette. Chrome matches 0.3.23. Reds and greens are the 0.3.36 deeper set. */
val DarkPalette = ClassicDarkPalette
val LightPalette = ClassicLightPalette

val FieldOpsDarkPalette = DipPalette(
    bg = Color(FieldSwatch.Dark.Background),
    surface = Color(FieldSwatch.Dark.Card),
    surfaceAlt = Color(FieldSwatch.Dark.SurfaceBright),
    border = Color(FieldSwatch.Dark.OutlineVariant),
    textPrimary = Color(FieldSwatch.Dark.OnSurface),
    textSecondary = Color(FieldSwatch.Dark.OnSurfaceVariant),
    accentBlue = Color(FieldSwatch.Dark.AccentBlue),
    up = Color(FieldSwatch.Dark.Success),
    upButton = Color(FieldSwatch.Dark.SuccessFill),
    upContainer = Color(FieldSwatch.Dark.SuccessContainer),
    down = Color(FieldSwatch.Dark.Error),
    downInk = Color(FieldSwatch.Dark.ErrorFill),
    downButton = Color(FieldSwatch.Dark.ErrorFill),
    downContainer = Color(FieldSwatch.Dark.ErrorContainer),
    accentOrange = Color(FieldSwatch.Dark.StatusUrgent),
    accentRed = Color(FieldSwatch.Dark.Error),
    onAccentBlue = Color(FieldSwatch.Dark.OnPrimary),
    onUp = Color(FieldSwatch.Dark.OnPrimary),
    onDown = Color(FieldSwatch.Dark.OnError),
    onAccentOrange = Color(FieldSwatch.Dark.OnError),
    onAccentRed = Color(FieldSwatch.Dark.OnError),
    gradientStart = Color(FieldSwatch.Dark.GradientStart),
    gradientEnd = Color(FieldSwatch.Dark.GradientEnd)
)

val FieldOpsLightPalette = DipPalette(
    bg = Color(FieldSwatch.Light.Background),
    surface = Color(FieldSwatch.Light.Elevated),
    surfaceAlt = Color(FieldSwatch.Light.SurfaceVariant),
    border = Color(FieldSwatch.Light.OutlineVariant),
    textPrimary = Color(FieldSwatch.Light.OnSurface),
    textSecondary = Color(FieldSwatch.Light.OnSurfaceVariant),
    accentBlue = Color(FieldSwatch.Light.AccentBlue),
    up = Color(FieldSwatch.Light.Success),
    upButton = Color(FieldSwatch.Light.SuccessFill),
    upContainer = Color(FieldSwatch.Light.SuccessContainer),
    down = Color(FieldSwatch.Light.Error),
    downInk = Color(FieldSwatch.Light.ErrorFill),
    downButton = Color(FieldSwatch.Light.ErrorFill),
    downContainer = Color(FieldSwatch.Light.ErrorContainer),
    accentOrange = Color(FieldSwatch.Light.StatusUrgent),
    accentRed = Color(FieldSwatch.Light.Error),
    onAccentBlue = Color(FieldSwatch.Light.OnPrimary),
    onUp = Color(FieldSwatch.Light.OnPrimary),
    onDown = Color(FieldSwatch.Light.OnError),
    onAccentOrange = Color(FieldSwatch.Light.OnPrimary),
    onAccentRed = Color(FieldSwatch.Light.OnPrimary),
    gradientStart = Color(FieldSwatch.Light.GradientStart),
    gradientEnd = Color(FieldSwatch.Light.GradientEnd)
)

val LocalDipPalette = staticCompositionLocalOf { DarkPalette }

object DipTheme {
    val colors: DipPalette
        @Composable get() = LocalDipPalette.current

    fun palette(dark: Boolean, colorStyle: String = ColorStyles.CLASSIC): DipPalette {
        val field = ColorStyles.isFieldOps(colorStyle)
        return when {
            field && dark -> FieldOpsDarkPalette
            field -> FieldOpsLightPalette
            dark -> ClassicDarkPalette
            else -> ClassicLightPalette
        }
    }

    fun contrastPairs(dark: Boolean, colorStyle: String = ColorStyles.CLASSIC): List<ThemeRoles.Pair> =
        ThemeRoles.forPalette(palette(dark, colorStyle))
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
        Pair("card", "DOWN ink on downContainer", p.downInk, p.downContainer),
        Pair("button", "UP label", p.onUp, p.upButton),
        Pair("button", "DOWN label", p.onDown, p.downButton),
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
    primary = Color(FieldSwatch.Dark.Primary),
    onPrimary = Color(FieldSwatch.Dark.OnPrimary),
    primaryContainer = Color(FieldSwatch.Dark.PrimaryContainer),
    onPrimaryContainer = Color(FieldSwatch.Dark.OnPrimaryContainer),
    secondary = Color(FieldSwatch.Dark.PrimaryLight),
    onSecondary = Color(FieldSwatch.Dark.OnPrimary),
    secondaryContainer = Color(FieldSwatch.Dark.PrimaryDark),
    onSecondaryContainer = Color(FieldSwatch.Dark.OnPrimary),
    tertiary = Color(FieldSwatch.Dark.AccentCyan),
    onTertiary = Color(FieldSwatch.Dark.OnPrimary),
    tertiaryContainer = Color(FieldSwatch.Dark.PrimaryContainer),
    onTertiaryContainer = Color(FieldSwatch.Dark.OnPrimaryContainer),
    background = Color(FieldSwatch.Dark.Background),
    onBackground = Color(FieldSwatch.Dark.OnBackground),
    surface = Color(FieldSwatch.Dark.Surface),
    onSurface = Color(FieldSwatch.Dark.OnSurface),
    surfaceVariant = Color(FieldSwatch.Dark.SurfaceVariant),
    onSurfaceVariant = Color(FieldSwatch.Dark.OnSurfaceVariant),
    surfaceBright = Color(FieldSwatch.Dark.SurfaceBright),
    surfaceDim = Color(FieldSwatch.Dark.NavBar),
    surfaceContainerLowest = Color(FieldSwatch.Dark.Background),
    surfaceContainerLow = Color(FieldSwatch.Dark.Card),
    surfaceContainer = Color(FieldSwatch.Dark.Surface),
    surfaceContainerHigh = Color(FieldSwatch.Dark.Card),
    surfaceContainerHighest = Color(FieldSwatch.Dark.SurfaceBright),
    error = Color(FieldSwatch.Dark.Error),
    onError = Color(FieldSwatch.Dark.OnError),
    errorContainer = Color(FieldSwatch.Dark.ErrorContainer),
    onErrorContainer = Color(FieldSwatch.Dark.OnErrorContainer),
    outline = Color(FieldSwatch.Dark.Outline),
    outlineVariant = Color(FieldSwatch.Dark.OutlineVariant),
    scrim = Color(FieldSwatch.Dark.Scrim),
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(FieldSwatch.Light.Primary)
)

private val LightColors = lightColorScheme(
    primary = Color(FieldSwatch.Light.Primary),
    onPrimary = Color(FieldSwatch.Light.OnPrimary),
    primaryContainer = Color(FieldSwatch.Light.PrimaryContainer),
    onPrimaryContainer = Color(FieldSwatch.Light.OnPrimaryContainer),
    secondary = Color(FieldSwatch.Light.PrimaryLight),
    onSecondary = Color(FieldSwatch.Light.OnPrimary),
    secondaryContainer = Color(FieldSwatch.Light.SecondaryContainer),
    onSecondaryContainer = Color(FieldSwatch.Light.OnPrimaryContainer),
    tertiary = Color(FieldSwatch.Light.AccentCyan),
    onTertiary = Color(FieldSwatch.Light.OnPrimary),
    tertiaryContainer = Color(0xFFD8D8D8),
    onTertiaryContainer = Color(FieldSwatch.Light.OnPrimaryContainer),
    background = Color(FieldSwatch.Light.Background),
    onBackground = Color(FieldSwatch.Light.OnBackground),
    surface = Color(FieldSwatch.Light.Card),
    onSurface = Color(FieldSwatch.Light.OnSurface),
    surfaceVariant = Color(FieldSwatch.Light.SurfaceVariant),
    onSurfaceVariant = Color(FieldSwatch.Light.OnSurfaceVariant),
    surfaceBright = Color(FieldSwatch.Light.SurfaceBright),
    surfaceContainerLowest = Color(FieldSwatch.Light.Card),
    surfaceContainerLow = Color(FieldSwatch.Light.Elevated),
    surfaceContainer = Color(0xFFE8E8E8),
    surfaceContainerHigh = Color(0xFFE3E3E3),
    surfaceContainerHighest = Color(0xFFDCDCDC),
    error = Color(FieldSwatch.Light.Error),
    onError = Color(FieldSwatch.Light.OnError),
    errorContainer = Color(FieldSwatch.Light.ErrorContainer),
    onErrorContainer = Color(FieldSwatch.Light.OnErrorContainer),
    outline = Color(FieldSwatch.Light.Outline),
    outlineVariant = Color(FieldSwatch.Light.OutlineVariant),
    scrim = Color(FieldSwatch.Light.Scrim),
    inverseSurface = Color(0xFF2F3133),
    inverseOnSurface = Color(0xFFF4F6F8),
    inversePrimary = Color(FieldSwatch.Dark.Primary)
)

private val ClassicDarkColors = darkColorScheme(
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

private val ClassicLightColors = lightColorScheme(
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

private fun materialColors(darkTheme: Boolean, colorStyle: String) =
    when {
        ColorStyles.isFieldOps(colorStyle) && darkTheme -> DarkColors
        ColorStyles.isFieldOps(colorStyle) -> LightColors
        darkTheme -> ClassicDarkColors
        else -> ClassicLightColors
    }

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun KalshiOddsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorStyle: String = ColorStyles.CLASSIC,
    content: @Composable () -> Unit
) {
    val palette = DipTheme.palette(darkTheme, colorStyle)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalDipPalette provides palette) {
        MaterialTheme(
            colorScheme = materialColors(darkTheme, colorStyle),
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
