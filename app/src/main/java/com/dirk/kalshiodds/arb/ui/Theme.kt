package com.dirk.kalshiodds.arb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Profit green / loss red that read on both schemes. */
data class ArbColors(val profit: Color, val loss: Color, val warn: Color)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF0B4F4A),
    onPrimaryContainer = Color(0xFFB2F5EA),
    secondary = Color(0xFFF6C85F),
    onSecondary = Color(0xFF2A1F00),
    background = Color(0xFF0A1414),
    onBackground = Color(0xFFE6F2F1),
    surface = Color(0xFF0F1E1E),
    onSurface = Color(0xFFE6F2F1),
    surfaceVariant = Color(0xFF17302F),
    onSurfaceVariant = Color(0xFF9FB8B6),
    outline = Color(0xFF3A5654),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A06)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00695F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F5EA),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF7A5900),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4FAF9),
    onBackground = Color(0xFF142120),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF142120),
    surfaceVariant = Color(0xFFDDEBE9),
    onSurfaceVariant = Color(0xFF3F5654),
    outline = Color(0xFF6F8987),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkArb = ArbColors(profit = Color(0xFF5EE08A), loss = Color(0xFFFF8A80), warn = Color(0xFFF6C85F))
private val LightArb = ArbColors(profit = Color(0xFF12692F), loss = Color(0xFFB3261E), warn = Color(0xFF7A5900))

val LocalArbColors = staticCompositionLocalOf { DarkArb }

/** Dark by default; light when the user flips the switch. */
@Composable
fun ArbHunterTheme(light: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalArbColors provides (if (light) LightArb else DarkArb)) {
        MaterialTheme(colorScheme = if (light) LightScheme else DarkScheme, content = content)
    }
}

/** Inside Dip Hunter: keep its MaterialTheme, only supply the profit/loss/warn colours. */
@Composable
fun ArbColorsProvider(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalArbColors provides (if (dark) DarkArb else LightArb), content = content)
}
