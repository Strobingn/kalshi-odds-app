package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketQuoteViewTest {

    @Test
    fun payoutMultipleUsesKalshiFeePerContract() {
        val one = KalshiQuoteDisplay.multiplier(0.01)!!
        val fee1 = KalshiFee.perContract(0.01)
        assertEquals((1.0 - fee1) / 0.01, one, 1e-9)
        assertEquals(99.0, one, 1e-9)

        val ninetyNine = KalshiQuoteDisplay.multiplier(0.99)!!
        val fee99 = KalshiFee.perContract(0.99)
        assertEquals((1.0 - fee99) / 0.99, ninetyNine, 1e-9)
        assertEquals(1.0, ninetyNine, 1e-6)

        val mid = KalshiQuoteDisplay.multiplier(0.64)!!
        assertEquals((1.0 - KalshiFee.perContract(0.64)) / 0.64, mid, 1e-9)
        assertTrue(mid in 1.50..1.54)
    }

    @Test
    fun zeroCentAndNoAskNeverDivide() {
        assertNull(KalshiQuoteDisplay.multiplier(0.0))
        assertNull(KalshiQuoteDisplay.multiplier(null))
        assertNull(KalshiQuoteDisplay.cents(0.0))
        assertNull(KalshiQuoteDisplay.cents(null))
        assertEquals("—", KalshiQuoteDisplay.formatAsk(0.0))
        assertEquals("—", KalshiQuoteDisplay.formatAsk(null))
        assertEquals("Buy DOWN", KalshiQuoteDisplay.buttonLabel(false, 0.0))
        assertEquals("Buy UP", KalshiQuoteDisplay.buttonLabel(true, null))
    }

    @Test
    fun oneCentIsNotNineHundredX() {
        val label = KalshiQuoteDisplay.buttonLabel(false, 0.01)
        assertEquals("Down 1¢ · 99.00x", label)
        val bogus = 1.0 / (0.001 + 0.07 * 0.001 * 0.999)
        assertTrue(kotlin.math.abs(bogus - 934.64) < 0.02)
        assertTrue(KalshiQuoteDisplay.multiplier(0.01)!! < 100.1)
        assertNull(KalshiQuoteDisplay.cents(0.001))
        assertEquals("0.1¢", KalshiQuoteDisplay.formatAsk(0.001))
    }

    @Test
    fun headerAndButtonStayConsistent() {
        val q = MarketQuoteView.of(
            yesBid = 1.0,
            yesAsk = null,
            noBid = null,
            noAsk = 0.01
        )
        assertEquals("—", q.upHero)
        assertEquals("1¢", q.downHero)
        assertEquals(99.0, q.downMultiple!!, 1e-9)
        assertEquals("Buy UP", q.upButton)
        assertEquals("Down 1¢ · 99.00x", q.downButton)
        assertEquals(q.downHero, "1¢")
        assertTrue(q.downButton.contains(q.downHero))
        assertTrue(q.downHeader.contains("ask 1¢"))
        assertTrue(q.upHeader.contains("ask —"))
    }

    @Test
    fun hundredCentBidIsDisplayedNotDropped() {
        assertEquals("100¢", KalshiQuoteDisplay.formatBid(1.0))
        assertEquals("—", KalshiQuoteDisplay.formatBid(0.0))
        assertEquals("71¢", KalshiQuoteDisplay.formatBid(0.71))
    }
}
