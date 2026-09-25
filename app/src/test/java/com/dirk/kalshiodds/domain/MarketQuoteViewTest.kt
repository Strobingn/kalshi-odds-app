package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketQuoteViewTest {

    @Test
    fun oneCentAtFiveDollarsIsHandComputed93_46x() {
        // C = floor(5 / 0.01) = 500
        // model = 0.07 × 500 × 0.01 × 0.99 = 0.3465
        // trade = ceil_6dp(0.3465) = 0.346500
        // debit = ceil_cent(5.00 + 0.346500) = 5.35
        // multiple = 500 / 5.35
        assertEquals(500, KalshiFee.contractsForStake(5.0, 0.01))
        assertEquals(0.3465, KalshiFee.raw(500, 0.01), 1e-12)
        assertEquals(0.346500, KalshiFee.ceil6dp(0.3465), 1e-12)
        assertEquals(0.35, KalshiFee.total(500, 0.01), 1e-9)
        assertEquals(5.35, KalshiFee.totalCost(500, 0.01), 1e-9)
        val one = KalshiQuoteDisplay.multiplier(0.01)!!
        assertEquals(500.0 / 5.35, one, 1e-9)
        assertEquals(93.45794392523364, one, 1e-9)
        assertTrue("1¢ multiple must not be 99x", kotlin.math.abs(one - 99.0) > 1.0)
        assertTrue(one < 94.0)
        assertTrue(one > 93.0)
    }

    @Test
    fun fiftyCentsAtFiveDollarsIsHandComputed1_93x() {
        // C = 10, model = 0.07 × 10 × 0.50 × 0.50 = 0.175
        // debit = ceil_cent(5.00 + 0.175) = 5.18
        assertEquals(10, KalshiFee.contractsForStake(5.0, 0.50))
        assertEquals(0.175, KalshiFee.raw(10, 0.50), 1e-12)
        assertEquals(0.18, KalshiFee.total(10, 0.50), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.50)!!
        assertEquals(10.0 / 5.18, m, 1e-9)
        assertEquals(1.9305019305019305, m, 1e-9)
    }

    @Test
    fun ninetyNineCentsAtFiveDollarsIsHandComputed1_01x() {
        // C = floor(5 / 0.99) = 5, position = 4.95
        // model = 0.07 × 5 × 0.99 × 0.01 = 0.003465
        // debit = ceil_cent(4.953465) = 4.96
        assertEquals(5, KalshiFee.contractsForStake(5.0, 0.99))
        assertEquals(0.003465, KalshiFee.raw(5, 0.99), 1e-12)
        assertEquals(0.01, KalshiFee.total(5, 0.99), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.99)!!
        assertEquals(5.0 / 4.96, m, 1e-9)
        assertEquals(1.0080645161290323, m, 1e-9)
    }

    @Test
    fun deciCentAtFiveDollarsIsLegitimateAndHandComputed() {
        // 0.1¢ is a real tapered_deci_cent / deci_cent tick.
        // C = floor(5 / 0.001) = 5000
        // model = 0.07 × 5000 × 0.001 × 0.999 = 0.34965
        // debit = ceil_cent(5.00 + 0.349650) = 5.35
        // multiple = 5000 / 5.35 ≈ 934.58 — NOT the 0.3.8 934.64 from 1/(P+raw)
        assertEquals(5000, KalshiFee.contractsForStake(5.0, 0.001))
        assertEquals(0.34965, KalshiFee.raw(5000, 0.001), 1e-12)
        assertEquals(0.35, KalshiFee.total(5000, 0.001), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.001)!!
        assertEquals(5000.0 / 5.35, m, 1e-9)
        assertEquals(934.5794392523364, m, 1e-9)
        val oldWrong = 1.0 / (0.001 + 0.07 * 0.001 * 0.999)
        assertTrue(kotlin.math.abs(oldWrong - 934.64) < 0.02)
        assertTrue(kotlin.math.abs(m - oldWrong) > 0.05)
        assertEquals("0.1¢", KalshiQuoteDisplay.formatAsk(0.001))
        assertEquals("Down 0.1¢ · 934.58x", KalshiQuoteDisplay.buttonLabel(false, 0.001))
        val q = MarketQuoteView.of(
            yesBid = null,
            yesAsk = null,
            noBid = null,
            noAsk = 0.001
        )
        assertEquals("0.1¢", q.downHero)
        assertEquals("0.1¢", q.noAskLabel)
        assertEquals(m, q.downMultiple!!, 1e-9)
        assertEquals("Down 0.1¢ · 934.58x", q.downButton)
        assertTrue(q.downHeader.contains("ask 0.1¢"))
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
        val q = MarketQuoteView.of(yesBid = 1.0, yesAsk = null, noBid = null, noAsk = null)
        assertNull(q.upMultiple)
        assertNull(q.downMultiple)
        assertEquals("Buy UP", q.upButton)
        assertEquals("Buy DOWN", q.downButton)
    }

    @Test
    fun oneCentIsNotNinetyNineX() {
        val label = KalshiQuoteDisplay.buttonLabel(false, 0.01)
        assertEquals("Down 1¢ · 93.46x", label)
        assertTrue(KalshiQuoteDisplay.multiplier(0.01)!! < 94.0)
        assertTrue(kotlin.math.abs(KalshiQuoteDisplay.multiplier(0.01)!! - 99.0) > 1.0)
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
        assertEquals(500.0 / 5.35, q.downMultiple!!, 1e-9)
        assertEquals("Buy UP", q.upButton)
        assertEquals("Down 1¢ · 93.46x", q.downButton)
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

    @Test
    fun handComputedMultiplesAtFiveDollarStakeMatchDisplay() {
        val cases = listOf(
            Triple(0.001, "0.1¢", 5000.0 / 5.35),
            Triple(0.01, "1¢", 500.0 / 5.35),
            Triple(0.015, "1.5¢", 333.0 / 5.34),
            Triple(0.018, "1.8¢", 277.0 / 5.33),
            Triple(0.099, "9.9¢", 50.0 / 5.27),
            Triple(0.10, "10¢", 50.0 / 5.32),
            Triple(0.105, "10.5¢", 47.0 / 5.25),
            Triple(0.25, "25¢", 20.0 / 5.27),
            Triple(0.50, "50¢", 10.0 / 5.18),
            Triple(0.905, "90.5¢", 5.0 / 4.56),
            Triple(0.99, "99¢", 5.0 / 4.96)
        )
        for ((ask, label, expected) in cases) {
            val q = MarketQuoteView.of(yesBid = null, yesAsk = ask, noBid = null, noAsk = ask)
            assertEquals("hero $ask", label, q.upHero)
            assertEquals("ask label $ask", label, q.yesAskLabel)
            assertEquals("button $ask", label, q.upButton.substringAfter("Up ").substringBefore(" ·"))
            assertEquals("multiple $ask", expected, q.upMultiple!!, 1e-9)
            assertEquals("same source $ask", KalshiQuoteDisplay.multiplier(ask)!!, q.upMultiple!!, 1e-9)
            assertTrue(q.upButton.contains(q.upHero))
            assertTrue(q.upHeader.contains("ask $label"))
            if (ask < 0.10) {
                assertTrue("sub-10c $ask must not exceed 1/P", q.upMultiple!! <= 1.0 / ask + 1e-9)
                assertTrue("sub-10c $ask must not be the 0.1c 934x unless it is 0.1c", ask < 0.0015 || q.upMultiple!! < 200.0)
            } else {
                assertNotNull("10c+ $ask must show a multiple", q.upMultiple)
                assertTrue("10c+ $ask multiple", q.upMultiple!! > 1.0)
            }
        }
        assertEquals("Down 1.5¢ · 62.36x", KalshiQuoteDisplay.buttonLabel(false, 0.015))
        assertEquals("Down 10¢ · 9.40x", KalshiQuoteDisplay.buttonLabel(false, 0.10))
        assertEquals("Down 25¢ · 3.80x", KalshiQuoteDisplay.buttonLabel(false, 0.25))
    }

    @Test
    fun restPayloadSubPennyAndWholeCentShareOneQuote() {
        val sub = com.dirk.kalshiodds.data.dto.MarketDto(
            ticker = "KXBTC15M-26SEP251600-00",
            yesAskDollars = "0.0150",
            noAskDollars = "0.2500",
            yesBidDollars = "0.0140",
            noBidDollars = "0.2480"
        ).toUiModel(SeriesKind.BTC)
        assertEquals(0.015, sub.yesAsk!!, 1e-12)
        assertEquals(0.25, sub.noAsk!!, 1e-12)
        val q = MarketQuoteView.of(sub)
        assertEquals("1.5¢", q.upHero)
        assertEquals("25¢", q.downHero)
        assertEquals(333.0 / 5.34, q.upMultiple!!, 1e-9)
        assertEquals(20.0 / 5.27, q.downMultiple!!, 1e-9)
        assertTrue(q.upButton.contains("1.5¢"))
        assertTrue(q.downButton.contains("25¢"))
        assertFalse(q.downButton.contains("934"))
    }
}
