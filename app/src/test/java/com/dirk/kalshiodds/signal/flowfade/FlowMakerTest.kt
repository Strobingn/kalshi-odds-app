package com.dirk.kalshiodds.signal.flowfade

import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMakerTest {

    private val ticker = "KXBTC15M-26OCT041430-30"

    private fun inputs(
        yes: Double = 900.0,
        no: Double = 100.0,
        tte: Long? = 600L,
        yesBid: Double? = 0.37,
        noBid: Double? = 0.62,
        yesQty: Double? = 1_200.0,
        noQty: Double? = 2_500.0,
        t: String = ticker
    ) = FlowFadeRule.MakerInputs(t, 1_000L, tte, yes, no, yesBid, noBid, yesQty, noQty)

    private fun order(side: String, price: Double, queue: Double, at: Long = 0L) =
        FlowFadeRule.Order(ticker, side, price, queue, at, 600L, if (side == "NO") 0.8 else -0.8)

    @Test
    fun heavyYesBuyingRestsOnTheNoBid() {
        val o = FlowFadeRule.order(inputs(), alreadyEntered = false)!!
        assertEquals("NO", o.side)
        assertEquals(0.62, o.price, 1e-9)
        assertEquals(2_500.0, o.queueAhead, 1e-9)
        assertEquals(0.8, o.imbalance, 1e-9)
        assertEquals(1_000L, o.postedAtMs)
    }

    @Test
    fun heavyNoBuyingRestsOnTheYesBid() {
        val o = FlowFadeRule.order(inputs(yes = 100.0, no = 900.0, yesBid = 0.69, noBid = 0.30), alreadyEntered = false)!!
        assertEquals("YES", o.side)
        assertEquals(0.69, o.price, 1e-9)
        assertEquals(1_200.0, o.queueAhead, 1e-9)
    }

    @Test
    fun unknownBookSizeUsesTheDefaultQueue() {
        val o = FlowFadeRule.order(inputs(noQty = null), alreadyEntered = false)!!
        assertEquals(FlowFadeRule.DEFAULT_QUEUE_AHEAD, o.queueAhead, 1e-9)
        assertEquals(0.0, FlowFadeRule.order(inputs(noQty = 0.0), alreadyEntered = false)!!.queueAhead, 1e-9)
    }

    @Test
    fun sameGatesAsTheSignal() {
        assertNull(FlowFadeRule.order(inputs(), alreadyEntered = true))
        assertNull(FlowFadeRule.order(inputs(yes = 740.0, no = 260.0), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(yes = 400.0, no = 50.0), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(tte = 841L), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(tte = 29L), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(tte = null), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(noBid = 0.91), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(noBid = 0.09), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(noBid = null), alreadyEntered = false))
        assertNull(FlowFadeRule.order(inputs(t = "KXETH15M-26OCT041430-30"), alreadyEntered = false))
    }

    @Test
    fun restingSizeHasNoFeeAndTheStressLineHasOne() {
        // $5 at a 63¢ bid: 7 contracts cost $4.41, no fee.
        val free = FlowFadeRule.sizeResting(0.63)!!
        assertEquals(7, free.contracts)
        assertEquals(4.41, free.costUsd, 1e-9)
        assertEquals(0.0, free.feeUsd, 1e-9)
        // Maker fee 0.0175·7·0.63·0.37 = 2.86¢, rounded up to 3¢.
        val fee = FlowFadeRule.sizeResting(0.63, FlowFadeRule.MAKER_FEE_STRESS)!!
        assertEquals(7, fee.contracts)
        assertEquals(0.03, fee.feeUsd, 1e-9)
        assertEquals(4.44, fee.costUsd, 1e-9)
        assertNull(FlowFadeRule.sizeResting(null))
        assertNull(FlowFadeRule.sizeResting(0.0))
    }

    @Test
    fun noBidFillsOnlyAfterTheQueueTradesAtItsPrice() {
        val book = MakerPaperBook()
        assertTrue(book.post(order("NO", 0.63, queue = 1_000.0)))
        // A NO bid at 63¢ is hit by takers buying YES at 37¢.
        assertNull(book.onTrade(ticker, "yes", 0.37, 600.0, 1_000L))
        assertNull(book.onTrade(ticker, "yes", 0.37, 400.0, 2_000L))   // exactly the queue: still ahead of us
        val filled = book.onTrade(ticker, "yes", 0.37, 1.0, 3_000L)
        assertNotNull(filled)
        assertEquals("NO", filled!!.side)
        assertNull(book.pending(ticker))
        assertNull(book.onTrade(ticker, "yes", 0.37, 5_000.0, 4_000L))
    }

    @Test
    fun printsThatDoNotReachTheBidAreIgnored() {
        val book = MakerPaperBook()
        book.post(order("NO", 0.63, queue = 100.0))
        assertNull(book.onTrade(ticker, "no", 0.37, 9_000.0, 1L))      // taker bought NO: not selling our side
        assertNull(book.onTrade(ticker, "yes", 0.36, 9_000.0, 2L))     // YES bought cheaper: our level untouched
        assertNull(book.onTrade(ticker, null, 0.37, 9_000.0, 3L))
        assertNull(book.onTrade(ticker, "yes", null, 9_000.0, 4L))
        assertNull(book.onTrade(ticker, "yes", 0.37, Double.NaN, 5L))
        assertNull(book.onTrade("OTHER", "yes", 0.37, 9_000.0, 6L))
        assertNotNull(book.pending(ticker))
    }

    @Test
    fun aPrintThroughThePriceFillsAtOnce() {
        val book = MakerPaperBook()
        book.post(order("NO", 0.63, queue = 50_000.0))
        assertNotNull(book.onTrade(ticker, "yes", 0.38, 1.0, 1L))

        val yes = MakerPaperBook()
        yes.post(order("YES", 0.40, queue = 50_000.0))
        assertNull(yes.onTrade(ticker, "no", 0.41, 9_000.0, 1L))       // YES still trading above our bid
        assertNull(yes.onTrade(ticker, "no", 0.40, 100.0, 2L))         // at our bid, queue not through
        assertNotNull(yes.onTrade(ticker, "no", 0.39, 1.0, 3L))        // traded below our bid
    }

    @Test
    fun yesBidFillsFromTakersBuyingNo() {
        val book = MakerPaperBook()
        book.post(order("YES", 0.40, queue = 500.0))
        assertNull(book.onTrade(ticker, "yes", 0.40, 9_000.0, 1L))
        assertNull(book.onTrade(ticker, "no", 0.40, 500.0, 2L))
        assertNotNull(book.onTrade(ticker, "no", 0.40, 10.0, 3L))
    }

    @Test
    fun oneOrderPerTickerAndItExpiresAfterThirtySeconds() {
        val book = MakerPaperBook()
        assertTrue(book.post(order("NO", 0.63, queue = 10.0, at = 1_000L)))
        assertFalse(book.post(order("NO", 0.60, queue = 10.0, at = 2_000L)))
        assertNull(book.expire(ticker, 31_000L))
        assertNotNull(book.pending(ticker))
        // A print after the cancel time cannot fill it.
        assertNull(book.onTrade(ticker, "yes", 0.38, 100.0, 31_001L))
        assertNull(book.pending(ticker))

        assertTrue(book.post(order("NO", 0.63, queue = 10.0, at = 40_000L)))
        assertNotNull(book.expire(ticker, 70_001L))
        assertNull(book.pending(ticker))
        book.post(order("NO", 0.63, queue = 10.0, at = 80_000L))
        book.retain(emptySet())
        assertNull(book.pending(ticker))
    }

    @Test
    fun aFillBecomesALedgerRowAtTheBidWithNoFee() {
        val o = order("NO", 0.63, queue = 0.0)
        val d = FlowFadeRule.filled(o, filledAtMs = 9_000L)!!
        assertEquals("NO", d.side)
        assertEquals(0.63, d.ask, 1e-9)
        assertEquals(9_000L, d.nowMs)
        assertEquals(7, d.sized.contracts)
        assertEquals(4.41, d.sized.costUsd, 1e-9)
        assertEquals(4.44, d.worse!!.costUsd, 1e-9)

        val ledger = LateFavoriteLedger()
        assertNotNull(ledger.record(d))
        ledger.settle(ticker, "no")
        val t = ledger.snapshot().totals
        assertEquals(1, t.wins)
        assertEquals(2.59, t.pnlUsd, 1e-9)        // 7 × $1 − $4.41
        assertEquals(2.56, t.pnlWorseUsd, 1e-9)   // with the 3¢ maker fee

        val s = FlowFadeSummary.of(ledger.snapshot())
        assertEquals("Flow fade (resting bid) · PAPER", s.title)
        assertEquals("1-0 (100.0%) at a 63¢ bid · 1 / 1000 settled", s.recordLine)
        assertEquals("P&L +$2.59 · +$2.59/bet", s.pnlLine)
        assertEquals("With a maker fee +$2.56 · +$2.56/bet", s.worseLine)
        assertTrue(s.note.contains("not proven"))
        assertTrue(s.note.contains("retired"))
    }
}
