package com.dirk.kalshiodds.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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

    const val AA_NORMAL = AA
    const val AA_UI = AA_LARGE

    fun ratio(foregroundArgb: Long, backgroundArgb: Long): Double {
        val lighter = max(luminance(foregroundArgb), luminance(backgroundArgb))
        val darker = min(luminance(foregroundArgb), luminance(backgroundArgb))
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun passesAa(foregroundArgb: Long, backgroundArgb: Long, minRatio: Double = AA_NORMAL): Boolean =
        ratio(foregroundArgb, backgroundArgb) + 1e-9 >= minRatio

    /** Src-over of an opaque [srcArgb] at [srcAlpha] onto opaque [dstArgb]. */
    fun composite(srcArgb: Long, dstArgb: Long, srcAlpha: Double): Long {
        require(srcAlpha in 0.0..1.0)
        fun channel(src: Int, dst: Int): Int =
            (src * srcAlpha + dst * (1.0 - srcAlpha)).roundToInt().coerceIn(0, 255)
        val r = channel(((srcArgb shr 16) and 0xFF).toInt(), ((dstArgb shr 16) and 0xFF).toInt())
        val g = channel(((srcArgb shr 8) and 0xFF).toInt(), ((dstArgb shr 8) and 0xFF).toInt())
        val b = channel((srcArgb and 0xFF).toInt(), (dstArgb and 0xFF).toInt())
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /**
     * True for yellow, amber, gold, orange, and lime hues.
     * Near-grays and reds (hue under 12° or over 105°) are not flagged.
     */
    fun isYellowAmberOrangeOrLime(argb: Long): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta < 24) return false
        val sector = when (maxC) {
            r -> {
                val raw = (g - b).toDouble() / delta
                if (raw < 0) raw + 6 else raw
            }
            g -> (b - r).toDouble() / delta + 2
            else -> (r - g).toDouble() / delta + 4
        }
        return sector * 60.0 in 12.0..105.0
    }

    fun luminance(argb: Long): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(((argb shr 16) and 0xFF).toInt())
        val g = channel(((argb shr 8) and 0xFF).toInt())
        val b = channel((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

@Composable
fun checklistLabelColor(background: Color = MaterialTheme.colorScheme.surface): Color =
    Contrast.readable(MaterialTheme.colorScheme.onSurfaceVariant, background)

@Composable
fun checklistValueColor(background: Color = MaterialTheme.colorScheme.surface): Color =
    Contrast.readable(MaterialTheme.colorScheme.onSurface, background)
