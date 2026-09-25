package com.dirk.kalshiodds.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiQuoteDisplayTest {
    @Test
    fun centsAndMultiplierMatchKalshiHero() {
        assertEquals(64, KalshiQuoteDisplay.cents(0.64))
        assertEquals(37, KalshiQuoteDisplay.cents(0.37))
        // Net payout / ask via KalshiFee.perContract: 64¢ → 1.53x, 37¢ → 2.65x.
        val upMult = KalshiQuoteDisplay.multiplier(0.64)!!
        val downMult = KalshiQuoteDisplay.multiplier(0.37)!!
        assertEquals(1.53, upMult, 0.01)
        assertEquals(2.65, downMult, 0.02)
        assertEquals("Up 64¢ · 1.53x", KalshiQuoteDisplay.buttonLabel(true, 0.64))
        assertTrue(KalshiQuoteDisplay.buttonLabel(false, 0.37).startsWith("Down 37¢"))
        assertEquals(1.5625, KalshiQuoteDisplay.grossMultiplier(0.64)!!, 1e-6)
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
