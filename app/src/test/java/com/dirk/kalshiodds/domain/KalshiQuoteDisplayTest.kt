package com.dirk.kalshiodds.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiQuoteDisplayTest {
    @Test
    fun centsAndMultiplierMatchKalshiHero() {
        assertEquals(64, KalshiQuoteDisplay.cents(0.64))
        assertEquals(1.5625, KalshiQuoteDisplay.multiplier(0.64)!!, 1e-6)
        assertEquals(37, KalshiQuoteDisplay.cents(0.37))
        val down = KalshiQuoteDisplay.multiplier(0.37)!!
        assertTrue(down in 2.70..2.71)
        assertEquals("Up 64¢ · 1.56x", KalshiQuoteDisplay.buttonLabel(true, 0.64))
        assertTrue(KalshiQuoteDisplay.buttonLabel(false, 0.37).startsWith("Down 37¢"))
    }

    @Test
    fun aiLabelIsSeparateFromMarket() {
        assertEquals("AI: 71% UP", KalshiQuoteDisplay.aiLabel(0.71))
        assertEquals("AI: 73% DOWN", KalshiQuoteDisplay.aiLabel(0.27))
    }

    @Test
    fun targetNowLine() {
        val line = KalshiQuoteDisplay.targetNowLine(84_278.84, 84_311.58)
        assertTrue(line!!.contains("Target"))
        assertTrue(line.contains("Now"))
        assertTrue(line.contains("▲"))
    }
}
