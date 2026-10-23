package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.scalper.PaperScalper
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalperEngine
import com.dirk.kalshiodds.signal.scalper.ScalperLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fleet through the real paper scalper and engine: orders, settlement, and legs on other markets. */
class FleetPaperScalperTest {
    private val ticker = "KXBTC15M-26OCT231300-00"
    private val close = 1_792_774_800_000L // 2026-10-23 13:00 ET = 17:00 UTC
    private val k = 100_000.0

    private class StubFeed(var index: IndexView?, var ext: FleetExternal = FleetExternal()) : FleetFeed {
        val legs = ArrayList<Pair<ScalpStrategy, OffBookLeg>>()
        override fun index(ticker: String, closeMs: Long, nowMs: Long) = index
        override fun external(ticker: String, closeMs: Long, nowMs: Long) = ext
        override fun openOffBook(strategy: ScalpStrategy, leg: OffBookLeg, note: String, nowMs: Long) { legs += strategy to leg }
    }

    private fun scalper(feed: FleetFeed, strategies: List<ScalpStrategy>, top: () -> TopOfBook, trades: MutableList<ScalpTrade>) = PaperScalper(
        model = null,
        ledger = ScalperLedger(record = { t, _ -> trades.addAll(t) }, hourOf = { 0 }),
        topOfBook = { top() }, bookLevels = { null }, closeMs = { close },
        engine = ScalperEngine(), strategies = strategies, fleetFeed = feed
    )

    @After
    fun reset() {
        Fleet.makersHaltedUntilMs = 0L
    }

    /** Prints a quiet two-sided tape at [yes] from [fromS] to [toS] seconds into the window. */
    private fun tape(sc: PaperScalper, fromS: Int, toS: Int, yes: Double) {
        for (s in fromS..toS) {
            val t = close - 900_000L + s * 1000L + 500L
            sc.onTrade(ticker, if (s % 2 == 0) "yes" else "no", yes, 5.0, t)
        }
    }

    @Test
    fun lockedInBuysThroughTheEngineAndSettles() {
        val trades = ArrayList<ScalpTrade>()
        val feed = StubFeed(IndexView(k + 40, k, 2e-5, DoubleArray(600) { k + 40 }, DoubleArray(45) { k + 40 }))
        val sc = scalper(feed, listOf(ScalpStrategy.FLEET_LOCKED_IN), { TopOfBook(0.78, 500.0, 0.80, 500.0, 0.20, 500.0, 0.22, 500.0) }, trades)
        tape(sc, 820, 886, 0.79)
        assertTrue("an open position", sc.openTickers().contains(ticker))
        sc.settle(ticker, "yes", close + 2_000L)
        val t = trades.single { it.strategy == ScalpStrategy.FLEET_LOCKED_IN.name }
        assertEquals("YES", t.side)
        assertTrue(t.pnlUsd > 0)
        assertTrue("Kelly-sized, not the arena's default 10", t.contracts != 10)
    }

    @Test
    fun aDominancePairOpensItsHourlyLeg() {
        val trades = ArrayList<ScalpTrade>()
        val hourly = listOf(VenueQuote("KXBTCD-26OCT2313-T100049.99", 0.50, 0.48, 0.40, 0.38, k + 50, close))
        val feed = StubFeed(IndexView(k, k, 2e-5, DoubleArray(600) { k }, DoubleArray(0)), FleetExternal(hourly = hourly))
        val sc = scalper(feed, listOf(ScalpStrategy.FLEET_HOURLY_DOMINANCE), { TopOfBook(0.53, 500.0, 0.55, 500.0, 0.45, 500.0, 0.47, 500.0) }, trades)
        tape(sc, 100, 110, 0.54)
        val (st, leg) = feed.legs.first()
        assertEquals(ScalpStrategy.FLEET_HOURLY_DOMINANCE, st)
        assertEquals("NO", leg.side)
        assertEquals("KXBTCD-26OCT2313-T100049.99", leg.market)
        assertTrue(sc.openTickers().contains(ticker))
    }

    @Test
    fun thePerpHedgeOpensOnlyWhenTheBinaryFills() {
        val trades = ArrayList<ScalpTrade>()
        val spot = k * 1.0006
        val now0 = close - 900_000L
        val perp = PerpQuote(9.999, 10.001, 0.0001, 0.0012, Long.MAX_VALUE / 2)
        val feed = StubFeed(IndexView(spot, k, 2e-5, DoubleArray(600) { spot }, DoubleArray(0)), FleetExternal(perp = perp))
        val sc = scalper(feed, listOf(ScalpStrategy.FLEET_PERP_HEDGE), { TopOfBook(0.55, 50.0, 0.57, 50.0, 0.43, 50.0, 0.45, 50.0) }, trades)
        // Prints at the ask only: the resting YES bid at 55¢ does not fill.
        for (s in 100..110) sc.onTrade(ticker, "yes", 0.57, 5.0, now0 + s * 1000L)
        assertTrue("no hedge before the fill", feed.legs.isEmpty())
        // Sellers hit 54¢ (through the bid): the binary fills, the hedge opens short.
        sc.onTrade(ticker, "no", 0.54, 500.0, now0 + 112_000L)
        val (_, leg) = feed.legs.single()
        assertEquals(OffBookLeg.Venue.PERP, leg.venue)
        assertEquals("NO", leg.side)
    }
}
