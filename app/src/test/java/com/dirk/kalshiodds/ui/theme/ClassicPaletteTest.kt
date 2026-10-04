package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Default colors are the 0.3.23 classic palette. FieldOps stays selectable. */
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
        assertEquals(Color(0xFF3FB950), ClassicDarkPalette.up)
        assertEquals(Color(0xFF14301C), ClassicDarkPalette.upContainer)
        assertEquals(Color(0xFFF85149), ClassicDarkPalette.down)
        assertEquals(Color(0xFF301616), ClassicDarkPalette.downContainer)
        assertEquals(Color(0xFFD29922), ClassicDarkPalette.accentOrange)
        assertEquals(Color(0xFFF6F8FA), ClassicLightPalette.bg)
        assertEquals(Color(0xFFFFFFFF), ClassicLightPalette.surface)
        assertEquals(Color(0xFFEEF2F6), ClassicLightPalette.surfaceAlt)
        assertEquals(Color(0xFF1F2328), ClassicLightPalette.textPrimary)
        assertEquals(Color(0xFF57606A), ClassicLightPalette.textSecondary)
        assertEquals(Color(0xFF0550AE), ClassicLightPalette.accentBlue)
        assertEquals(Color(0xFF116329), ClassicLightPalette.up)
        assertEquals(Color(0xFFA0111F), ClassicLightPalette.down)
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
