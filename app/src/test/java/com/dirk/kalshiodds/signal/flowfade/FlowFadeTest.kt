package com.dirk.kalshiodds.signal.flowfade

import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowFadeTest {

    private val ticker = "KXBTC15M-26OCT041430-30"

    private fun inputs(
        yes: Double = 900.0,
        no: Double = 100.0,
        tte: Long? = 600L,
        yesAsk: Double? = 0.38,
        noAsk: Double? = 0.63,
        t: String = ticker
    ) = FlowFadeRule.Inputs(t, 1_000L, tte, yes, no, yesAsk, noAsk)

    @Test
    fun heavyYesBuyingIsFadedWithNo() {
        val d = FlowFadeRule.evaluate(inputs(), alreadyEntered = false)!!
        assertEquals("NO", d.side)
        assertEquals(0.63, d.ask, 1e-9)
        assertEquals(0.8, d.z, 1e-9)
        assertEquals(0.64, d.worseAsk, 1e-9)
        // $5 all-in at 63¢: 7 contracts cost 4.41 + fee ceil_cent(0.07·7·0.63·0.37) = 0.12.
        assertEquals(7, d.sized.contracts)
        assertEquals(4.53, d.sized.costUsd, 1e-9)
        assertNotNull(d.worse)
    }

    @Test
    fun heavyNoBuyingIsFadedWithYes() {
        val d = FlowFadeRule.evaluate(inputs(yes = 100.0, no = 900.0, yesAsk = 0.70, noAsk = 0.31), alreadyEntered = false)!!
        assertEquals("YES", d.side)
        assertEquals(0.70, d.ask, 1e-9)
        assertEquals(-0.8, d.z, 1e-9)
    }

    @Test
    fun thresholdIsThreeToOne() {
        // 750 vs 250 is exactly 0.5 and qualifies; 740 vs 260 does not.
        assertNotNull(FlowFadeRule.evaluate(inputs(yes = 750.0, no = 250.0), alreadyEntered = false))
        assertNull(FlowFadeRule.evaluate(inputs(yes = 740.0, no = 260.0), alreadyEntered = false))
        assertEquals(0.5, FlowFadeRule.imbalance(750.0, 250.0)!!, 1e-12)
        assertNull(FlowFadeRule.imbalance(0.0, 0.0))
        assertNull(FlowFadeRule.imbalance(-1.0, 5.0))
        assertNull(FlowFadeRule.imbalance(Double.NaN, 5.0))
    }

    @Test
    fun needsEnoughContracts() {
        assertNull(FlowFadeRule.evaluate(inputs(yes = 400.0, no = 50.0), alreadyEntered = false))
        assertNotNull(FlowFadeRule.evaluate(inputs(yes = 450.0, no = 50.0), alreadyEntered = false))
    }

    @Test
    fun onlyBetweenOneMinuteInAndThirtySecondsLeft() {
        assertNull(FlowFadeRule.evaluate(inputs(tte = 841L), alreadyEntered = false))
        assertNotNull(FlowFadeRule.evaluate(inputs(tte = 840L), alreadyEntered = false))
        assertNotNull(FlowFadeRule.evaluate(inputs(tte = 30L), alreadyEntered = false))
        assertNull(FlowFadeRule.evaluate(inputs(tte = 29L), alreadyEntered = false))
        assertNull(FlowFadeRule.evaluate(inputs(tte = null), alreadyEntered = false))
    }

    @Test
    fun askMustBeInsideTenToNinetyCents() {
        assertNull(FlowFadeRule.evaluate(inputs(noAsk = 0.91), alreadyEntered = false))
        assertNotNull(FlowFadeRule.evaluate(inputs(noAsk = 0.90), alreadyEntered = false))
        assertNotNull(FlowFadeRule.evaluate(inputs(noAsk = 0.10), alreadyEntered = false))
        assertNull(FlowFadeRule.evaluate(inputs(noAsk = 0.09), alreadyEntered = false))
        assertNull(FlowFadeRule.evaluate(inputs(noAsk = null), alreadyEntered = false))
    }

    @Test
    fun oneEntryPerMarketAndWatchedMarketsOnly() {
        assertNull(FlowFadeRule.evaluate(inputs(), alreadyEntered = true))
        assertNull(FlowFadeRule.evaluate(inputs(t = "KXETH15M-26OCT041430-30"), alreadyEntered = false))
    }

    @Test
    fun windowKeepsOnlyTheLastThirtySeconds() {
        val w = FlowWindow()
        w.onTrade(ticker, "yes", 300.0, 1_000L)
        w.onTrade(ticker, "no", 100.0, 11_000L)
        w.onTrade(ticker, "YES", 50.0, 21_000L)
        assertEquals(350.0 to 100.0, w.sums(ticker, 21_000L))
        // 31 s after the first print it has dropped out.
        assertEquals(50.0 to 100.0, w.sums(ticker, 31_001L))
        assertEquals(0.0 to 0.0, w.sums(ticker, 100_000L))
        assertEquals(0.0 to 0.0, w.sums("OTHER", 21_000L))
    }

    @Test
    fun windowIgnoresBadPrintsAndForgetsUnwatchedTickers() {
        val w = FlowWindow()
        w.onTrade(ticker, null, 10.0, 1L)
        w.onTrade(ticker, "maybe", 10.0, 1L)
        w.onTrade(ticker, "yes", null, 1L)
        w.onTrade(ticker, "yes", -5.0, 1L)
        w.onTrade(ticker, "yes", Double.NaN, 1L)
        assertEquals(0.0 to 0.0, w.sums(ticker, 1L))
        w.onTrade(ticker, "no", 7.5, 2L)
        w.onTrade("OLD", "yes", 9.0, 2L)
        w.retain(setOf(ticker))
        assertEquals(0.0 to 7.5, w.sums(ticker, 3L))
        assertEquals(0.0 to 0.0, w.sums("OLD", 3L))
    }

    @Test
    fun windowIsBoundedByPrintCount() {
        val w = FlowWindow(maxPrints = 3)
        repeat(5) { w.onTrade(ticker, "yes", 1.0, 10L) }
        assertEquals(3.0 to 0.0, w.sums(ticker, 10L))
    }

    @Test
    fun ledgerSettlesAFadeLikeAnyPaperBet() {
        val ledger = LateFavoriteLedger()
        val d = FlowFadeRule.evaluate(inputs(), alreadyEntered = false)!!
        assertNotNull(ledger.record(d))
        assertTrue(ledger.hasEntry(ticker))
        assertNull(FlowFadeRule.evaluate(inputs(), alreadyEntered = ledger.hasEntry(ticker)))
        ledger.settle(ticker, "no")
        val t = ledger.snapshot().totals
        assertEquals(1, t.wins)
        // 7 contracts pay $7.00 against a $4.53 cost.
        assertEquals(2.47, t.pnlUsd, 1e-9)
    }

    @Test
    fun summaryCopy() {
        val empty = FlowFadeSummary.of(LateFavoriteState())
        assertEquals(FlowFadeSummary.TITLE, empty.title)
        assertTrue(empty.title.startsWith("Flow fade"))
        assertEquals("0-0 · 0 / 1000 settled", empty.recordLine)
        assertEquals("Open: none", empty.openLine)

        val ledger = LateFavoriteLedger()
        ledger.record(FlowFadeRule.evaluate(inputs(), alreadyEntered = false)!!)
        assertEquals("Open: 26OCT041430-30 DOWN @ 63¢", FlowFadeSummary.of(ledger.snapshot()).openLine)
        ledger.settle(ticker, "no")
        val s = FlowFadeSummary.of(ledger.snapshot())
        assertTrue(s.recordLine.startsWith("1-0 (100.0%) at "))
        assertTrue(s.recordLine.endsWith("· 1 / 1000 settled"))
        assertEquals("P&L +$2.47 · +$2.47/bet", s.pnlLine)
        assertTrue(s.note.startsWith("Paper only."))
    }
}
