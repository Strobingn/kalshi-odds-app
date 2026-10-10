package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookFillSink
import com.dirk.kalshiodds.signal.paper.PaperLimitFill
import com.dirk.kalshiodds.signal.paper.PaperOrder
import com.dirk.kalshiodds.signal.paper.PaperOrderBook
import com.dirk.kalshiodds.signal.paper.PaperSideQuote
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.43 paper limit orders (owner touch-fill rule) and the CF-reprice paper scalp strategy. */
class ReleaseGate0343PaperLimitTest {
    private val t = "KXBTC15M-26OCT091445-45"
    private var now = 1_000_000L
    private var ids = 0

    private fun paper() = PaperBook(idFactory = { "f${ids++}" }, nowMs = { now }).also { it.reset(10_000.0) }
    private fun orders(p: PaperBook) = PaperOrderBook(sink = PaperBookFillSink(p), idFactory = { "o${ids++}" }, nowMs = { now })
    private fun q(ask: Double?, askSz: Double?, bid: Double? = null, bidSz: Double? = null) = PaperSideQuote.top(ask, askSz, bid, bidSz)

    @Test
    fun buyLimitDoesNotFillWhileAskAboveLimit() {
        val p = paper(); val b = orders(p)
        val o = b.submit(t, "YES", "BUY", 0.33, 10, now + 600_000, q(0.34, 100.0)).order!!
        assertEquals(0, o.filledQty)
        b.onQuote(t, { q(0.34, 100.0) }, now + 1)
        assertEquals(0, b.orders.value.single().filledQty)
        assertEquals(0, p.openContracts(t, "YES"))
    }

    @Test
    fun buyLimitTouchFillsAtExactlyTheLimitUpToDepth() {
        val p = paper(); val b = orders(p)
        b.submit(t, "YES", "BUY", 0.33, 10, now + 600_000, q(0.35, 100.0))
        // Market reaches the limit (ask == 33¢) with 4 displayed → partial fill of 4 at exactly 33¢ (no improvement).
        b.onQuote(t, { q(0.33, 4.0) }, now + 1)
        val o = b.orders.value.single()
        assertEquals(4, o.filledQty); assertTrue(o.isOpen); assertEquals(4, o.makerQty)
        assertEquals(4, p.openContracts(t, "YES"))
        assertEquals(0.33, p.snapshot().fills.first { !it.settled }.limitPrice, 1e-9)
        // Ask drops to 30¢: still fills at 33¢ (no price improvement), remaining 6 capped by depth 100.
        b.onQuote(t, { q(0.30, 100.0) }, now + 2)
        val done = b.orders.value.single()
        assertEquals(10, done.filledQty); assertEquals(PaperOrder.STATUS_FILLED, done.status)
        assertEquals(0.33, p.snapshot().fills.first { !it.settled }.limitPrice, 1e-9)
    }

    @Test
    fun depthAtOrBetterSumsBookLevels() {
        val yesBids = listOf(0.30 to 5.0)
        val noBids = listOf(0.67 to 3.0, 0.66 to 4.0, 0.60 to 50.0) // YES asks 33, 34, 40
        val sq = PaperSideQuote.fromBook("YES", yesBids, noBids)
        assertEquals(0.33, sq.bestAsk!!, 1e-9)
        assertEquals(7.0, sq.askDepthAtOrBelow(0.34), 1e-9)
        assertEquals(3, PaperLimitFill.touchFillQty(0.33, sq.bestAsk, sq.askDepthAtOrBelow(0.33), 10, buy = true))
        // NO side asks are 1 − YES bids.
        val no = PaperSideQuote.fromBook("NO", yesBids, noBids)
        assertEquals(0.70, no.bestAsk!!, 1e-9)
    }

    @Test
    fun sellLimitFillsWhenBidReachesLimit() {
        val p = paper(); val b = orders(p)
        b.submit(t, "YES", "BUY", 0.30, 10, now + 600_000, q(0.30, 100.0))
        assertEquals(10, p.openContracts(t, "YES"))
        val s = b.submit(t, "YES", "SELL", 0.33, 10, now + 600_000, q(0.31, 100.0, bid = 0.32, bidSz = 100.0)).order!!
        assertEquals(0, s.filledQty)
        b.onQuote(t, { q(0.34, 100.0, bid = 0.33, bidSz = 6.0) }, now + 1) // bid touches 33¢ with 6 displayed
        val after = b.orders.value.first { it.id == s.id }
        assertEquals(6, after.filledQty)
        assertEquals(4, p.openContracts(t, "YES"))
        val sold = p.snapshot().fills.first { it.outcome == "sell" }
        assertEquals(6 * (0.33 - 0.30), sold.pnlUsd!!, 1e-9) // maker fee $0 on KXBTC15M
    }

    @Test
    fun sellWithoutPositionIsRejected() {
        val p = paper(); val b = orders(p)
        assertNull(b.submit(t, "NO", "SELL", 0.50, 5, now + 600_000, null).order)
    }

    @Test
    fun unfilledOrdersAutoCancelAtWindowClose() {
        val p = paper(); val b = orders(p)
        val close = now + 60_000
        b.submit(t, "NO", "BUY", 0.20, 10, close, q(0.25, 100.0))
        b.onQuote(t, { q(0.20, 3.0) }, now + 1)
        assertEquals(3, b.orders.value.single().filledQty)
        b.onQuote(t, { q(0.10, 100.0) }, close) // at close: expires, no more fills
        val o = b.orders.value.single()
        assertEquals(PaperOrder.STATUS_EXPIRED, o.status); assertEquals(3, o.filledQty)
        assertTrue(b.expire(close + 1).isEmpty())
    }

    @Test
    fun takerFeeOnImmediateFillMakerFeeOnRestingFill() {
        val p = paper(); val b = orders(p)
        val o = b.submit(t, "YES", "BUY", 0.50, 10, now + 600_000, q(0.50, 4.0)).order!!
        assertEquals(4, o.takerQty)
        assertEquals(KalshiFee.takerFee(4, 0.50), o.feesUsd, 1e-9)
        assertTrue(o.feesUsd > 0.0)
        b.onQuote(t, { q(0.49, 100.0) }, now + 1)
        val done = b.orders.value.single()
        assertEquals(6, done.makerQty)
        assertEquals(o.feesUsd, done.feesUsd, 1e-9) // maker fee $0: KXBTC15M is not on the Maker Fees table
        assertEquals(0.0, KalshiFee.makerFee(6, 0.50, "KXBTC15M"), 0.0)
    }

    @Test
    fun editAndCancel() {
        val p = paper(); val b = orders(p)
        val o = b.submit(t, "YES", "BUY", 0.30, 10, now + 600_000, q(0.40, 100.0)).order!!
        val e = b.edit(o.id, 0.35, 20).order!!
        assertEquals(0.35, e.limitPrice, 1e-9); assertEquals(20, e.quantity)
        // Editing through the market fills immediately (taker).
        val crossed = b.edit(o.id, 0.40, null, q(0.40, 5.0)).order!!
        assertEquals(5, crossed.takerQty)
        assertEquals(PaperOrder.STATUS_CANCELLED, b.cancel(o.id).order!!.status)
        assertFalse(b.orders.value.single().isOpen)
    }

    @Test
    fun touchFillIsLabelledOptimistic() {
        assertTrue(PaperOrder.TOUCH_FILL_NOTE.contains("slightly optimistic vs real queue priority"))
        assertTrue(com.dirk.kalshiodds.ui.components.PaperOrderCopy.FOOTNOTE.contains("auto-cancel at window close"))
    }

    // ---- CF-reprice ----

    private fun quote(ask: Double, bid: Double, cf: Double?, cfAge: Long?, tauS: Double = 400.0, gap: Boolean = false, atMs: Long = now) =
        ScalpRule.Quote(
            ticker = t, nowMs = atMs, closeMs = atMs + (tauS * 1000).toLong(), bookAtMs = atMs,
            yesBid = bid, yesBidSize = 100.0, yesAsk = ask, yesAskSize = 100.0,
            spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5,
            cfSpot = cf, cfAgeMs = cfAge, bookGap = gap
        )

    private val cfr = ScalpParams.seedFor("BTC", ScalpStrategy.CF_REPRICE)

    @Test
    fun cfRepriceNoTradeWhenCfStaleOrMissingOrGap() {
        assertTrue(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_300.0, 2_500L), cfr, listOf("YES")).second.startsWith("NO TRADE"))
        assertTrue(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, null, null), cfr, listOf("YES")).second.startsWith("NO TRADE"))
        assertTrue(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_300.0, 500L, gap = true), cfr, listOf("YES")).second.contains("seq gap"))
    }

    @Test
    fun cfRepriceEntersOnBigGapOnly() {
        // CF well above strike → CF fair YES high; Kalshi ask still 50¢ → enter.
        val (e, why) = ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_300.0, 500L), cfr, listOf("YES", "NO"))
        assertNotNull(why, e); assertEquals("YES", e!!.side)
        assertTrue(e.gapAfterFee >= 0.06)
        // CF at the strike → fair ≈ 50¢ → no 6¢ gap.
        assertNull(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_000.0, 500L), cfr, listOf("YES", "NO")).first)
        // Outside 3–13 min.
        assertNull(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_300.0, 500L, tauS = 120.0), cfr, listOf("YES")).first)
        assertNotNull(ScalpRule.cfRepriceSignal(quote(0.50, 0.49, 100_300.0, 500L, tauS = 800.0), cfr, listOf("YES")).first) // 0.3.53: no warm-up, trades from the open
        // Spread > 2¢.
        assertNull(ScalpRule.cfRepriceSignal(quote(0.50, 0.46, 100_300.0, 500L), cfr, listOf("YES")).first)
    }

    @Test
    fun cfRepriceExits() {
        val q0 = quote(0.60, 0.59, 100_300.0, 500L)
        assertEquals(ScalpRule.ExitReason.PROFIT_TARGET, ScalpRule.cfRepriceExit("YES", 0.50, now, quote(0.59, 0.58, 100_300.0, 500L), cfr))
        assertEquals(ScalpRule.ExitReason.STOP, ScalpRule.cfRepriceExit("YES", 0.50, now, quote(0.45, 0.44, 100_300.0, 500L), cfr))
        assertEquals(ScalpRule.ExitReason.CF_TIME, ScalpRule.cfRepriceExit("YES", 0.50, now - 90_000, quote(0.52, 0.51, 100_300.0, 500L), cfr))
        assertEquals(ScalpRule.ExitReason.CF_EXIT_BY_CLOSE, ScalpRule.cfRepriceExit("YES", 0.50, now, quote(0.52, 0.51, 100_300.0, 500L, tauS = 80.0), cfr))
        // Kalshi repriced to CF fair: gap gone → early exit.
        val fair = q0.cfFair("YES")!!
        assertEquals(ScalpRule.ExitReason.CF_GAP_GONE,
            ScalpRule.cfRepriceExit("YES", 0.50, now, quote(fair + 0.01, fair - 0.0, 100_300.0, 500L), cfr.copy(target = 0.99)))
    }

    @Test
    fun cfRepriceRestingLimitEntryCancelsAfterOneSecond() {
        val book = ScalpBook(variants = listOf(cfr), idFactory = { "s${ids++}" })
        book.onQuote(quote(0.50, 0.49, 100_300.0, 500L), enabled = true)
        val pend = book.allTrades().filter { it.variantId == cfr.id }
        assertEquals(1, pend.size); assertEquals(ScalpState.PENDING_ENTRY, pend.single().state)
        // Next book 1.5 s later still above the limit → limit cancelled, no fill.
        book.onQuote(quote(0.53, 0.52, 100_300.0, 500L, atMs = now + 1_500), enabled = false)
        assertEquals(ScalpState.NO_FILL, book.allTrades().first { it.id == pend.single().id }.state)
    }

    @Test
    fun cfRepriceRestingLimitTouchFillsWithinOneSecond() {
        val book = ScalpBook(variants = listOf(cfr), idFactory = { "s${ids++}" })
        book.onQuote(quote(0.50, 0.49, 100_300.0, 500L), enabled = true)
        val id = book.allTrades().single { it.variantId == cfr.id }.id
        book.onQuote(quote(0.50, 0.49, 100_300.0, 500L, atMs = now + 600), enabled = false)
        val open = book.allTrades().first { it.id == id }
        assertEquals(ScalpState.OPEN, open.state); assertEquals(0.50, open.entryPrice!!, 1e-9)
        assertEquals(ScalpStrategy.CF_REPRICE, open.strategy)
    }

    @Test
    fun cfRepriceHasItsOwnGridAndScorecardLine() {
        assertTrue(ScalpParams.STRATEGY_GRID.count { it.strategy == ScalpStrategy.CF_REPRICE } >= 4)
        assertTrue(ScalpParams.byId(cfr.id) != null)
        assertTrue(ScalpRule.rulesText().contains("CF-reprice"))
    }
}

class ReleaseGate0343MigrationTest {
    @Test
    fun paperOrdersMigrationIsAdditive() {
        val sql = com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.upgradeSql(8).joinToString("\n").uppercase()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS PAPER_ORDERS"))
        assertFalse(sql.contains("DROP")); assertFalse(sql.contains("ALTER")); assertFalse(sql.contains("DELETE"))
        assertTrue(com.dirk.kalshiodds.data.local.paper.PaperOrderSchema.upgradeSql(9).isEmpty())
        assertEquals(9, com.dirk.kalshiodds.AppIdentity.DB_VERSION)
    }
}
