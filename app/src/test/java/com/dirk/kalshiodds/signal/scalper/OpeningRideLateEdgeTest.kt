package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening ride (first minute, the side Bitcoin leans to, +10¢ / trailing 3¢) and the late edge
 * (last 45 s, fair from the settlement average so far, ask under fair by the fee plus 2¢, hold).
 */
class OpeningRideLateEdgeTest {

    /**
     * The fee and P&L maths here is written for a 10-contract order, so these tests name that size instead of
     * riding on [ScalpTuning.DEFAULT_CONTRACTS] (100 since 1.23.0) and changing meaning when the default moves.
     */
    private fun engine(tuning: ScalpTuning = ScalpTuning()) =
        ScalperEngine(ScalperEngine.Config(contracts = 10), tuning)


    private val t = "KXBTC15M-26OCT091215-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> null }

    private fun runner(fair: () -> Double?) = PaperScalper(
        model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null },
        closeMs = { 900_000L }, fairYes = { _, _ -> fair() }, strategies = ScalpStrategy.values().toList(),
        engine = engine()
    )

    /** Seconds [from]..[to] of two-sided prints: takers sell YES at [bid] and buy YES at [ask]. */
    private fun prints(s: PaperScalper, from: Int, to: Int, bid: Double, ask: Double) {
        for (sec in from..to) {
            s.onTrade(t, "no", bid, 20.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", ask, 20.0, sec * 1000L + 200L)
        }
    }

    private fun posted(s: PaperScalper, st: ScalpStrategy) = s.ledger.snapshot().stats(st.name).posted

    @Test
    fun theRideTakesTenCentsOrTrailsThreeCentsUnderItsBestBid() {
        val st = ScalpStrategy.OPENING_RIDE
        assertTrue(st.taker && st.trail && st.opening && !st.hold)
        assertEquals(0.10, st.targetDollars, 0.0)
        assertEquals(0.03, st.stopDollars, 0.0)
        val e = engine()
        e.buyNow(st, t, "YES", 0.52, noQueue, 0L)
        assertTrue("2¢ down is not 3¢", e.onClock(t, 1_000L, 0.50, 0.49).isEmpty())
        assertTrue("it climbs: the stop climbs with it", e.onClock(t, 2_000L, 0.58, 0.41).isEmpty())
        assertTrue(e.onClock(t, 3_000L, 0.56, 0.43).isEmpty())
        val c = e.onClock(t, 4_000L, 0.55, 0.44).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals("3¢ under the 58¢ high, a profit", ExitKind.STOP, c.kind)
        assertEquals(0.55, c.exitPrice, 1e-9)
        assertEquals(0.03 * 10 - KalshiFee.total(10, 0.52) - KalshiFee.total(10, 0.55), c.pnlUsd, 1e-9)

        val e2 = engine()
        e2.buyNow(st, t, "YES", 0.52, noQueue, 0L)
        val won = e2.onTrade(t, true, 0.63, 50.0, 5_000L, noQueue).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals(ExitKind.TARGET, won.kind)
        assertEquals(0.62, won.exitPrice, 1e-9)
        // A plain stop still hangs from the entry.
        val e3 = engine()
        e3.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, 0L)
        e3.onClock(t, 1_000L, 0.60, 0.39)
        assertTrue("dip-hunter's 2¢ stop is under its entry, not its best bid", e3.onClock(t, 2_000L, 0.57, 0.42).isEmpty())
    }

    @Test
    fun itBuysTheSideBitcoinLeansToAsSoonAsTheWindowOpensOnceAWindow() {
        val up = runner { 0.55 }
        prints(up, 0, 3, 0.51, 0.52)
        assertEquals("bought YES at the open", 1, up.engine.workingFor(ScalpStrategy.OPENING_RIDE, t, "YES"))
        assertEquals(0.52, up.engine.openPositions(t).single { it.order.strategy == ScalpStrategy.OPENING_RIDE }.order.price, 1e-9)
        // Falls 3¢: stopped. It does not buy again this window.
        prints(up, 4, 6, 0.48, 0.49)
        assertEquals(0, up.engine.workingFor(ScalpStrategy.OPENING_RIDE, t, "YES"))
        prints(up, 7, 50, 0.51, 0.52)
        assertEquals("once a window", 1, posted(up, ScalpStrategy.OPENING_RIDE))
        assertEquals(1, up.ledger.snapshot().stats(ScalpStrategy.OPENING_RIDE.name).stops)

        val down = runner { 0.40 }
        prints(down, 0, 3, 0.51, 0.52)
        assertEquals("Bitcoin under the strike: NO", 1, down.engine.workingFor(ScalpStrategy.OPENING_RIDE, t, "NO"))
        assertEquals(0, down.engine.workingFor(ScalpStrategy.OPENING_RIDE, t, "YES"))
    }

    @Test
    fun noLeanNoBuyAndNothingAfterTheFirstMinute() {
        val flat = runner { 0.505 }
        prints(flat, 0, 120, 0.50, 0.51)
        assertEquals("50.5% is under the 51% lean", 0, posted(flat, ScalpStrategy.OPENING_RIDE))
        // No index: the market's own favourite, but only a clear one.
        val quiet = runner { null }
        prints(quiet, 0, 60, 0.50, 0.51)
        assertEquals(0, posted(quiet, ScalpStrategy.OPENING_RIDE))
        val leaning = runner { null }
        prints(leaning, 0, 5, 0.55, 0.56)
        assertEquals(1, leaning.engine.workingFor(ScalpStrategy.OPENING_RIDE, t, "YES"))
        // A lean that shows only after minute 1 is too late.
        var fair = 0.50
        val late = runner { fair }
        prints(late, 0, 70, 0.50, 0.51)
        fair = 0.60
        prints(late, 71, 120, 0.50, 0.51)
        assertEquals(0, posted(late, ScalpStrategy.OPENING_RIDE))
        // Your lean setting moves it.
        val strict = runner { 0.55 }
        strict.engine.tuning.set(ScalpStrategy.OPENING_RIDE, ScalpKnobs(trigger = 60.0))
        prints(strict, 0, 60, 0.51, 0.52)
        assertEquals("55% is under your 60%", 0, posted(strict, ScalpStrategy.OPENING_RIDE))
    }

    @Test
    fun lateEdgeBuysInTheLast60SecondsWhenFairBeatsTheAskByTheFeePlusOneCent() {
        val st = ScalpStrategy.LATE_EDGE
        assertTrue(st.hold && st.taker && st.stopDollars == 0.0)
        assertEquals(ScalpStrategy.FavSpec(5, 60, 0.80, 0.01), st.fav)
        val s = runner { 0.97 }
        prints(s, 0, 835, 0.90, 0.92)
        assertEquals("66 s left: too early", 0, posted(s, st))
        prints(s, 836, 845, 0.90, 0.92)
        assertEquals(1, s.engine.workingFor(st, t, "YES"))
        prints(s, 861, 894, 0.90, 0.92)
        assertEquals("once a window", 1, posted(s, st))
        s.settle(t, "yes", 900_000L)
        assertEquals((1.0 - 0.92) * 10 - KalshiFee.total(10, 0.92), s.ledger.snapshot().stats(st.name).pnlUsd, 1e-9)

        // Fair 93% against a 92¢ ask: 1¢ does not cover the fee plus 1¢.
        val thin = runner { 0.93 }
        prints(thin, 0, 894, 0.90, 0.92)
        assertEquals(0, posted(thin, st))
        // Under 80% fair it never buys, however cheap.
        val coin = runner { 0.75 }
        prints(coin, 0, 894, 0.50, 0.52)
        assertEquals(0, posted(coin, st))
        assertEquals("nor the other side at 25%", 0, coin.engine.workingFor(st, t, "NO"))
    }

    @Test
    fun aMakerFeeIsChargedOnRestingFillsOnlyWhenKalshiListsOne() {
        fun scalp(rate: Double): ClosedScalp {
            val e = engine()
            e.makerFeeRate = rate
            e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 50.0, 0.4, 0.3, 0L)
            e.onTrade(t, false, 0.49, 10.0, 1_000L, noQueue)
            return e.onTrade(t, true, 0.52, 10.0, 2_000L, noQueue).filterIsInstance<ScalpEvent.Closed>().single().scalp
        }
        assertEquals("free while Kalshi says quadratic", 0.10, scalp(0.0).pnlUsd, 1e-9)
        val paid = scalp(KalshiFee.MAKER_COEFFICIENT)
        val fees = KalshiFee.total(10, 0.50, KalshiFee.MAKER_COEFFICIENT) + KalshiFee.total(10, 0.51, KalshiFee.MAKER_COEFFICIENT)
        assertEquals(fees, paid.feeUsd, 1e-9)
        assertEquals(0.10 - fees, paid.pnlUsd, 1e-9)
    }
}
