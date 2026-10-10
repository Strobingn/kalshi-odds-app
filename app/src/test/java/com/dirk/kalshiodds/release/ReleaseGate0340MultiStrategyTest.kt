package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.InMemoryScalpPersistence
import com.dirk.kalshiodds.decision.InMemoryScalpTuneStore
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpBreakdown
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ScalpTuneState
import com.dirk.kalshiodds.decision.ScalpTuner
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.40: four paper scalp strategies side by side (fair-gap, dip-hunter, momentum-sniper, extreme-reversion). */
class ReleaseGate0340MultiStrategyTest {
    private val ticker = "KXBTC15M-26OCT091015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val sigma = 3e-5

    private fun q(nowMs: Long, yesBid: Double, yesAsk: Double, spot: Double = 100_000.0) =
        ScalpRule.Quote(ticker, nowMs, close, nowMs, yesBid, 500.0, yesAsk, 500.0, spot, 100_000.0, sigma)

    private fun snap(q: ScalpRule.Quote) = ScalpRule.Snap(q.nowMs, q.yesBid, q.yesAsk, ScalpRule.fairYes(q.spot, q.strike, q.sigmaPerSec, q.tauS))

    /** Spot that puts YES fair at [p] with [tauS] left. */
    private fun spotFor(p: Double, tauS: Double): Double {
        // invert Φ by bisection
        var lo = -6.0; var hi = 6.0
        repeat(80) { val m = (lo + hi) / 2; if (ScalpRule.normCdf(m) < p) lo = m else hi = m }
        return 100_000.0 * Math.exp(lo * sigma * Math.sqrt(ScalpRule.effectiveTauSeconds(tauS)))
    }

    private fun seed(s: ScalpStrategy) = ScalpParams.STRATEGY_SEEDS.getValue(s)

    @Test
    fun dipHunterBuysFastDropOnlyWhenSpotFairHolds() {
        val t = close - 600_000L
        val before = q(t, 0.44, 0.45)
        val now = q(t + 30_000L, 0.34, 0.35)
        val (sig, why) = ScalpRule.strategySignal(now, seed(ScalpStrategy.DIP_HUNTER), listOf("YES", "NO"), listOf(snap(before)))
        assertNotNull(why, sig)
        assertEquals("YES", sig!!.side)
        // Not enough history (10 s) → no entry.
        assertNull(ScalpRule.strategySignal(q(t + 10_000L, 0.34, 0.35), seed(ScalpStrategy.DIP_HUNTER), listOf("YES"), listOf(snap(before))).first)
        // Spot explains the drop (fair fell under the ask) → no entry.
        val explained = q(t + 30_000L, 0.34, 0.35, spot = spotFor(0.30, 570.0))
        assertNull(ScalpRule.strategySignal(explained, seed(ScalpStrategy.DIP_HUNTER), listOf("YES"), listOf(snap(before))).first)
    }

    @Test
    fun momentumSniperNeedsSpotConfirmation() {
        val t = close - 600_000L
        val before = q(t, 0.40, 0.41, spot = spotFor(0.40, 600.0))
        val confirmed = q(t + 30_000L, 0.52, 0.53, spot = spotFor(0.55, 570.0))
        assertNotNull(ScalpRule.strategySignal(confirmed, seed(ScalpStrategy.MOMENTUM), listOf("YES"), listOf(snap(before))).first)
        // Same price rise but spot flat → no entry.
        val unconfirmed = q(t + 30_000L, 0.52, 0.53, spot = spotFor(0.40, 570.0))
        assertNull(ScalpRule.strategySignal(unconfirmed, seed(ScalpStrategy.MOMENTUM), listOf("YES"), listOf(snap(before))).first)
        // A rise too small to beat spread + both fees is skipped with that reason.
        val small = q(t + 30_000L, 0.47, 0.48, spot = spotFor(0.50, 570.0))
        val (s2, why) = ScalpRule.strategySignal(small, seed(ScalpStrategy.MOMENTUM), listOf("YES"), listOf(snap(before)))
        assertNull(s2)
        assertTrue(why, why.contains("both fees"))
    }

    @Test
    fun extremeReversionBuysOnlyTheVeryCheapSideBelowFair() {
        val tau = 600.0
        val cheap = q(close - 600_000L, 0.04, 0.05, spot = spotFor(0.20, tau))
        val (sig, why) = ScalpRule.strategySignal(cheap, seed(ScalpStrategy.EXTREME_REVERSION), listOf("YES", "NO"), emptyList())
        assertNotNull(why, sig)
        assertEquals("YES", sig!!.side)
        // Fair-gap ignores sub-10¢ asks entirely.
        assertNull(ScalpRule.entrySignal(cheap, ScalpParams.CLASSIC.copy(minGap = 0.0)).first)
        // A 30¢ ask is not "very cheap".
        val mid = q(close - 600_000L, 0.29, 0.30, spot = spotFor(0.50, tau))
        assertNull(ScalpRule.strategySignal(mid, seed(ScalpStrategy.EXTREME_REVERSION), listOf("YES", "NO"), emptyList()).first)
    }

    private fun book(bankroll: Double = 1_000.0): ScalpBook {
        val ids = AtomicInteger()
        val pin = ScalpTuneState(paramsByCoin = mapOf("BTC" to ScalpParams.CLASSIC.id))
        return ScalpBook(InMemoryScalpPersistence(), emptyList(), InMemoryScalpTuneStore(pin), bankrollUsd = { bankroll }) { "m${ids.incrementAndGet()}" }
    }

    @Test
    fun strategiesRunConcurrentlyWithOneOpenPerStrategyPerSide() {
        val b = book()
        val t = close - 700_000L
        b.onQuote(q(t, 0.44, 0.45), true)
        b.onQuote(q(t + 30_000L, 0.34, 0.35), true) // fair-gap + dip-hunter both fire on YES
        b.onQuote(q(t + 33_000L, 0.34, 0.35), true) // both fill
        b.onQuote(q(t + 36_000L, 0.34, 0.35), true) // same signal again: no second open per strategy/side
        // 0.3.50: the sixth model (maker-first dip) also runs as a primary; check the taker models here.
        val open = b.snapshot().filter { it.state == ScalpState.OPEN && !it.isMaker }
        assertEquals(setOf(ScalpStrategy.FAIR_GAP, ScalpStrategy.DIP_HUNTER), open.map { it.strategy }.toSet())
        assertEquals(2, b.snapshot().count { !it.isMaker })
        open.groupBy { it.strategy to it.side }.values.forEach { assertEquals(1, it.size) }
        assertTrue(open.all { it.isPrimary })
    }

    @Test
    fun totalOpenCostNeverExceedsPaperBankroll() {
        // One 10-contract clip at 35¢ costs $3.50 + $0.16 fee. 0.3.50: each of six models gets bankroll/6;
        // a $5 bankroll (slice $0.83) fits none, $25 (slice $4.17) fits one clip per model.
        val t = close - 700_000L
        val tiny = book(bankroll = 5.0)
        tiny.onQuote(q(t, 0.44, 0.45), true)
        tiny.onQuote(q(t + 30_000L, 0.34, 0.35), true)
        assertEquals(0, tiny.snapshot().size)
        val b = book(bankroll = 25.0)
        b.onQuote(q(t, 0.44, 0.45), true)
        b.onQuote(q(t + 30_000L, 0.34, 0.35), true)
        b.onQuote(q(t + 33_000L, 0.34, 0.35), true)
        assertTrue(b.openCostUsd() <= 25.0 + 1e-9)
        com.dirk.kalshiodds.decision.ScalpModels.ALL.forEach { assertTrue(b.openCostUsd(it) <= 25.0 / 6 + 1e-9) }
        val roomy = book(bankroll = 1_000.0)
        roomy.onQuote(q(t, 0.44, 0.45), true)
        roomy.onQuote(q(t + 30_000L, 0.34, 0.35), true)
        assertEquals(2, roomy.snapshot().count { !it.isMaker })
    }

    private fun closed(window: Int, variant: ScalpParams, cents: Double, i: Int): ScalpTrade {
        val minute = 15 * window
        val hh = 10 + minute / 60
        val tk = String.format("KXBTC15M-26OCT%02d%02d%02d-15", 1 + hh / 24, hh % 24, minute % 60)
        val c = ScalpTicker.closeMs(tk)!!
        return ScalpTrade(
            id = "$window-${variant.id}-$i", ticker = tk, side = "YES", state = ScalpState.CLOSED,
            signalAtMs = c - 600_000L, signalAsk = 0.4, fairAtSignal = 0.5, contracts = 10, entryPrice = 0.4,
            entryAtMs = c - 597_000L, closedAtMs = c - 500_000L, netUsd = cents / 100.0 * 10,
            ruleVersion = "${ScalpRule.VERSION}|${variant.id}|S"
        )
    }

    @Test
    fun eachStrategyIsTunedIndependently() {
        val dipCur = seed(ScalpStrategy.DIP_HUNTER)
        val dipAlt = ScalpParams.STRATEGY_GRID.first { it.strategy == ScalpStrategy.DIP_HUNTER && it != dipCur }
        val momo = seed(ScalpStrategy.MOMENTUM)
        val data = ArrayList<ScalpTrade>()
        for (w in 0 until 20) repeat(4) { i ->
            val early = w < 12
            data += closed(w, dipCur, if (early) -1.0 else -0.5, i)
            data += closed(w, dipAlt, if (early) 2.0 else 1.0, i)
            data += closed(w, momo, 3.0, i) // only one momentum variant: nothing to switch to
        }
        val st = ScalpTuner.tune(data, ScalpTuneState(), 1L)
        assertEquals(dipAlt.id, st.paramsFor("BTC", ScalpStrategy.DIP_HUNTER).id)
        assertEquals(momo.id, st.paramsFor("BTC", ScalpStrategy.MOMENTUM).id)
        assertEquals(ScalpParams.seedFor("BTC").id, st.paramsFor("BTC").id) // fair-gap untouched
        assertEquals(1.0, st.oosCentsByCoin["BTC:dip"]!!, 1e-9)
        assertEquals(3.0, st.oosCentsByCoin["BTC:momo"]!!, 1e-9)
        assertEquals(1, st.version)
        // Strategy params never leak across strategies.
        assertFalse(ScalpTuneState(paramsByCoin = mapOf("BTC:dip" to ScalpParams.CLASSIC.id))
            .paramsFor("BTC", ScalpStrategy.DIP_HUNTER).strategy != ScalpStrategy.DIP_HUNTER)
    }

    @Test
    fun scorecardHasALinePerStrategyWithCoinAndHour() {
        val trades = listOf(
            closed(0, ScalpParams.CLASSIC, 3.0, 0),
            closed(0, seed(ScalpStrategy.DIP_HUNTER), -2.0, 0),
            closed(4, seed(ScalpStrategy.DIP_HUNTER), 0.0, 1),
            closed(1, seed(ScalpStrategy.EXTREME_REVERSION), 1.0, 0)
        )
        val rows = ScalpBreakdown.byStrategy(trades).associateBy { it.key }
        assertEquals(2, rows["Dip-hunter"]!!.n)
        assertEquals(0, rows["Dip-hunter"]!!.wins) // breakeven after both fees is not a win
        assertEquals(1, rows["Fair-gap"]!!.wins)
        val lines = ScalpBreakdown.lines(trades)
        ScalpStrategy.values().forEach { s -> assertTrue(s.label, lines.any { it.startsWith(s.label) }) }
        assertTrue(lines.any { it.startsWith("Momentum-sniper · no closed round trips yet") })
        assertTrue(lines.any { it.startsWith("  BTC") } && lines.any { it.startsWith("  09 ET") } && lines.any { it.startsWith("  10 ET") })
    }
}
