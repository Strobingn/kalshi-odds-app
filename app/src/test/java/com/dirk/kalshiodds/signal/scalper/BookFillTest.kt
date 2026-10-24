package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TakerCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paper orders fill only against the book (1.20.4), and Explain states facts only. */
class BookFillTest {
    private val fee = KalshiFee.TAKER_COEFFICIENT

    @Test
    fun aBuyWalksTheOffersAndStopsAtTheLimitAndTheDepth() {
        val offers = listOf(0.82 to 2_992.0, 0.83 to 1_000.0, 0.90 to 50_000.0)
        val f = BookFill.buy(offers, 36_000, fee, limit = 0.87)!!
        assertEquals("only what is offered up to 87¢", 3_992, f.contracts)
        assertEquals((2_992 * 0.82 + 1_000 * 0.83) / 3_992, f.avgPrice, 1e-12)
        assertEquals(KalshiFee.total(2_992, 0.82, fee) + KalshiFee.total(1_000, 0.83, fee), f.feeUsd, 1e-12)
        assertEquals(10, BookFill.buy(offers, 10, fee, 0.87)!!.contracts)
        assertNull("nothing under the limit", BookFill.buy(offers, 10, fee, 0.80))
    }

    @Test
    fun aSaleWalksTheBidsAndSellsTheRestAtTheFloor() {
        val bids = listOf(0.60 to 100.0, 0.59 to 100.0)
        val f = BookFill.sell(bids, 300, fee, floorPrice = 0.55)
        assertEquals(300, f.contracts)
        assertEquals((100 * 0.60 + 100 * 0.59 + 100 * 0.55) / 300, f.avgPrice, 1e-12)
    }

    @Test
    fun levelsComeFromTheBookOnlyWhenItAgreesWithTheQuote() {
        val book = BookLevelSnapshot(yes = listOf(0.17 to 500.0, 0.16 to 100.0), no = listOf(0.82 to 300.0))
        // A YES offer at 18¢ is the NO bid at 82¢.
        assertEquals(listOf(0.18 to 300.0), BookFill.askLevels(book, "YES", 0.18)!!.map { Math.round(it.first * 100) / 100.0 to it.second })
        assertNull("the book drifted from the quote", BookFill.askLevels(book, "YES", 0.30))
        assertEquals(0.17, BookFill.bidLevels(book, "YES", 0.17)!!.first().first, 1e-12)
        assertNull(BookFill.askLevels(null, "YES", 0.18))
    }

    @Test
    fun theEngineFillsABigOrderOnlyAsFarAsTheBookGoes() {
        val e = ScalperEngine(tuning = ScalpTuning())
        val ev = e.buyNow(
            ScalpStrategy.LATE_COMEBACK, "T", "YES", 0.30, { _, _ -> null }, 0L, contracts = 36_000,
            askLevels = listOf(0.30 to 2_992.0, 0.31 to 500.0)
        )
        val o = (ev.first() as ScalpEvent.Posted).order
        assertEquals(3_492, o.contracts)
        assertTrue(o.price > 0.30)
        // No sizes from the book: the unknown queue at the ask, not 36,000.
        val e2 = ScalperEngine(tuning = ScalpTuning())
        val o2 = (e2.buyNow(ScalpStrategy.LATE_COMEBACK, "T", "YES", 0.30, { _, _ -> null }, 0L, contracts = 36_000).first() as ScalpEvent.Posted).order
        assertEquals(e2.config.unknownQueue.toInt(), o2.contracts)
    }

    @Test
    fun aStopSellsIntoTheBidsShown() {
        val e = ScalperEngine(tuning = ScalpTuning())
        e.buyNow(ScalpStrategy.MICRO_BRTI_PULSE, "T", "YES", 0.50, { _, _ -> null }, 0L, contracts = 200, targetPrice = 0.60, askLevels = listOf(0.50 to 1_000.0))
        val out = e.onClock("T", 1_000L, 0.46, 0.50) { side -> if (side == "YES") listOf(0.46 to 50.0, 0.45 to 50.0) else null }
        val c = (out.single() as ScalpEvent.Closed).scalp
        assertEquals(ExitKind.STOP, c.kind)
        assertEquals((50 * 0.46 + 50 * 0.45 + 100 * 0.41) / 200, c.exitPrice, 1e-4)
    }

    @Test
    fun theExplanationIsFactsOnly() {
        val ticker = "KXBTC15M-26OCT231545-45"
        val close = TakerCost.closeEpochMs(ticker)!!
        val t = ScalpTrade(
            closedAtMs = close + 30_000L, run = 1, id = 7, strategy = ScalpStrategy.EARLY_FAVORITE.name, ticker = ticker, side = "NO",
            contracts = 36_000, entry = 0.82, exit = 1.0, kind = "SETTLED", entryFeeUsd = 371.96, exitFeeUsd = 0.0, pnlUsd = 6_108.04,
            postedAtMs = close - 182_000L, filledAtMs = close - 182_000L, queueAhead = 0.0, queueKnown = true, predictionCents = 0.0, expectedCents = 0.0
        )
        val order = OrderDetail(bid = 0.81, ask = 0.82, bidQty = 8_845.0, askQty = 2_992.0, move10 = -0.015, move30 = 0.065, features = null)
        val f = TradeExplainer.facts(t, order, null)
        val record = ScalpStats(closed = 40, wins = 30, pnlUsd = 120.0)
        val lines = TradeExplainer.story(f, record)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("Minutes 3–10, the side costs 65–85¢."))
        assertTrue(text.contains("12.0× what the ask showed"))
        assertTrue(text.contains("not part of this strategy's rule"))
        assertTrue(text.contains("One trade says nothing about skill or luck"))
        assertTrue(text.contains("40 trades"))
        assertFalse("no claim of skill", text.contains("suggests skill"))
        assertFalse(text.lowercase().contains("because"))
    }
}
