package com.dirk.kalshiodds.ui

import androidx.compose.ui.graphics.Color
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.LightTextPrimary
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    @Test
    fun lightOnSurfaceOnHardcodedDarkCardIsIllegible() {
        // 0.3.4 bug: light-scheme onSurface (#1F2328) painted on hardcoded Surface.
        val lightOnSurface = LightTextPrimary
        assertTrue(
            "light onSurface vs dark Surface must fail AA (this is the screenshot bug)",
            Contrast.ratio(lightOnSurface, Surface) < Contrast.AA
        )
    }

    @Test
    fun readablePromotesLightTextOnDarkCard() {
        val fixed = Contrast.readable(LightTextPrimary, Surface)
        assertTrue(Contrast.ratio(fixed, Surface) >= Contrast.AA)
        assertEquals(TextPrimary, fixed)
    }

    @Test
    fun darkSchemeOnSurfacePassesOnDarkSurface() {
        val kept = Contrast.readable(TextPrimary, Surface)
        assertEquals(TextPrimary, kept)
        assertTrue(Contrast.ratio(kept, Surface) >= Contrast.AA)
    }

    @Test
    fun lightCardKeepsDarkText() {
        val lightBg = Color(0xFFFFFFFF)
        val kept = Contrast.readable(LightTextPrimary, lightBg)
        assertEquals(LightTextPrimary, kept)
        assertTrue(Contrast.ratio(kept, lightBg) >= Contrast.AA)
    }

    @Test
    fun displayNeverReturnsBlank() {
        assertEquals("—", Contrast.display(null))
        assertEquals("—", Contrast.display("  "))
        assertEquals("—", Contrast.display("null"))
        assertEquals("0 contracts max", Contrast.display("0 contracts max"))
        assertEquals("unavailable", Contrast.display("", empty = "unavailable"))
    }
}
