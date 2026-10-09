package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.local.paper.ScalpSchema
import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.decision.InMemoryScalpPersistence
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStats
import com.dirk.kalshiodds.decision.StrategyLadder
import com.dirk.kalshiodds.signal.notify.TradeEventPolicy
import com.dirk.kalshiodds.signal.paper.AutopilotMode
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.RealMoneyPhrase
import com.dirk.kalshiodds.signal.trade.RestingOrder
import com.dirk.kalshiodds.ui.HomeOpenBets
import com.dirk.kalshiodds.update.KashiReleasePolicy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.38 release gates (0.3.39: the Home Stop gates were removed with the Stop button). */
class ReleaseGate0338Test {

    // 0.3.40: the live-Autopilot dispatch gates were removed with the live path (Autopilot is paper-only);
    // see ReleaseGate0340PaperOnlyTest.

    @Test
    fun typedRealMoneyPhraseIsExact() {
        assertTrue(RealMoneyPhrase.matches("REAL MONEY"))
        assertTrue(RealMoneyPhrase.matches(" REAL MONEY "))
        assertFalse(RealMoneyPhrase.matches("real money"))
        assertFalse(RealMoneyPhrase.matches("REAL"))
        assertFalse(RealMoneyPhrase.matches(null))
    }

    @Test
    fun updaterPicksOnlyKashiDebugWithAppId() {
        val json = """
            [
              {"tag_name":"v0.3.38-debug","target_commitish":"kashi","draft":false,
                "body":"app-id: com.dirk.kalshiodds.kashi\n\nKalshi Trader 0.3.38",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.38-debug/DipHunter-debug.apk"}]},
              {"tag_name":"v0.3.39-debug","target_commitish":"main","draft":false,
                "body":"app-id: com.dirk.kalshiodds",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.39-debug/DipHunter-debug.apk"}]}
            ]
        """.trimIndent()
        val chosen = KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.37")
        assertEquals("v0.3.38-debug", chosen?.tag)
        assertNull(KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.38"))
    }

    @Test
    fun kellyUnderFiveDollarsIsSkipped() {
        assertTrue(AutopilotMinStake.below(4.99))
        assertFalse(AutopilotMinStake.below(5.00))
        assertTrue(AutopilotMinStake.REASON.contains("skipped"))
    }

    @Test
    fun paperAndScalpFeesRoundUpToTheCentPerOrder() {
        // 0.07 × C × P × (1 − P), rounded up to the cent, per order.
        assertEquals(0.02, KalshiFee.total(1, 0.50), 1e-9)
        assertEquals(0.18, KalshiFee.total(10, 0.50), 1e-9)
        assertEquals(1.75, KalshiFee.total(100, 0.50), 1e-9)
        assertEquals(0.02, ScalpRule.orderFee(1, 0.50), 1e-9)
        assertEquals(0.18, ScalpRule.orderFee(10, 0.50), 1e-9)
        assertEquals(0.15, ScalpRule.orderFee(10, 0.30), 1e-9) // 7 × 10 × 0.21 = 14.7¢ → 15¢
        assertEquals(0.01, ScalpRule.orderFee(1, 0.05), 1e-9)
        // Round trip at 50¢, 10 contracts, 1¢ spread = 1 + 1.8 + 1.8 = 4.6¢ per contract.
        assertEquals(0.046, ScalpRule.roundTripCost(0.50, 0.01), 1e-9)
    }

    // ---- Scalp book: fees both legs, latency, depth, no look-ahead ----

    private val close = 1_800_000_000_000L
    private fun q(
        nowMs: Long,
        yesBid: Double,
        yesAsk: Double,
        bidSize: Double = 500.0,
        askSize: Double = 500.0,
        spot: Double = 100_000.0,
        strike: Double = 100_000.0,
        bookAtMs: Long = nowMs
    ) = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT091015-15",
        nowMs = nowMs,
        closeMs = close,
        bookAtMs = bookAtMs,
        yesBid = yesBid,
        yesBidSize = bidSize,
        yesAsk = yesAsk,
        yesAskSize = askSize,
        spot = spot,
        strike = strike,
        sigmaPerSec = 3e-5
    )

    private fun book(): ScalpBook {
        val ids = AtomicInteger()
        // 0.3.40: pin the classic v1 params (no shadow variants) so these 0.3.38 gates keep testing the same rule.
        return ScalpBook(
            InMemoryScalpPersistence(),
            emptyList(),
            com.dirk.kalshiodds.decision.InMemoryScalpTuneStore(
                com.dirk.kalshiodds.decision.ScalpTuneState(paramsByCoin = mapOf("BTC" to com.dirk.kalshiodds.decision.ScalpParams.CLASSIC.id))
            )
        ) { "s${ids.incrementAndGet()}" }
    }

    @Test
    fun scalpEntersOnlyOnABigGapAfterFee() {
        val t0 = close - 600_000L
        // Spot at strike → fair 50¢. Ask 45¢: gap 5¢ − fee < 10¢ → no signal.
        assertNull(ScalpRule.entrySignal(q(t0, 0.44, 0.45)).first)
        // Ask 35¢: 15¢ − 1.6¢ fee ≥ 10¢ → YES signal.
        val sig = ScalpRule.entrySignal(q(t0, 0.34, 0.35)).first
        assertNotNull(sig)
        assertEquals("YES", sig!!.side)
        // Too wide a spread blocks.
        assertNull(ScalpRule.entrySignal(q(t0, 0.30, 0.35)).first)
        // Outside the 5–14 min window blocks.
        assertNull(ScalpRule.entrySignal(q(close - 200_000L, 0.34, 0.35)).first)
        // Stale book blocks.
        assertNull(ScalpRule.entrySignal(q(t0, 0.34, 0.35, bookAtMs = t0 - 60_000L)).first)
    }

    @Test
    fun scalpFillsOnLaterFreshBookAtThatPriceNotTheSignalBook() {
        val b = book()
        val t0 = close - 600_000L
        b.onQuote(q(t0, 0.34, 0.35), enabled = true)
        assertEquals(ScalpState.PENDING_ENTRY, b.snapshot().single().state)
        // Same book (no newer book) a second later: no fill yet — latency ≥ 1 tick.
        b.onQuote(q(t0 + 1_000L, 0.34, 0.35, bookAtMs = t0), enabled = true)
        assertEquals(ScalpState.PENDING_ENTRY, b.snapshot().single().state)
        // Newer book 3 s later at 36¢ (within 1¢ slippage): fills at 36¢, the later book's price.
        b.onQuote(q(t0 + 3_000L, 0.35, 0.36), enabled = true)
        val t = b.snapshot().single()
        assertEquals(ScalpState.OPEN, t.state)
        assertEquals(0.36, t.entryPrice!!, 1e-12)
        assertEquals(ScalpRule.orderFee(10, 0.36), t.entryFeeUsd, 1e-12)
    }

    @Test
    fun scalpNoFillWhenAskRunsAwayOrDepthShort() {
        val b = book()
        val t0 = close - 600_000L
        b.onQuote(q(t0, 0.34, 0.35), enabled = true)
        b.onQuote(q(t0 + 3_000L, 0.38, 0.39), enabled = true)
        assertEquals(ScalpState.NO_FILL, b.snapshot().single().state)
        val b2 = book()
        b2.onQuote(q(t0, 0.34, 0.35), enabled = true)
        b2.onQuote(q(t0 + 3_000L, 0.34, 0.35, askSize = 4.0), enabled = true)
        assertEquals(ScalpState.NO_FILL, b2.snapshot().single().state)
    }

    @Test
    fun scalpRoundTripPaysFeesOnBothLegs() {
        val b = book()
        val t0 = close - 600_000L
        b.onQuote(q(t0, 0.34, 0.35), enabled = true)
        b.onQuote(q(t0 + 3_000L, 0.34, 0.35), enabled = true) // fill at 35¢
        // Market rallies to 52¢ bid with fair 50¢: selling beats holding → exit decided.
        b.onQuote(q(t0 + 6_000L, 0.52, 0.53), enabled = true)
        assertEquals(ScalpState.PENDING_EXIT, b.snapshot().single().state)
        // Next fresh book ≥ 3 s later: sells 10 at that book's bid (51¢).
        b.onQuote(q(t0 + 9_000L, 0.51, 0.52), enabled = true)
        val t = b.snapshot().single()
        assertEquals(ScalpState.CLOSED, t.state)
        val expected = 10 * 0.51 - ScalpRule.orderFee(10, 0.51) - (10 * 0.35 + ScalpRule.orderFee(10, 0.35))
        assertEquals(expected, t.netUsd!!, 1e-9)
        assertTrue(t.entryFeeUsd > 0.0 && t.exitFeeUsd > 0.0)
        val s = ScalpStats.summary(b.snapshot())
        assertEquals(1, s.roundTrips)
        assertEquals(1.0, s.winRate!!, 1e-12)
    }

    @Test
    fun scalpExitSellsOnlyDisplayedBidSize() {
        val b = book()
        val t0 = close - 600_000L
        b.onQuote(q(t0, 0.34, 0.35), enabled = true)
        b.onQuote(q(t0 + 3_000L, 0.34, 0.35), enabled = true)
        b.onQuote(q(t0 + 6_000L, 0.52, 0.53), enabled = true)
        b.onQuote(q(t0 + 9_000L, 0.51, 0.52, bidSize = 4.0), enabled = true)
        val partial = b.snapshot().single()
        assertEquals(ScalpState.PENDING_EXIT, partial.state)
        assertEquals(4, partial.soldContracts)
        b.onQuote(q(t0 + 12_000L, 0.50, 0.51, bidSize = 100.0), enabled = true)
        val done = b.snapshot().single()
        assertEquals(ScalpState.CLOSED, done.state)
        val expectedProceeds = 4 * 0.51 + 6 * 0.50
        assertEquals(expectedProceeds, done.proceedsUsd, 1e-9)
        assertEquals(ScalpRule.orderFee(4, 0.51) + ScalpRule.orderFee(6, 0.50), done.exitFeeUsd, 1e-9)
    }

    @Test
    fun scalpHasNoLookAheadFutureQuotesNeverChangeEarlierDecisions() {
        // The decision at t0 must be identical whatever comes later.
        val t0 = close - 600_000L
        val a = book()
        val c = book()
        a.onQuote(q(t0, 0.34, 0.35), enabled = true)
        c.onQuote(q(t0, 0.34, 0.35), enabled = true)
        assertEquals(a.snapshot().single().copy(id = "x"), c.snapshot().single().copy(id = "x"))
        // Diverging futures only affect fills after the latency.
        a.onQuote(q(t0 + 3_000L, 0.34, 0.35), enabled = true)
        c.onQuote(q(t0 + 3_000L, 0.60, 0.61), enabled = true)
        assertEquals(0.35, a.snapshot().single().signalAsk, 1e-12)
        assertEquals(0.35, c.snapshot().single().signalAsk, 1e-12)
        assertEquals(ScalpState.OPEN, a.snapshot().single().state)
        assertEquals(ScalpState.NO_FILL, c.snapshot().single().state)
        // Entry signal is a pure function of the one quote it sees.
        val one = q(t0, 0.34, 0.35)
        assertEquals(ScalpRule.entrySignal(one), ScalpRule.entrySignal(one.copy()))
    }

    @Test
    fun scalpTimeStopAndSettlement() {
        val b = book()
        val t0 = close - 600_000L
        b.onQuote(q(t0, 0.34, 0.35), enabled = true)
        b.onQuote(q(t0 + 3_000L, 0.34, 0.35), enabled = true)
        // 20 s left → time stop decided; market then closes with no exit fill → settles.
        b.onQuote(q(close - 15_000L, 0.40, 0.41, bidSize = 0.0), enabled = true)
        assertEquals(ScalpState.PENDING_EXIT, b.snapshot().single().state)
        b.settle("KXBTC15M-26OCT091015-15", "yes", close + 10_000L)
        val t = b.snapshot().single()
        assertEquals(ScalpState.CLOSED, t.state)
        assertEquals(10 * 1.0 - (10 * 0.35 + ScalpRule.orderFee(10, 0.35)), t.netUsd!!, 1e-9)
    }

    @Test
    fun scalpPaperModeOffMeansNoNewEntries() {
        val b = book()
        b.onQuote(q(close - 600_000L, 0.34, 0.35), enabled = false)
        assertTrue(b.snapshot().isEmpty())
    }

    @Test
    fun scalpLadderBarIs300RoundTripsAndNeverLeavesPaper() {
        assertEquals(300, StrategyLadder.Id.SCALP.need)
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.Id.SCALP.maxStage)
        val winners = (1..400).map { StrategyLadder.Item("w${it % 200}", true, true, 0.05) }
        val st = StrategyLadder.status(StrategyLadder.Id.SCALP, StrategyLadder.Stage.PAPER, winners)
        assertFalse(st.eligibleForNext)
        assertTrue(st.reason.contains("paper-only"))
    }

    @Test
    fun scalpTableMigrationIsAdditive() {
        val sql = ScalpSchema.upgradeSql(7).joinToString("\n").uppercase()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"))
        assertFalse(sql.contains("DROP"))
        assertFalse(sql.contains("ALTER"))
        assertFalse(sql.contains("DELETE"))
        assertTrue(ScalpSchema.upgradeSql(8).isEmpty())
        assertEquals(8, com.dirk.kalshiodds.AppIdentity.DB_VERSION)
    }

    @Test
    fun tradeNotificationsFireOncePerEvent() {
        val p = TradeEventPolicy()
        assertTrue(p.firstTime(TradeEventPolicy.Kind.REAL_BET, "c1"))
        assertFalse(p.firstTime(TradeEventPolicy.Kind.REAL_BET, "c1"))
        assertTrue(p.firstTime(TradeEventPolicy.Kind.REAL_BET, "c2"))
        assertTrue(p.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "c1"))
        assertFalse(p.firstTime(TradeEventPolicy.Kind.ERROR_STOP, "c1"))
        // Balance: once per outage, re-armed only by a fresh balance.
        assertFalse(p.balanceChanged(needsBalance = true, fresh = true))
        assertTrue(p.balanceChanged(needsBalance = true, fresh = false))
        assertFalse(p.balanceChanged(needsBalance = true, fresh = false))
        assertFalse(p.balanceChanged(needsBalance = true, fresh = true))
        assertTrue(p.balanceChanged(needsBalance = true, fresh = false))
        assertFalse(p.balanceChanged(needsBalance = false, fresh = false))
    }

    @Test
    fun homeOpenBetsListsCancelForRestingAndCloseForPositions() {
        val rows = HomeOpenBets.rows(
            positions = listOf(LivePosition("KXBTC15M-X", "YES", 5.0, 2.5, 0.5, bestBid = 0.55)),
            resting = listOf(RestingOrder("o1", "KXETH15M-Y", "NO", 3.0, 0.0, 0.40, "resting")),
            paperFills = emptyList()
        )
        assertEquals(2, rows.size)
        val order = rows.first { it.kind == HomeOpenBets.Kind.RESTING_ORDER }
        assertEquals("Cancel", order.action)
        assertEquals("o1", order.orderId)
        assertTrue(order.realMoney)
        val pos = rows.first { it.kind == HomeOpenBets.Kind.LIVE_POSITION }
        assertEquals("Close", pos.action)
    }
}
