package com.dirk.kalshiodds.arb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.dirk.kalshiodds.ui.theme.DarkPalette
import com.dirk.kalshiodds.ui.theme.DipTheme

/** Profit / loss / warning, taken from Dip Hunter's own palette (UP, DOWN, orange). */
data class ArbColors(val profit: Color, val loss: Color, val warn: Color)

val LocalArbColors = staticCompositionLocalOf {
    ArbColors(profit = DarkPalette.up, loss = DarkPalette.down, warn = DarkPalette.accentOrange)
}

/** The Arb screen uses Dip Hunter's colours only; no palette of its own. */
@Composable
fun ArbColorsProvider(content: @Composable () -> Unit) {
    val p = DipTheme.colors
    CompositionLocalProvider(
        LocalArbColors provides ArbColors(profit = p.up, loss = p.down, warn = p.accentOrange),
        content = content
    )
}
