package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.TakerCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

/** Explain's facts and prompt, the order-file lookup behind them, and the daily review's numbers and store. */
class InsightFactsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }
    private val ticker = "KXBTC15M-26OCT221215-15"
    private val close = TakerCost.closeEpochMs(ticker)!!
    private val open = close - 900_000L

    private val model = ScalperModel.parse(
        """{"kind":"classifier","features":["mid","spread"],"theta_cents":-1.5,
            "calibration":{"x":[0.0,1.0],"y":[0.0,1.0]},"cents_by_chance":{"x":[0.0,1.0],"y":[-4.0,1.0]},
            "trees":[{"f":[0,-1,-1],"t":[0.5,0,0],"l":[1,-1,-1],"r":[2,-1,-1],"v":[0,-0.4,0.6],"iv":[0.1,-0.4,0.6]}]}"""
    )

    private fun order(st: ScalpStrategy, id: Long, features: FloatArray?) = ScalpOrder(
        id = id, strategy = st, ticker = ticker, side = "YES", price = 0.46, contracts = 10, queueAhead = 120.0,
        queueKnown = true, postedAtMs = open + 200_000L, predictionCents = 0.4, expectedCents = 0.2,
        context = ScalpContext(bid = 0.46, ask = 0.48, bidQty = 120.0, askQty = 80.0, move10 = 0.01, move30 = -0.02, features = features)
    )

    private fun closed(o: ScalpOrder, exit: Double, kind: ExitKind, pnl: Double) =
        ClosedScalp(o, exit, kind, feeUsd = 0.0, pnlUsd = pnl, filledAtMs = open + 210_000L, closedAtMs = open + 250_000L)

    @Test
    fun theOrderRowIsFoundAgainAndItsFactsBecomeAGroundedPrompt() {
        val log = ScalpTradeLog(tmp.newFolder("scalper"), run = 5L, io = direct)
        val o = order(ScalpStrategy.ML_REST, 7L, floatArrayOf(0.47f, 0.02f))
        val c = closed(o, 0.47, ExitKind.TARGET, 0.10)
        val other = closed(order(ScalpStrategy.DIP_HUNTER, 8L, null), 0.42, ExitKind.STOP, -0.40)
        log.add(listOf(ScalpTrade.of(c, 5L), ScalpTrade.of(other, 5L)), listOf(
            c.closedAtMs to ScalpTradeLog.orderRow(o, c, c.closedAtMs, 5L),
            other.closedAtMs to ScalpTradeLog.orderRow(other.order, other, other.closedAtMs, 5L)
        ))
        assertTrue(log.flushNow())
        val t = ScalpTrade.of(c, 5L)
        val d = log.orderFor(t)
        assertNotNull(d)
        assertEquals(0.46, d!!.bid!!, 1e-9)
        assertEquals(80.0, d.askQty!!, 1e-9)
        assertTrue(d.features!!.contentEquals(floatArrayOf(0.47f, 0.02f)))
        assertNull("the dip hunter has no model inputs", log.orderFor(ScalpTrade.of(other, 5L))!!.features)

        val f = TradeExplainer.facts(t, d, model)
        assertEquals(210, f.enteredSecond)
        assertEquals(40L, f.holdSeconds)
        assertEquals(1.0, f.pnlCentsPerContract, 1e-9)
        assertEquals(model.probability(d.features!!)!!, f.modelChance!!, 1e-12)
        assertEquals("mid", f.pushes.first().name)
        assertEquals(-0.5, f.pushes.first().push, 1e-12)
        val p = TradeExplainer.prompt(f)
        for (want in listOf("ML scalper", "UP x10 at 46c", "minute 3:30", "target hit", "after 40 s", "+$0.10", "bid 46c", "ask 48c",
                "120 contracts on the bid", "chance this order makes money", "it acts at -1.50c", "the side's mid price")) {
            assertTrue("prompt has \"$want\":\n$p", p.contains(want, ignoreCase = true) || p.contains(want))
        }
        assertFalse("no invented field", p.contains("null"))
    }

    @Test
    fun aTradeFromAnEarlierModelSaysSoInsteadOfGuessing() {
        val o = order(ScalpStrategy.ML_REST, 1L, FloatArray(23) { 0.1f })
        val t = ScalpTrade.of(closed(o, 0.47, ExitKind.TARGET, 0.1), 1L)
        val f = TradeExplainer.facts(t, OrderDetail(0.46, 0.48, null, null, null, null, FloatArray(23) { 0.1f }), model)
        assertNull(f.modelCents)
        assertTrue(f.modelNote!!.contains("earlier model with 23 inputs"))
        assertTrue(TradeExplainer.prompt(f).contains("Model breakdown: not available"))
        val noOrder = TradeExplainer.facts(t, null, model)
        assertTrue(noOrder.modelNote!!.contains("not on file"))
    }

    @Test
    fun theDayIsSummedByStrategyMinutePriceAndExit() {
        // Strategies the arena runs (a retired one would be left out, as on the arena's screens).
        val trades = listOf(
            ScalpTrade.of(closed(order(ScalpStrategy.KASHI_FAV_BASE, 1L, null), 0.47, ExitKind.TARGET, 0.10), 1L),
            ScalpTrade.of(closed(order(ScalpStrategy.KASHI_FAV_BASE, 2L, null), 0.42, ExitKind.STOP, -0.40), 1L),
            ScalpTrade.of(closed(order(ScalpStrategy.LATE_EDGE, 3L, null), 0.48, ExitKind.TIMEOUT, 0.20), 1L),
            ScalpTrade.of(closed(order(ScalpStrategy.DIP_HUNTER, 4L, null), 0.48, ExitKind.TIMEOUT, 9.0), 1L)
        )
        val s = DailyReview.stats("2026-10-22", trades)
        assertEquals(3, s.trades)
        assertEquals(2, s.wins)
        assertEquals(-0.10, s.netUsd, 1e-9)
        assertEquals(listOf(0.10, -0.30, -0.10), s.curve)
        assertEquals("best first", ScalpStrategy.LATE_EDGE.name, s.strategies.first().name)
        assertTrue("the retired dip hunter is left out", s.strategies.none { it.name == ScalpStrategy.DIP_HUNTER.name })
        assertEquals(listOf("min 4"), s.byMinute.map { it.label })
        assertEquals(listOf("40-60c"), s.byPrice.map { it.label })
        assertEquals(listOf("TARGET", "STOP", "TIMEOUT"), s.byExit.map { it.label })
        assertTrue("fewer than 10 ML trades: no bins", s.mlBins.isEmpty())
        val p = DailyReview.prompt(s, partial = true)
        assertTrue(p.contains("so far"))
        assertTrue(p.contains("3 trades, 67% won, net -$0.10"))
        assertTrue(p.contains("min 4 -$0.10 (3)"))
    }

    @Test
    fun mlBinsCompareWhatItPredictedWithWhatItMade() {
        val trades = (1..20).map { i ->
            val o = order(ScalpStrategy.ML_REST, i.toLong(), null).copy(predictionCents = i / 10.0)
            ScalpTrade.of(closed(o, 0.47, ExitKind.TARGET, if (i > 10) 0.10 else -0.10), 1L)
        }
        val bins = DailyReview.stats("2026-10-22", trades).mlBins
        assertEquals("bins of at least 5 trades", 4, bins.size)
        assertTrue(bins.first().predictedCents < bins.last().predictedCents)
        assertEquals(-1.0, bins.first().madeCents, 1e-9)
        assertEquals(1.0, bins.last().madeCents, 1e-9)
        assertEquals(20, bins.sumOf { it.trades })
    }

    @Test
    fun theStoreKeepsOneReviewADayNewestFirst() {
        val store = DailyReview.Store(java.io.File(tmp.root, "reviews.json"))
        val s = DailyReview.stats("2026-10-21", emptyList())
        store.put(SavedReview("2026-10-21", 1L, "On-phone · q", "partial", s, partial = true))
        assertFalse(store.hasFinal("2026-10-21"))
        store.put(SavedReview("2026-10-21", 2L, "On-phone · q", "final", s))
        store.put(SavedReview("2026-10-22", 3L, "Groq x", "today", s.copy(day = "2026-10-22"), partial = true))
        val all = store.all()
        assertEquals(listOf("2026-10-22", "2026-10-21"), all.map { it.day })
        assertEquals("final", all[1].text)
        assertTrue(store.hasFinal("2026-10-21"))
        assertEquals("2026-10-21", DailyReview.yesterday(close))
    }
}
