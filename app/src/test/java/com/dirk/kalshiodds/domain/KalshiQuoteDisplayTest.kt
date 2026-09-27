package com.dirk.kalshiodds.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiQuoteDisplayTest {
    @Test
    fun centsAndMultiplierMatchKalshiHero() {
        assertEquals(64, KalshiQuoteDisplay.cents(0.64))
        assertEquals(37, KalshiQuoteDisplay.cents(0.37))
        // $10 stake, order-level fee. 64¢: C=15, cost $9.85, 15/9.85 = 1.52x
        // 37¢: C=27, cost $10.44, 27/10.44 = 2.59x
        val upMult = KalshiQuoteDisplay.multiplier(0.64)!!
        val downMult = KalshiQuoteDisplay.multiplier(0.37)!!
        assertEquals(15.0 / 9.85, upMult, 1e-9)
        assertEquals(27.0 / 10.44, downMult, 1e-9)
        assertEquals("Up 64¢ · 1.52x", KalshiQuoteDisplay.buttonLabel(true, 0.64))
        assertEquals("Down 37¢ · 2.59x", KalshiQuoteDisplay.buttonLabel(false, 0.37))
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
