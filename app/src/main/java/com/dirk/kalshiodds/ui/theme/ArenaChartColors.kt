package com.dirk.kalshiodds.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Chart colours for the Arena tab (`ui/ArenaChartViews.kt`): the validated
 * reference palette, stepped separately for the dark and the light surface.
 *
 * - [Set.series]: categorical slots 1–4 (blue, orange, aqua, yellow), in this
 *   fixed order. Validated on #161B22 (dark) and #FFFFFF (light): adjacent
 *   colour-blind ΔE ≥ 8.4, normal-vision ΔE ≥ 19.8. Light aqua and yellow are
 *   under 3:1 on white, so every chart that uses them carries a legend and
 *   labels.
 * - [Set.gain] / [Set.loss]: the blue ↔ red diverging pair for profit / loss.
 * - [Set.shadeLight] / [Set.shadeDark]: two steps of one blue (before / after).
 */
object ArenaColors {
    class Set(
        val series: List<Color>,
        val gain: Color,
        val loss: Color,
        val muted: Color,
        val grid: Color,
        val baseline: Color,
        val shadeLight: Color,
        val shadeDark: Color,
        val surface: Color,
        /** Diverging midpoint for heatmaps: a gray that reads as "nothing". */
        val neutral: Color
    )

    @Composable
    fun current(): Set {
        val c = DipTheme.colors
        val dark = c.surface.luminance() < 0.5f
        return if (dark) {
            Set(
                series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500)),
                gain = Color(0xFF3987E5),
                loss = Color(0xFFE66767),
                muted = Color(0xFF898781),
                grid = Color(0xFF2C2C2A),
                baseline = Color(0xFF5A5A55),
                shadeLight = Color(0xFF86B6EF),
                shadeDark = Color(0xFF3987E5),
                surface = c.surface,
                neutral = Color(0xFF383835)
            )
        } else {
            Set(
                series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100)),
                gain = Color(0xFF2A78D6),
                loss = Color(0xFFE34948),
                muted = Color(0xFF898781),
                grid = Color(0xFFE1E0D9),
                baseline = Color(0xFFC3C2B7),
                shadeLight = Color(0xFF86B6EF),
                shadeDark = Color(0xFF1C5CAB),
                surface = c.surface,
                neutral = Color(0xFFF0EFEC)
            )
        }
    }
}

