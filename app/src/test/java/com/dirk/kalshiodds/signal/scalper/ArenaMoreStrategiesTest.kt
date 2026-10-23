package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.engine.TopOfBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fifteen strategies added in 1.9.4: each fires on its own signal and stays quiet without it. Paper only. */
class ArenaMoreStrategiesTest {

    private val t = "KXBTC15M-26OCT091215-15"

    private val added = listOf(
        ScalpStrategy.STRETCH_FADE, ScalpStrategy.SQUEEZE_BREAKOUT, ScalpStrategy.RANGE_FLOOR,
        ScalpStrategy.TREND_PULLBACK, ScalpStrategy.FLOW_FOLLOW, ScalpStrategy.WHALE_FOLLOW,
        ScalpStrategy.QUIET_MAKER, ScalpStrategy.OPENING_DRIVE, ScalpStrategy.LATE_TIEBREAK,
        ScalpStrategy.HEAVY_FAVORITE, ScalpStrategy.PANIC_FADE, ScalpStrategy.STEADY_CLIMB,
        ScalpStrategy.PENNY_SCALPER, ScalpStrategy.BOOK_IMBALANCE, ScalpStrategy.LATE_COMEBACK
    )

    private fun runner(top: () -> TopOfBook? = { null }) = PaperScalper(
        model = null,
        ledger = ScalperLedger(),
        topOfBook = { top() },
        bookLevels = { null },
        closeMs = { 900_000L }, strategies = ScalpStrategy.values().toList()
    )

    /** One second of two-sided prints: takers sell YES at [bid] and buy YES at [ask]. */
    private fun second(s: PaperScalper, sec: Int, bid: Double, ask: Double, size: Double = 20.0) {
        s.onTrade(t, "no", bid, size, sec * 1000L + 100L)
        s.onTrade(t, "yes", ask, size, sec * 1000L + 200L)
    }

    private fun cents(v: Double) = Math.round(v * 100.0) / 100.0

    private fun posted(s: PaperScalper, st: ScalpStrategy) = s.ledger.snapshot().stats(st.name).posted

    @Test
    fun fifteenMoreMakeThirtyEachNewAndOnItsOwnRow() {
        assertEquals(57, ScalpStrategy.values().size)
        assertEquals("added after the first fifteen, so those keep their places", added, ScalpStrategy.values().drop(15).take(15))
        assertTrue(added.all { it.historyCents == null && it.origin == "Claude 1.9.4" })
        val lines = ScalperSummary.of(ScalperState.fresh(), shown = ScalpStrategy.values().toList()).strategyLines
        assertTrue(lines.contains("Whale follow · buy now: no scalps yet [new · Claude 1.9.4]"))
        // Holds have no exit; scalps have a target, a stop and a time-out.
        for (st in added) {
            if (st.hold) assertTrue(st.name, st.targetDollars == 0.0 && st.timeoutMs == 0L)
            else assertTrue(st.name, st.targetDollars > 0.0 && st.stopDollars > 0.0 && st.timeoutMs > 0L)
        }
    }

    @Test
    fun stretchFadeBuysAMidFarUnderItsMinuteAverage() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.60, 0.61)
        assertEquals(0, posted(s, ScalpStrategy.STRETCH_FADE))
        for (sec in 70..75) second(s, sec, 0.55, 0.56)
        assertTrue(s.engine.workingFor(ScalpStrategy.STRETCH_FADE, t, "YES") >= 1)
    }

    @Test
    fun squeezeBreakoutBuysTheBreakOfTwoQuietMinutes() {
        val quiet = runner()
        for (sec in 0..140) second(quiet, sec, 0.50, 0.51)
        assertEquals("no break, no buy", 0, posted(quiet, ScalpStrategy.SQUEEZE_BREAKOUT))

        val s = runner()
        for (sec in 0..129) second(s, sec, 0.50, 0.51)
        for (sec in 130..134) second(s, sec, 0.52, 0.53)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.SQUEEZE_BREAKOUT, t, "YES"))
        assertEquals("the NO side broke down, not up", 0, s.engine.workingFor(ScalpStrategy.SQUEEZE_BREAKOUT, t, "NO"))
    }

    @Test
    fun aFewPrintsAreNoSqueeze() {
        // Prints for the first 20 s, then nothing until a jump: the two "quiet" minutes were never quoted.
        val s = runner()
        for (sec in 0..20) second(s, sec, 0.50, 0.51)
        for (sec in 125..134) second(s, sec, 0.52, 0.53)
        assertEquals(0, posted(s, ScalpStrategy.SQUEEZE_BREAKOUT))
        assertEquals(0, posted(s, ScalpStrategy.RANGE_FLOOR))
    }

    @Test
    fun rangeFloorRestsABidOnTheLowOfARange() {
        val s = runner()
        // 20 s at 50/51, 20 s at 55/56, and so on: a 5¢ range. Seconds 120–139 sit on its floor.
        for (sec in 0..125) {
            if ((sec / 20) % 2 == 0) second(s, sec, 0.50, 0.51) else second(s, sec, 0.55, 0.56)
        }
        assertTrue(s.engine.workingFor(ScalpStrategy.RANGE_FLOOR, t, "YES") >= 1)
        assertEquals("NO is on its ceiling", 0, s.engine.workingFor(ScalpStrategy.RANGE_FLOOR, t, "NO"))
    }

    @Test
    fun trendPullbackBuysTheDipInsideAClimb() {
        val s = runner()
        for (sec in 0..60) second(s, sec, 0.40, 0.41)
        for (sec in 61..180) {
            val b = cents(0.40 + 0.10 * (sec - 60) / 120.0)
            second(s, sec, b, cents(b + 0.01))
        }
        assertEquals("no dip yet", 0, s.engine.workingFor(ScalpStrategy.TREND_PULLBACK, t, "YES"))
        for (sec in 181..190) second(s, sec, 0.48, 0.49)
        assertTrue(s.engine.workingFor(ScalpStrategy.TREND_PULLBACK, t, "YES") >= 1)
    }

    @Test
    fun flowFollowGoesWithTheTakers() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.50, 0.51)
        for (sec in 70..75) {
            s.onTrade(t, "yes", 0.51, 400.0, sec * 1000L + 300L)
            s.onTrade(t, "no", 0.50, 20.0, sec * 1000L + 400L)
        }
        for (sec in 76..81) second(s, sec, 0.50, 0.51)
        assertTrue(s.engine.workingFor(ScalpStrategy.FLOW_FOLLOW, t, "YES") >= 1)
        assertEquals("flow fade takes the other side", 0, s.engine.workingFor(ScalpStrategy.FLOW_FOLLOW, t, "NO"))
    }

    @Test
    fun whaleFollowBuysAfterOneHugePrint() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.50, 0.51)
        assertEquals(0, posted(s, ScalpStrategy.WHALE_FOLLOW))
        s.onTrade(t, "yes", 0.51, 1_500.0, 70_300L)
        for (sec in 71..73) second(s, sec, 0.50, 0.51)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.WHALE_FOLLOW, t, "YES"))
    }

    @Test
    fun quietMakerRestsOnlyWhenThePriceStandsStill() {
        val flat = runner()
        for (sec in 0..95) second(flat, sec, 0.50, 0.51)
        assertTrue(posted(flat, ScalpStrategy.QUIET_MAKER) >= 1)

        val busy = runner()
        for (sec in 0..95) if (sec % 2 == 0) second(busy, sec, 0.50, 0.51) else second(busy, sec, 0.51, 0.52)
        assertEquals(0, posted(busy, ScalpStrategy.QUIET_MAKER))
    }

    @Test
    fun openingDriveHoldsASideThatRanInTheFirstMinutes() {
        val s = runner()
        for (sec in 0..60) second(s, sec, 0.50, 0.51)
        for (sec in 61..100) {
            val b = cents(0.50 + 0.08 * (sec - 60) / 40.0)
            second(s, sec, b, cents(b + 0.01))
        }
        for (sec in 101..119) second(s, sec, 0.58, 0.59)
        assertEquals("not before minute 2", 0, posted(s, ScalpStrategy.OPENING_DRIVE))
        for (sec in 120..124) second(s, sec, 0.58, 0.59)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.OPENING_DRIVE, t, "YES"))
    }

    @Test
    fun lateTiebreakAndHeavyFavoriteWaitForMinuteTwelve() {
        val tie = runner()
        for (sec in 0..719) second(tie, sec, 0.50, 0.51)
        assertEquals(0, posted(tie, ScalpStrategy.LATE_TIEBREAK))
        for (sec in 720..730) second(tie, sec, 0.53, 0.54)
        assertEquals(1, tie.engine.workingFor(ScalpStrategy.LATE_TIEBREAK, t, "YES"))

        val fav = runner()
        for (sec in 0..715) second(fav, sec, 0.94, 0.95)
        assertEquals(0, posted(fav, ScalpStrategy.HEAVY_FAVORITE))
        for (sec in 716..725) second(fav, sec, 0.94, 0.95)
        assertEquals(1, fav.engine.workingFor(ScalpStrategy.HEAVY_FAVORITE, t, "YES"))
        assertEquals("5¢ is no heavy favorite", 0, fav.engine.workingFor(ScalpStrategy.HEAVY_FAVORITE, t, "NO"))
    }

    @Test
    fun panicFadeNeedsBothTheFallAndTheSelling() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.60, 0.61)
        // Down 5¢ with 150 contracts a second sold into YES (takers buying NO).
        for (sec in 70..74) {
            s.onTrade(t, "no", 0.55, 150.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", 0.56, 20.0, sec * 1000L + 200L)
        }
        for (sec in 75..76) second(s, sec, 0.55, 0.56)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.PANIC_FADE, t, "YES"))

        val thin = runner()
        for (sec in 0..69) second(thin, sec, 0.60, 0.61)
        for (sec in 70..76) second(thin, sec, 0.55, 0.56)
        assertEquals("the same fall on thin selling", 0, posted(thin, ScalpStrategy.PANIC_FADE))
    }

    @Test
    fun steadyClimbBuysAMoveThatKeepsGoing() {
        val s = runner()
        for (sec in 0..60) second(s, sec, 0.50, 0.51)
        for (sec in 61..130) {
            val b = Math.round((0.50 + 0.0012 * (sec - 60)) * 10_000.0) / 10_000.0
            second(s, sec, b, b + 0.01)
        }
        // The climb carries on through the target, so a buy may already have closed: count the buys.
        assertTrue(posted(s, ScalpStrategy.STEADY_CLIMB) >= 1)
        assertEquals("NO fell the whole time", 0, s.engine.workingFor(ScalpStrategy.STEADY_CLIMB, t, "NO"))
    }

    @Test
    fun pennyScalperNeedsAOneCentSpreadAndAShortQueue() {
        fun book(qty: Double) = TopOfBook(
            yesBid = 0.50, yesBidQty = qty, yesAsk = 0.51, yesAskQty = qty,
            noBid = 0.49, noBidQty = qty, noAsk = 0.50, noAskQty = qty
        )
        val short = runner { book(100.0) }
        for (sec in 0..70) second(short, sec, 0.50, 0.51)
        assertTrue(posted(short, ScalpStrategy.PENNY_SCALPER) >= 1)

        val long = runner { book(5_000.0) }
        for (sec in 0..70) second(long, sec, 0.50, 0.51)
        assertEquals("5,000 ahead is too deep", 0, posted(long, ScalpStrategy.PENNY_SCALPER))

        val noBook = runner()
        for (sec in 0..70) second(noBook, sec, 0.50, 0.51)
        assertEquals("the prints show no queue", 0, posted(noBook, ScalpStrategy.PENNY_SCALPER))
    }

    @Test
    fun bookImbalanceBuysTheSideWithTheHeavyBid() {
        val heavy = TopOfBook(
            yesBid = 0.50, yesBidQty = 1_200.0, yesAsk = 0.51, yesAskQty = 200.0,
            noBid = 0.49, noBidQty = 200.0, noAsk = 0.50, noAskQty = 1_200.0
        )
        val s = runner { heavy }
        for (sec in 0..70) second(s, sec, 0.50, 0.51)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.BOOK_IMBALANCE, t, "YES"))
        assertEquals(0, s.engine.workingFor(ScalpStrategy.BOOK_IMBALANCE, t, "NO"))
    }

    @Test
    fun lateComebackHoldsAnUnderdogThatIsClimbing() {
        val s = runner()
        // YES at 70/71 all window: NO is a 29–30¢ underdog.
        for (sec in 0..599) second(s, sec, 0.70, 0.71)
        assertEquals(0, posted(s, ScalpStrategy.LATE_COMEBACK))
        for (sec in 600..630) {
            val b = cents(0.70 - 0.05 * minOf(sec - 600, 25) / 25.0)
            second(s, sec, b, cents(b + 0.01))
        }
        assertEquals(1, s.engine.workingFor(ScalpStrategy.LATE_COMEBACK, t, "NO"))
        assertEquals(0, s.engine.workingFor(ScalpStrategy.LATE_COMEBACK, t, "YES"))
    }
}
