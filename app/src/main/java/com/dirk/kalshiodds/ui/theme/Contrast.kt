package com.dirk.kalshiodds.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * WCAG-ish contrast helpers so checklist / market-stat text cannot
 * disappear as dark-on-dark (the 0.3.4 “No info” bug: hardcoded dark
 * [Surface] + light-scheme [onSurface] ≈ #1F2328 on #161B22).
 */
object Contrast {
    const val AA = 4.5
    const val AA_LARGE = 3.0

    fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Keep [preferred] when it clears [minRatio] against [background];
     * otherwise pick a light or dark fallback that does.
     */
    fun readable(
        preferred: Color,
        background: Color,
        minRatio: Double = AA,
        onDark: Color = TextPrimary,
        onLight: Color = LightTextPrimary
    ): Color {
        if (ratio(preferred, background) >= minRatio) return preferred
        val fallback = if (background.luminance() < 0.5f) onDark else onLight
        if (ratio(fallback, background) >= minRatio) return fallback
        return if (background.luminance() < 0.5f) Color.White else Color.Black
    }

    /** Blank / missing stats must stay explicit — never an empty dark glyph. */
    fun display(value: String?, empty: String = "—"): String {
        val t = value?.trim().orEmpty()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) empty else t
    }
}

@Composable
fun checklistLabelColor(background: Color = MaterialTheme.colorScheme.surface): Color =
    Contrast.readable(MaterialTheme.colorScheme.onSurfaceVariant, background)

@Composable
fun checklistValueColor(background: Color = MaterialTheme.colorScheme.surface): Color =
    Contrast.readable(MaterialTheme.colorScheme.onSurface, background)
