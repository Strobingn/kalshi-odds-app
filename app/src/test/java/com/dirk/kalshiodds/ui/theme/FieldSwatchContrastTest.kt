package com.dirk.kalshiodds.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FieldOps contrast rules, including the lighter dark grays. */
class FieldSwatchContrastTest {

    @Test
    fun accentTokensAreGrayAndWarningsAreNotYellow() {
        val grays = listOf(
            FieldSwatch.Light.Primary,
            FieldSwatch.Light.AccentBlue,
            FieldSwatch.Light.AccentOrange,
            FieldSwatch.Dark.Primary,
            FieldSwatch.Dark.AccentBlue,
            FieldSwatch.Dark.AccentOrange,
            FieldSwatch.Dark.GradientStart,
            FieldSwatch.Light.GradientStart
        )
        grays.forEach { argb ->
            val r = ((argb shr 16) and 0xFF).toInt()
            val g = ((argb shr 8) and 0xFF).toInt()
            val b = (argb and 0xFF).toInt()
            assertEquals(r, g)
            assertEquals(g, b)
            assertFalse(Contrast.isYellowAmberOrangeOrLime(argb))
        }
        assertFalse(Contrast.isYellowAmberOrangeOrLime(FieldSwatch.Light.StatusUrgent))
        assertFalse(Contrast.isYellowAmberOrangeOrLime(FieldSwatch.Dark.StatusUrgent))
        assertTrue(isRed(FieldSwatch.Light.StatusUrgent))
        assertTrue(isRed(FieldSwatch.Dark.StatusUrgent))
        assertTrue(isGreen(FieldSwatch.Light.Success))
        assertTrue(isGreen(FieldSwatch.Dark.Success))
    }

    @Test
    fun warningWashOnCardsMeetsAa() {
        val darkCard = FieldSwatch.Dark.Card
        assertTrue(
            Contrast.passesAa(
                FieldSwatch.Dark.StatusUrgent,
                Contrast.composite(FieldSwatch.Dark.StatusUrgent, darkCard, 0.15)
            )
        )
        val lightCard = FieldSwatch.Light.Elevated
        assertTrue(
            Contrast.passesAa(
                FieldSwatch.Light.StatusUrgent,
                Contrast.composite(FieldSwatch.Light.StatusUrgent, lightCard, 0.15)
            )
        )
    }

    @Test
    fun darkSurfacesAreTheLighterGrays() {
        assertEquals(0xFF121212, FieldSwatch.Dark.Background)
        assertEquals(0xFF2A2A2A, FieldSwatch.Dark.NavBar)
        assertEquals(0xFF2C2C2C, FieldSwatch.Dark.Surface)
        assertEquals(0xFF333333, FieldSwatch.Dark.Card)
        assertEquals(0xFF404040, FieldSwatch.Dark.SurfaceBright)
        assertEquals(0xFF4A4A4A, FieldSwatch.Dark.NavIndicator)
    }

    private fun isGreen(argb: Long): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return g > r + 20 && g > b + 20
    }

    private fun isRed(argb: Long): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return r > g + 20 && r > b + 20
    }
}
