package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paper scalper: fills, exits, the scoreboard and the runner. Nothing here can place an order. */
class ScalperEngineTest {

    private val t = "KXBTC15M-26OCT091215-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> 0.0 }

    private fun closed(events: List<ScalpEvent>): ClosedScalp =
        events.filterIsInstance<ScalpEvent.Closed>().single().scalp

    // ---- resting entry ----

    @Test
    fun restingBidFillsOnlyAfterTheQueueAndItsOwnSizeHaveTraded() {
        val e = ScalperEngine()
        val posted = e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, queueAhead = 100.0, predictionCents = 0.4, expectedCents = 0.17, nowMs = 0L)!!
        assertEquals(QueueBucket.FRONT, posted.order.bucket)
        // A taker buying YES does not touch a YES bid.
        assertTrue(e.onTrade(t, takerYes = true, yesPrice = 0.50, contracts = 500.0, nowMs = 1_000L, exitQueue = noQueue).isEmpty())
        // 100 ahead + our 10: 105 traded at the price is not enough, 110 is.
        assertTrue(e.onTrade(t, false, 0.50, 105.0, 2_000L, noQueue).isEmpty())
        val filled = e.onTrade(t, false, 0.50, 5.0, 3_000L, noQueue)
        assertTrue(filled.single() is ScalpEvent.Filled)
        assertEquals(0 to 1, e.working(t))
    }

    @Test
    fun aPrintThroughThePriceFillsAtOnceWhateverTheQueue() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 5_000.0, 0.4, -1.7, 0L)
        assertTrue(e.onTrade(t, false, 0.49, 1.0, 1_000L, noQueue).single() is ScalpEvent.Filled)
    }

    @Test
    fun unfilledBidIsCancelledAfterTwentySeconds() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 100.0, 0.4, 0.17, 0L)
        assertTrue(e.onClock(t, 20_000L, 0.50, 0.49).isEmpty())
        assertTrue(e.onClock(t, 20_001L, 0.50, 0.49).single() is ScalpEvent.Unfilled)
        assertFalse(e.hasWork(t))
    }

    @Test
    fun downSideBidIsHitByTakersBuyingUp() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "NO", 0.40, 0.0, 0.4, 0.4, 0L)
        // NO bid at 40¢ = YES offer at 60¢: a taker buying YES at 60¢ for 10 fills it.
        assertTrue(e.onTrade(t, takerYes = false, yesPrice = 0.60, contracts = 50.0, nowMs = 500L, exitQueue = noQueue).isEmpty())
        assertTrue(e.onTrade(t, true, 0.60, 10.0, 1_000L, noQueue).single() is ScalpEvent.Filled)
        // Its offer rests at 41¢ NO = takers buying NO when YES trades at 59¢.
        val out = e.onTrade(t, false, 0.59, 10.0, 2_000L, noQueue)
        assertEquals(ExitKind.TARGET, closed(out).kind)
        assertEquals(0.10, closed(out).pnlUsd, 1e-9)
    }

    // ---- exits ----

    @Test
    fun targetPaysOneCentAContractWithNoFee() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 0.0, 0.4, 0.4, 0L)
        e.onTrade(t, false, 0.50, 10.0, 1_000L) { _, _ -> 30.0 }
        // Offer at 51¢ behind 30: 30 + our 10 must trade there.
        assertTrue(e.onTrade(t, true, 0.51, 39.0, 2_000L, noQueue).isEmpty())
        val c = closed(e.onTrade(t, true, 0.51, 1.0, 3_000L, noQueue))
        assertEquals(ExitKind.TARGET, c.kind)
        assertEquals(0.51, c.exitPrice, 1e-9)
        assertEquals(0.0, c.feeUsd, 0.0)
        assertEquals(0.10, c.pnlUsd, 1e-9)
        assertFalse(e.hasWork(t))
    }

    @Test
    fun stopSellsAtTheBidAndPaysTheTakerFee() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 0.0, 0.4, 0.4, 0L)
        e.onTrade(t, false, 0.50, 10.0, 1_000L, noQueue)
        assertTrue("3¢ down is not the stop", e.onClock(t, 2_000L, bidYes = 0.47, bidNo = 0.52).isEmpty())
        val c = closed(e.onClock(t, 3_000L, bidYes = 0.46, bidNo = 0.53))
        assertEquals(ExitKind.STOP, c.kind)
        val fee = KalshiFee.total(10, 0.46)
        assertEquals(fee, c.feeUsd, 1e-9)
        assertEquals((0.46 - 0.50) * 10 - fee, c.pnlUsd, 1e-9)
    }

    @Test
    fun timeOutSellsAtTheBidAfterTheStrategysLimit() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 0.0, 0.4, 0.4, 0L)
        e.onTrade(t, false, 0.50, 10.0, 1_000L, noQueue)
        assertTrue(e.onClock(t, 120_999L, 0.50, 0.49).isEmpty())
        val c = closed(e.onClock(t, 121_000L, 0.50, 0.49))
        assertEquals(ExitKind.TIMEOUT, c.kind)
        assertEquals(-KalshiFee.total(10, 0.50), c.pnlUsd, 1e-9)
        // With no bid to sell into the scalp waits.
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 0.0, 0.4, 0.4, 200_000L)
        e.onTrade(t, false, 0.50, 10.0, 201_000L, noQueue)
        assertTrue(e.onClock(t, 400_000L, bidYes = null, bidNo = 0.49).isEmpty())
        assertEquals(0 to 1, e.working(t))
    }

    @Test
    fun settlementPaysOpenScalpsAndDropsUnfilledBids() {
        val e = ScalperEngine()
        e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 0.0, 0.4, 0.4, 0L)
        e.onTrade(t, false, 0.50, 10.0, 1_000L, noQueue)
        e.post(ScalpStrategy.ML_REST, t, "NO", 0.45, 0.0, 0.4, 0.4, 2_000L)
        val out = e.settle(t, "yes", 900_000L)
        val c = closed(out)
        assertEquals(ExitKind.SETTLED, c.kind)
        assertEquals(5.0, c.pnlUsd, 1e-9)
        assertEquals(1, out.count { it is ScalpEvent.Unfilled })
        assertFalse(e.hasWork(t))
        assertTrue(e.settle(t, "maybe", 0L).isEmpty())
    }

    @Test
    fun aVoidBooksNothing() {
        val e = ScalperEngine()
        e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, 0L)
        assertEquals(0.0, closed(e.settle(t, "void", 1_000L)).pnlUsd, 0.0)
    }

    // ---- buy-now strategies ----

    @Test
    fun buyNowPaysTheAskAndTheFeeAndRestsItsOfferAtOnce() {
        val e = ScalperEngine()
        val out = e.buyNow(ScalpStrategy.MOMENTUM_SNIPER, t, "YES", 0.60, noQueue, 0L)
        assertEquals(2, out.size)
        val order = (out[0] as ScalpEvent.Posted).order
        val fee = KalshiFee.total(10, 0.60)
        assertEquals(fee, order.entryFeeUsd, 1e-9)
        assertNull("a buy-now entry has no entry queue", order.bucket)
        assertEquals(0 to 1, e.working(t))
        // +2¢ target: 62¢. The win is 2¢ × 10 minus the entry fee.
        val c = closed(e.onTrade(t, true, 0.62, 10.0, 5_000L, noQueue))
        assertEquals(ExitKind.TARGET, c.kind)
        assertEquals(0.20 - fee, c.pnlUsd, 1e-9)
    }

    @Test
    fun buyNowStopAndTimeOutPayBothFees() {
        val e = ScalperEngine()
        e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.60, noQueue, 0L)
        val c = closed(e.onClock(t, 4_000L, bidYes = 0.58, bidNo = 0.41))
        assertEquals(ExitKind.STOP, c.kind)
        assertEquals((0.58 - 0.60) * 10 - KalshiFee.total(10, 0.60) - KalshiFee.total(10, 0.58), c.pnlUsd, 1e-9)
        e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.60, noQueue, 10_000L)
        assertTrue(e.onClock(t, 39_999L, 0.59, 0.40).isEmpty())
        assertEquals(ExitKind.TIMEOUT, closed(e.onClock(t, 40_000L, 0.59, 0.40)).kind)
    }

    @Test
    fun eachStrategyHoldsSeveralPositionsAtOnceUpToItsLimit() {
        val e = ScalperEngine()
        repeat(ScalpStrategy.DIP_HUNTER.maxPerSide) {
            assertEquals(2, e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.60, noQueue, it * 2_000L).size)
        }
        assertTrue("the limit is per strategy and side", e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.60, noQueue, 99_000L).isEmpty())
        assertEquals(2, e.buyNow(ScalpStrategy.DIP_HUNTER, t, "NO", 0.41, noQueue, 99_000L).size)
        assertEquals(2, e.buyNow(ScalpStrategy.MOMENTUM_SNIPER, t, "YES", 0.60, noQueue, 99_000L).size)
        assertEquals(0 to (ScalpStrategy.DIP_HUNTER.maxPerSide + 2), e.working(t))
        assertNull("outside 10–90¢ nothing is posted", e.post(ScalpStrategy.ML_REST, t, "YES", 0.95, 0.0, 1.0, 1.0, 0L))
        assertTrue(e.buyNow(ScalpStrategy.DIP_HUNTER, "KXBTC15M-OTHER", "YES", 0.05, noQueue, 0L).isEmpty())
    }

    // ---- scoreboard ----

    @Test
    fun scoreboardSplitsByStrategyAndByQueue() {
        val saved = ArrayList<ScalperState>()
        var now = 0L
        val ledger = ScalperLedger(persist = { saved += it }, nowMs = { now }, persistEveryMs = 15_000L)
        val e = ScalperEngine()
        // ML scalp from the front of the queue: wins 10¢.
        val a = e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 50.0, 0.40, 0.28, 0L)!!
        ledger.apply(listOf(a))
        ledger.apply(e.onTrade(t, false, 0.49, 1.0, 1_000L, noQueue))
        ledger.apply(e.onTrade(t, true, 0.52, 1.0, 2_000L, noQueue))
        // ML scalp from deep in the queue, expected to lose there: stopped out.
        val b = e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 3_000.0, 0.40, -1.4, 3_000L)!!
        ledger.apply(listOf(b))
        ledger.apply(e.onTrade(t, false, 0.49, 1.0, 4_000L, noQueue))
        ledger.apply(e.onClock(t, 5_000L, 0.45, 0.54))
        // A dip-hunter buy that times out.
        ledger.apply(e.buyNow(ScalpStrategy.DIP_HUNTER, t, "NO", 0.40, noQueue, 6_000L))
        now = 60_000L
        ledger.apply(e.onClock(t, 60_000L, 0.59, 0.40))

        val s = ledger.snapshot()
        val all = s.stats(ScalperState.ALL)
        assertEquals(3, all.posted)
        assertEquals(3, all.closed)
        assertEquals(1, all.wins)
        assertEquals(1, all.targets)
        assertEquals(1, all.stops)
        assertEquals(1, all.timeouts)
        val ml = s.stats(ScalpStrategy.ML_REST.name)
        assertEquals(2, ml.closed)
        assertEquals(0.10 + ((0.45 - 0.50) * 10 - KalshiFee.total(10, 0.45)), ml.pnlUsd, 1e-9)
        assertEquals(0.10, s.stats(ScalperState.bucketKey(QueueBucket.FRONT)).pnlUsd, 1e-9)
        assertEquals(1, s.stats(ScalperState.bucketKey(QueueBucket.DEEP)).closed)
        assertEquals("only the order expected to pay in its queue", 1, s.stats(ScalperState.QUEUE_AWARE).closed)
        assertEquals(0.10, s.stats(ScalperState.QUEUE_AWARE).pnlUsd, 1e-9)
        val dip = s.stats(ScalpStrategy.DIP_HUNTER.name)
        assertEquals(-2.0 * KalshiFee.total(10, 0.40), dip.pnlUsd, 1e-9)
        assertEquals(3, s.recent.size)
        assertEquals("DIP_HUNTER", s.recent.first().strategy)
        assertTrue("saved, but not on every scalp", saved.size in 1..3)

        val copy = ScalperSummary.of(s, working = 2 to 1)
        assertEquals(ScalperSummary.TITLE, copy.title)
        assertTrue(copy.totalLine, copy.totalLine.startsWith("All strategies: −$"))
        assertTrue(copy.totalLine, copy.totalLine.contains("3 scalps (33% won)"))
        assertEquals(ScalpStrategy.values().size, copy.strategyLines.size)
        assertEquals(
            "strategies that traded come first, best on top",
            "Dip-hunter · buy now: −$0.34 · 1 (0% won) · −$0.34 each [-3.68 to -3.27¢]",
            copy.strategyLines[0]
        )
        assertEquals(
            "ML scalper · resting: −$0.58 · 2 (50% won) · −$0.29 each [-2.02 to +0.35¢]",
            copy.strategyLines[1]
        )
        assertEquals("Momentum-sniper · buy now: no scalps yet [-3.49 to -3.07¢]", copy.strategyLines[2])
        assertTrue(copy.queueLines.first(), copy.queueLines.first().contains("+$0.10 · 1 (100% won)"))
        assertTrue(copy.queueLines.any { it.startsWith("ML scalper, 0–100 ahead: +$0.10") })
        assertTrue(copy.workingLine, copy.workingLine.startsWith("Working now: 2 resting bids · 1 open scalps"))

        ledger.reset()
        assertEquals(0, ledger.snapshot().stats(ScalperState.ALL).closed)
    }

    // ---- runner ----

    private class Fake(var top: TopOfBook? = null, var levels: BookLevelSnapshot? = null)

    private fun runner(fake: Fake, model: ScalperModel?, open: Long = 0L) = PaperScalper(
        model = model,
        ledger = ScalperLedger(),
        topOfBook = { fake.top },
        bookLevels = { fake.levels },
        closeMs = { open + 900_000L }
    )

    /** A model that likes everything by [cents]. */
    private fun flatModel(cents: Double) = ScalperModel(
        features = PrintGrid.FEATURES,
        thetaCents = 0.0,
        trees = listOf(
            ScalperModel.Tree(intArrayOf(-1), doubleArrayOf(0.0), intArrayOf(-1), intArrayOf(-1), doubleArrayOf(cents))
        ),
        queuePenalty = listOf(0.0 to 0.0, 100.0 to 0.23, 250.0 to 0.46, 1_000.0 to 1.13)
    )

    /** One second of quiet two-sided prints at 50 / 51. */
    private fun quiet(s: PaperScalper, second: Int) {
        s.onTrade(t, "no", 0.50, 20.0, second * 1000L + 100L)
        s.onTrade(t, "yes", 0.51, 20.0, second * 1000L + 200L)
    }

    @Test
    fun mlScalperPostsOnBothSidesEveryFiveSecondsFromTheRealBidAndQueue() {
        val fake = Fake(top = TopOfBook(yesBid = 0.50, yesBidQty = 80.0, yesAsk = 0.51, noBid = 0.49, noBidQty = 4_000.0, noAsk = 0.50))
        val s = runner(fake, flatModel(0.5))
        for (sec in 0..66) quiet(s, sec)
        val st = s.ledger.snapshot()
        val ml = st.stats(ScalpStrategy.ML_REST.name)
        // Decisions at the end of seconds 60 and 65, both sides each time.
        assertEquals(4, ml.posted)
        assertEquals("YES bid sat behind 80", 2, st.stats(ScalperState.bucketKey(QueueBucket.FRONT)).posted)
        assertEquals("NO bid sat behind 4,000", 2, st.stats(ScalperState.bucketKey(QueueBucket.DEEP)).posted)
        assertEquals("0.5 − penalty(80) > 0 only for the YES bids", 2, st.stats(ScalperState.QUEUE_AWARE).posted)
        assertEquals("quiet market: none of the fast strategies fired", ml.posted, st.stats(ScalperState.ALL).posted)
    }

    @Test
    fun nothingIsPostedBeforeAMinuteOfPrintsOrWithoutAModelForTheMlScalper() {
        val fake = Fake()
        val s = runner(fake, model = null)
        for (sec in 30..120) quiet(s, sec)
        assertEquals("no model, quiet market: nothing to do", 0, s.ledger.snapshot().stats(ScalperState.ALL).posted)
        val late = runner(Fake(), flatModel(0.5))
        for (sec in 100..150) quiet(late, sec)
        assertEquals("joined at 100 s: not warm until 160 s", 0, late.ledger.snapshot().stats(ScalperState.ALL).posted)
    }

    @Test
    fun aSharpDropFiresTheDipHunterAndExtremeReversionOnThatSideAndMomentumOnTheOther() {
        val fake = Fake()
        val s = runner(fake, model = null)
        for (sec in 0..69) quiet(s, sec)
        // YES falls from 50/51 to 43/44 within a few seconds.
        for (sec in 70..75) {
            s.onTrade(t, "no", 0.43, 20.0, sec * 1000L + 100L)
            s.onTrade(t, "yes", 0.44, 20.0, sec * 1000L + 200L)
        }
        val st = s.ledger.snapshot()
        assertTrue("dip-hunter bought the falling YES side", st.stats(ScalpStrategy.DIP_HUNTER.name).posted >= 1)
        assertTrue("extreme reversion too (7¢ in under 30 s)", st.stats(ScalpStrategy.EXTREME_REVERSION.name).posted >= 1)
        assertTrue("momentum-sniper bought the rising NO side", st.stats(ScalpStrategy.MOMENTUM_SNIPER.name).posted >= 1)
        assertTrue("the resting twins posted too", st.stats(ScalpStrategy.DIP_HUNTER_REST.name).posted >= 1)
        assertEquals("buy-now entries are filled at once", st.stats(ScalpStrategy.DIP_HUNTER.name).posted, st.stats(ScalpStrategy.DIP_HUNTER.name).filled)
        val (bids, open) = s.working()
        assertTrue("several positions at once", open >= 3 && bids >= 1)
        // The window settles: everything open is paid and the scalper is flat.
        s.settle(t, "no", 900_000L)
        assertEquals(0 to 0, s.working())
        assertTrue(s.openTickers().isEmpty())
        assertTrue(s.ledger.snapshot().stats(ScalperState.ALL).settled >= 3)
    }

    @Test
    fun otherCoinsAndBadPrintsAreIgnored() {
        val s = runner(Fake(), flatModel(0.5))
        for (sec in 0..70) {
            s.onTrade("KXETH15M-26OCT091215-15", "no", 0.50, 20.0, sec * 1000L)
            s.onTrade(t, null, 0.50, 20.0, sec * 1000L)
            s.onTrade(t, "yes", 1.50, 20.0, sec * 1000L)
            s.onTrade(t, "yes", 0.50, -1.0, sec * 1000L)
        }
        assertEquals(0, s.ledger.snapshot().stats(ScalperState.ALL).posted)
    }
}
