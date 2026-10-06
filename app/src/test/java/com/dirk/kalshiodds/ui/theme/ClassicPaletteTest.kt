package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chrome stays the 0.3.23 classic palette. UP greens and DOWN reds are the 0.3.36 deeper set. */
class ClassicPaletteTest {

    @Test
    fun classicMatches02323AndFieldOpsStaysAvailable() {
        assertEquals(Color(0xFF0D1117), ClassicDarkPalette.bg)
        assertEquals(Color(0xFF161B22), ClassicDarkPalette.surface)
        assertEquals(Color(0xFF21262D), ClassicDarkPalette.surfaceAlt)
        assertEquals(Color(0xFF30363D), ClassicDarkPalette.border)
        assertEquals(Color(0xFFF0F6FC), ClassicDarkPalette.textPrimary)
        assertEquals(Color(0xFF8B949E), ClassicDarkPalette.textSecondary)
        assertEquals(Color(0xFF58A6FF), ClassicDarkPalette.accentBlue)
        assertEquals(Color(0xFF37A146), ClassicDarkPalette.up)
        assertEquals(Color(0xFF329440), ClassicDarkPalette.upButton)
        assertEquals(Color(0xFF102616), ClassicDarkPalette.upContainer)
        assertEquals(Color(0xFFF85048), ClassicDarkPalette.down)
        assertEquals(Color(0xFFDE4439), ClassicDarkPalette.downInk)
        assertEquals(Color(0xFF9E2020), ClassicDarkPalette.downButton)
        assertEquals(Color(0xFFFFFFFF), ClassicDarkPalette.onDown)
        assertEquals(Color(0xFF160808), ClassicDarkPalette.downContainer)
        assertEquals(Color(0xFFD29922), ClassicDarkPalette.accentOrange)
        assertEquals(Color(0xFFF6F8FA), ClassicLightPalette.bg)
        assertEquals(Color(0xFFFFFFFF), ClassicLightPalette.surface)
        assertEquals(Color(0xFFEEF2F6), ClassicLightPalette.surfaceAlt)
        assertEquals(Color(0xFF1F2328), ClassicLightPalette.textPrimary)
        assertEquals(Color(0xFF57606A), ClassicLightPalette.textSecondary)
        assertEquals(Color(0xFF0550AE), ClassicLightPalette.accentBlue)
        assertEquals(Color(0xFF0E4F21), ClassicLightPalette.up)
        assertEquals(Color(0xFF0E4F21), ClassicLightPalette.upButton)
        assertEquals(Color(0xFF9DD2B1), ClassicLightPalette.upContainer)
        assertEquals(Color(0xFF721111), ClassicLightPalette.down)
        assertEquals(Color(0xFF721111), ClassicLightPalette.downInk)
        assertEquals(Color(0xFF921616), ClassicLightPalette.downButton)
        assertEquals(Color(0xFFFFFFFF), ClassicLightPalette.onDown)
        assertEquals(Color(0xFFE7868E), ClassicLightPalette.downContainer)
        assertEquals(Color(0xFF7D4E00), ClassicLightPalette.accentOrange)
        assertEquals(ClassicDarkPalette, DipTheme.palette(dark = true))
        assertEquals(ClassicLightPalette, DipTheme.palette(dark = false))
        assertEquals(FieldOpsDarkPalette, DipTheme.palette(true, ColorStyles.FIELDOPS))
        assertEquals(Color(FieldSwatch.Dark.Background), FieldOpsDarkPalette.bg)
        assertEquals(ColorStyles.CLASSIC, ColorStyles.normalize(null))
        assertEquals(ColorStyles.FIELDOPS, ColorStyles.normalize("FieldOps"))
    }

    @Test
    fun classicAndFieldOpsTextMeetAa() {
        for (style in listOf(ColorStyles.CLASSIC, ColorStyles.FIELDOPS)) {
            for (dark in listOf(false, true)) {
                for (pair in DipTheme.contrastPairs(dark, style)) {
                    val ratio = Contrast.ratio(pair.foreground, pair.background)
                    assertTrue(
                        "$style ${if (dark) "dark" else "light"} ${pair.screen}/${pair.role} = $ratio",
                        ratio >= Contrast.AA
                    )
                }
            }
        }
    }
}
