package com.dirk.kalshiodds.signal.limit

import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.api.KalshiTradeApi
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.LimitCopy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limit orders at the user's price: the rules, the paper fills from real trades, and the real request. */
class LimitOrderTest {

    private val t = "KXBTC15M-26OCT091315-15"
    private val now = 1_000_000L
    private val close = now + 600_000L
    private val quote = LimitOrder.Quote(bid = 0.50, ask = 0.53, bidQty = 4_000.0, askQty = 900.0)

    private fun buy(side: String = "YES", paperOnly: Boolean = false) = TradeTicket(
        id = "t1", ticker = t, side = side, bookSide = if (side == "YES") "bid" else "ask", stakeUsd = 4.77,
        limitPrice = 0.53, yesLimitPrice = if (side == "YES") 0.53 else 0.47, contracts = 9, estimatedFillUsd = 4.77,
        maxPayoutUsd = 9.0, estimatedAvgFill = 0.53, sizingNote = "taker", kind = TicketKind.MANUAL, feeUsd = 0.16,
        allInUsd = 4.93, paperOnly = paperOnly
    )

    private fun sell(side: String = "YES", held: Int = 9, paperOnly: Boolean = false) = TradeTicket(
        id = "s1", ticker = t, side = side, bookSide = if (side == "YES") "ask" else "bid", stakeUsd = 4.3,
        limitPrice = 0.50, yesLimitPrice = if (side == "YES") 0.50 else 0.50, contracts = held, estimatedFillUsd = 4.3,
        maxPayoutUsd = 4.3, estimatedAvgFill = 0.50, sizingNote = "sell", kind = TicketKind.SELL, reduceOnly = true,
        heldContracts = held, paperOnly = paperOnly
    )

    private fun built(ticket: TradeTicket, price: Double, n: Int, paper: Boolean = false, cancel: Long = 120_000L, q: LimitOrder.Quote = quote) =
        LimitOrder.build(ticket, price, n, q, cancel, close, now, paper)

    // ---- prices ----

    @Test
    fun priceStepsAreACentInTheMiddleAndATenthOutside() {
        assertEquals(0.51, LimitOrder.step(0.50, up = true), 1e-9)
        assertEquals(0.49, LimitOrder.step(0.50, up = false), 1e-9)
        assertEquals(0.90, LimitOrder.step(0.89, up = true), 1e-9)
        assertEquals(0.901, LimitOrder.step(0.90, up = true), 1e-9)
        assertEquals(0.89, LimitOrder.step(0.90, up = false), 1e-9)
        assertEquals(0.90, LimitOrder.step(0.901, up = false), 1e-9)
        assertEquals(0.11, LimitOrder.step(0.10, up = true), 1e-9)
        assertEquals(0.099, LimitOrder.step(0.10, up = false), 1e-9)
        assertEquals(0.10, LimitOrder.step(0.099, up = true), 1e-9)
        assertEquals(0.001, LimitOrder.step(0.001, up = false), 1e-9)
        assertEquals(0.999, LimitOrder.step(0.999, up = true), 1e-9)
        assertEquals(0.53, LimitOrder.round(0.534), 1e-9)
        assertEquals(0.934, LimitOrder.round(0.9341), 1e-9)
        assertEquals("54¢", LimitOrder.cents(0.54))
        assertEquals("90.5¢", LimitOrder.cents(0.905))
    }

    @Test
    fun editorStartsOneStepBetterWhenThereIsRoom() {
        assertEquals("buy: one cent over the bid", 0.51, LimitOrder.defaultPrice(false, quote)!!, 1e-9)
        assertEquals("buy, 1¢ spread: join the bid", 0.50, LimitOrder.defaultPrice(false, quote.copy(ask = 0.51))!!, 1e-9)
        assertEquals("sell: one cent under the ask", 0.52, LimitOrder.defaultPrice(true, quote)!!, 1e-9)
        assertEquals("sell, 1¢ spread: join the ask", 0.51, LimitOrder.defaultPrice(true, quote.copy(ask = 0.51))!!, 1e-9)
        assertEquals("sell with nobody bidding: under the ask", 0.52, LimitOrder.defaultPrice(true, quote.copy(bid = null))!!, 1e-9)
        assertNull(LimitOrder.defaultPrice(false, LimitOrder.Quote(null, null)))
    }

    // ---- building ----

    @Test
    fun aLimitBuyRestsWithNoFeeAndAnEndTime() {
        val l = built(buy(), 0.51, 9).getOrThrow()
        assertTrue(l.postOnly)
        assertEquals(0.51, l.limitPrice, 1e-9)
        assertEquals(0.51, l.yesLimitPrice, 1e-9)
        assertEquals(9, l.contracts)
        assertEquals(4.59, l.stakeUsd, 1e-9)
        assertEquals(0.0, l.feeUsd!!, 1e-9)
        assertEquals(4.59, l.allInUsd!!, 1e-9)
        assertEquals(9.0 - 4.59, l.profitIfWinUsd!!, 1e-9)
        assertEquals(now + 120_000L, l.expiresAtMs)
        assertEquals("the app cancels 2 s before Kalshi's own expiry", 118_000L, l.restingCancelAfterMs)
        assertEquals("9 contracts bid at 51¢ · $4.59 if filled · no fee", l.sizingNote)
        assertEquals(LimitOrder.BUY_NOTE, l.gateNote)
        assertTrue(l.canApprove)
        // DOWN at 46¢ is an offer of UP at 54¢ on Kalshi's single book.
        val down = built(buy("NO"), 0.46, 5, q = LimitOrder.Quote(0.45, 0.48)).getOrThrow()
        assertEquals(0.54, down.yesLimitPrice, 1e-9)
        assertEquals("ask", down.bookSide)
    }

    @Test
    fun aBuyThatWouldCrossIsRefused() {
        assertEquals(LimitOrder.WOULD_BUY_NOW, built(buy(), 0.53, 5).exceptionOrNull()!!.message)
        assertEquals(LimitOrder.WOULD_BUY_NOW, built(buy(), 0.60, 5).exceptionOrNull()!!.message)
        assertTrue("one step under the ask is fine", built(buy(), 0.52, 5).isSuccess)
        assertTrue(built(buy(), 0.515, 5).exceptionOrNull()!!.message!!.contains("between steps"))
        assertEquals("At least 1 contract", built(buy(), 0.51, 0).exceptionOrNull()!!.message)
        assertTrue("a deep bid is allowed", built(buy(), 0.30, 15).isSuccess)
    }

    @Test
    fun realBuysKeepTheFiveDollarCapAndPaperDoesNot() {
        assertEquals(9, LimitOrder.maxLiveContracts(0.51))
        assertEquals("Over the \$5 cap: at 51¢ the most is 9 contracts", built(buy(), 0.51, 10).exceptionOrNull()!!.message)
        assertTrue(built(buy(), 0.51, 9).isSuccess)
        assertEquals(40, built(buy(), 0.51, 40, paper = true).getOrThrow().contracts)
        // The cap leaves room for the taker fee the order path would add, so the last-chance clip never resizes it.
        for (p in listOf(0.03, 0.12, 0.37, 0.51, 0.88, 0.95)) {
            val n = LimitOrder.maxLiveContracts(p)
            val l = LimitOrder.build(buy(), p, n, LimitOrder.Quote(null, null), 120_000L, close, now, paper = false).getOrThrow()
            assertEquals("at $p", n, com.dirk.kalshiodds.signal.trade.LiveOrderSizer.enforce(l).count)
        }
    }

    @Test
    fun everyOrderEndsBeforeTheWindowDoes() {
        assertEquals(now + 30_000L, LimitOrder.expiresAt(now, 30_000L, close))
        assertEquals("never into the last 30 s", close - 30_000L, LimitOrder.expiresAt(now, 3_600_000L, close))
        assertEquals(close - 30_000L, LimitOrder.expiresAt(now, LimitOrder.UNTIL_CLOSE, close))
        assertNull("nothing to rest 32 s before the close", LimitOrder.expiresAt(close - 32_000L, 120_000L, close))
        assertNull(LimitOrder.expiresAt(now, LimitOrder.UNTIL_CLOSE, null))
        assertEquals(LimitOrder.TOO_LATE, LimitOrder.build(buy(), 0.51, 5, quote, 120_000L, close, close - 20_000L, false).exceptionOrNull()!!.message)
        assertEquals("2 min", LimitOrder.cancelLabel(120_000L))
        assertEquals("30 s", LimitOrder.cancelLabel(30_000L))
        assertEquals("until 30 s before the close", LimitOrder.cancelLabel(LimitOrder.UNTIL_CLOSE))
    }

    @Test
    fun aLimitSellRestsAboveTheBidForNoMoreThanIsHeld() {
        val l = built(sell(), 0.55, 9).getOrThrow()
        assertTrue(l.postOnly)
        assertFalse("a resting sell cannot be reduce-only on Kalshi", l.reduceOnly)
        assertTrue(l.isSell)
        assertEquals(0.55, l.yesLimitPrice, 1e-9)
        assertEquals("ask", l.bookSide)
        assertEquals(4.95, l.stakeUsd, 1e-9)
        assertEquals("9 contracts offered at 55¢ · $4.95 if filled · no fee", l.sizingNote)
        assertEquals("You hold 9: cannot sell 10", built(sell(), 0.55, 10).exceptionOrNull()!!.message)
        assertEquals(LimitOrder.WOULD_SELL_NOW, built(sell(), 0.50, 9).exceptionOrNull()!!.message)
        // DOWN sold at 55¢ is a bid for UP at 45¢.
        val down = built(sell("NO"), 0.55, 4).getOrThrow()
        assertEquals(0.45, down.yesLimitPrice, 1e-9)
        assertEquals("bid", down.bookSide)
        // "No buyers" blocks selling at the bid, not resting an offer.
        val stuck = sell().copy(contracts = 0, blockedReason = TicketBuilder.NO_BUYERS)
        val rested = built(stuck, 0.40, 9, q = LimitOrder.Quote(null, 0.45)).getOrThrow()
        assertNull(rested.blockedReason)
        assertEquals(9, rested.contracts)
        assertEquals("Market closed", built(buy().copy(blockedReason = "Market closed"), 0.51, 5).exceptionOrNull()!!.message)
        // The min-profit setting switches off the buy-at-the-ask ticket; a limit at the user's own price still rests.
        val small = buy().copy(blockedReason = "Profit if win \$4.07 is below the \$10 minimum", belowMinProfit = true, minProfitIfWinUsd = 10.0)
        assertTrue(LimitOrder.canRest(small))
        val ok = built(small, 0.51, 9).getOrThrow()
        assertNull(ok.blockedReason)
        assertFalse(ok.belowMinProfit)
        assertTrue(ok.canApprove)
        assertFalse(LimitOrder.canRest(buy().copy(blockedReason = "Market closed")))
        assertFalse("a sell is not excused by the buy-side setting", LimitOrder.canRest(sell().copy(blockedReason = "x", belowMinProfit = true)))
    }

    // ---- the real request ----

    @Test
    fun realLimitOrdersArePostOnlyGtcWithKalshiSideExpiry() {
        val client = KalshiTradeClient(api = Unused, credentials = { "k" to "p" })
        val json = Json { encodeDefaults = true }
        val b = client.v2Body(built(buy(), 0.51, 9).getOrThrow(), "c1")
        assertEquals("bid", b.side)
        assertEquals("0.5100", b.price)
        assertEquals("9.00", b.count)
        assertTrue(b.postOnly)
        assertFalse(b.reduceOnly)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_GTC, b.timeInForce)
        assertEquals("whole seconds, rounded up", (now + 120_000L + 999L) / 1000L, b.expirationTime)
        assertTrue(json.encodeToString(CreateOrderV2Request.serializer(), b).contains("\"expiration_time\":1120"))

        val s = client.v2Body(built(sell(), 0.55, 9).getOrThrow(), "c2")
        assertEquals("ask", s.side)
        assertEquals("0.5500", s.price)
        assertTrue(s.postOnly)
        assertFalse("post-only sells rest; reduce-only is allowed only with immediate-or-cancel", s.reduceOnly)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_GTC, s.timeInForce)
        assertEquals((now + 120_000L + 999L) / 1000L, s.expirationTime)

        // Unchanged paths: a sell at the bid is still reduce-only IOC, an ordinary buy has no expiry field at all.
        val ioc = client.v2Body(sell(), "c3")
        assertTrue(ioc.reduceOnly)
        assertFalse(ioc.postOnly)
        assertEquals(CreateOrderV2Request.TIME_IN_FORCE_IOC, ioc.timeInForce)
        assertNull(ioc.expirationTime)
        val plain = client.v2Body(buy(), "c4")
        assertNull(plain.expirationTime)
        assertFalse(json.encodeToString(CreateOrderV2Request.serializer(), plain).contains("expiration_time"))
    }

    @Test
    fun aRestingSellCountsAsWorkingAndASellAtTheBidDoesNot() {
        fun placed(ticket: TradeTicket) = PlacedOrder(ticket, "c", "o1", fillCount = 0.0, remainingCount = 9.0, averageFillPrice = null, placedAtMs = now)
        assertTrue(placed(built(sell(), 0.55, 9).getOrThrow()).isResting)
        assertFalse(placed(sell()).isResting)
        assertTrue(placed(built(buy(), 0.51, 9).getOrThrow()).isResting)
    }

    @Test
    fun aCancelThatFindsTheOrderGoneClearsItFromTheRestingList() = kotlinx.coroutines.runBlocking {
        val limit = built(buy(), 0.51, 9).getOrThrow()
        val session = com.dirk.kalshiodds.signal.trade.TicketSession(
            placeOrder = { tk, cid -> Result.success(PlacedOrder(tk, cid, "ord-1", 0.0, 9.0, null, now)) },
            cancelOrder = { Result.failure(IllegalStateException("HTTP 404 — not_found")) }
        )
        session.addManual(limit)
        session.approve(limit.id)
        assertTrue(session.snapshot().working.single().isResting)
        val after = session.cancelWorking("ord-1")
        assertEquals(com.dirk.kalshiodds.signal.trade.TicketSession.ORDER_GONE, after.working.single().error)
        assertTrue(after.lastError!!.contains("filled or expired"))
        assertEquals("nothing is given back to the daily cap for an order that may have filled",
            0.0, com.dirk.kalshiodds.signal.trade.LiveDailyCap.cancelledCostOf(after.working.single()), 1e-9)
    }

    // ---- paper ----

    private class Book(var levels: BookLevelSnapshot? = null)

    private fun paperBook() = PaperBook(idFactory = { "f" + System.nanoTime() }, nowMs = { now })

    private fun limits(paper: PaperBook, book: Book) = PaperLimitBook(paper, levels = { book.levels }, idFactory = { "o1" })

    @Test
    fun aPaperBuyFillsOnlyAfterTheQueueAheadHasTraded() {
        val paper = paperBook()
        val book = Book(BookLevelSnapshot(yes = listOf(0.51 to 100.0, 0.50 to 4_000.0), no = listOf(0.47 to 900.0)))
        val l = limits(paper, book)
        val cash = paper.snapshot().cashUsd
        val order = l.place(built(buy(paperOnly = true), 0.51, 9, paper = true).getOrThrow(), now).getOrThrow()
        assertEquals(100.0, order.queueAhead, 1e-9)
        assertTrue(order.queueKnown)
        assertTrue(l.lastMessage!!, l.lastMessage!!.startsWith("PAPER limit buy UP · 9 ct @ 51¢ resting · 100 ahead"))
        assertEquals("nothing is spent until it fills", cash, paper.snapshot().cashUsd, 1e-9)
        // Takers buying UP do not hit a bid; sellers at a higher price do not reach it.
        assertTrue(l.onTrade(t, "yes", 0.52, 500.0, now + 1).isEmpty())
        assertTrue(l.onTrade(t, "no", 0.52, 500.0, now + 2).isEmpty())
        // 60 + 48 = 108 sold at 51¢: one short of the 100 ahead plus our 9.
        assertTrue(l.onTrade(t, "no", 0.51, 60.0, now + 3).isEmpty())
        assertTrue(l.onTrade(t, "no", 0.51, 48.0, now + 4).isEmpty())
        assertEquals(1.0, l.snapshot().single().needed, 1e-9)
        assertEquals(1, l.onTrade(t, "no", 0.51, 1.0, now + 5).size)
        assertTrue(l.snapshot().isEmpty())
        val fill = paper.snapshot().fills.single()
        assertEquals("YES", fill.side)
        assertEquals(9, fill.contracts)
        assertEquals(0.51, fill.limitPrice, 1e-9)
        assertEquals("a resting fill pays no fee", 0.0, fill.feeUsd, 1e-9)
        assertEquals(cash - 4.59, paper.snapshot().cashUsd, 1e-9)
        assertTrue(l.lastMessage!!.contains("FILLED"))
    }

    @Test
    fun aPrintThroughThePriceFillsAtOnceAndTheBookCanOnlyShortenTheQueue() {
        val paper = paperBook()
        val book = Book(BookLevelSnapshot(yes = listOf(0.50 to 4_000.0), no = listOf(0.47 to 900.0)))
        val l = limits(paper, book)
        l.place(built(buy(paperOnly = true), 0.50, 9, paper = true).getOrThrow(), now).getOrThrow()
        assertEquals(4_009.0, l.snapshot().single().needed, 1e-9)
        // The size shown at our price falls to 30: at most 30 are ahead now. It growing again changes nothing.
        book.levels = BookLevelSnapshot(yes = listOf(0.50 to 30.0), no = listOf(0.47 to 900.0))
        l.onClock(t, now + 1_000L)
        assertEquals(39.0, l.snapshot().single().needed, 1e-9)
        book.levels = BookLevelSnapshot(yes = listOf(0.50 to 6_000.0), no = listOf(0.47 to 900.0))
        l.onClock(t, now + 2_000L)
        assertEquals(39.0, l.snapshot().single().needed, 1e-9)
        assertEquals(30.0, l.snapshot().single().stillAhead, 1e-9)
        // A sale at 49¢ went through 50¢: filled, at our 50¢.
        assertEquals(1, l.onTrade(t, "no", 0.49, 5.0, now + 3_000L).size)
        assertEquals(0.50, paper.snapshot().fills.single().limitPrice, 1e-9)
    }

    @Test
    fun paperOrdersExpireCancelAndNeverFillAfterTheirTime() {
        val paper = paperBook()
        val l = limits(paper, Book())
        val order = l.place(built(buy(paperOnly = true), 0.51, 9, paper = true, cancel = 30_000L).getOrThrow(), now).getOrThrow()
        assertFalse("no book: the queue is assumed", order.queueKnown)
        assertEquals(3_500.0, order.queueAhead, 1e-9)
        assertTrue(l.lastMessage!!.contains("about 3,500 (book not shown)"))
        assertEquals(setOf(t), l.openTickers())
        assertTrue("a late print cannot fill an expired order", l.onTrade(t, "no", 0.40, 50_000.0, now + 30_001L).isEmpty())
        l.onClock(t, now + 30_001L)
        assertTrue(l.snapshot().isEmpty())
        assertEquals("PAPER limit buy UP 9 ct @ 51¢ expired unfilled", l.lastMessage)
        assertTrue(paper.snapshot().fills.isEmpty())
        // Cancel, and a second order on the same window while one rests.
        val again = l.place(built(buy(paperOnly = true), 0.51, 9, paper = true).getOrThrow(), now).getOrThrow()
        assertTrue(l.place(built(buy(paperOnly = true), 0.50, 9, paper = true).getOrThrow(), now).isFailure)
        assertTrue(l.cancel(again.id))
        assertFalse(l.cancel(again.id))
        l.place(built(buy(paperOnly = true), 0.51, 9, paper = true).getOrThrow(), now)
        l.closeTicker(t)
        assertTrue(l.snapshot().isEmpty())
        // More than the paper cash covers.
        assertTrue(l.place(built(buy(paperOnly = true), 0.51, 400, paper = true).getOrThrow(), now).exceptionOrNull()!!.message!!.startsWith("Paper cash"))
    }

    @Test
    fun aPaperLimitSellWaitsForBuyersAtItsPrice() {
        val paper = paperBook()
        val book = Book(BookLevelSnapshot(yes = listOf(0.50 to 4_000.0), no = listOf(0.45 to 200.0, 0.44 to 900.0)))
        val l = limits(paper, book)
        assertTrue("nothing to sell yet", l.place(built(sell(paperOnly = true), 0.55, 9, paper = true).getOrThrow(), now).isFailure)
        paper.explicitFill(t, "YES", 0.50, 9, "test", "test", maker = true)
        val cash = paper.snapshot().cashUsd
        // An offer of UP at 55¢ waits with the DOWN bids at 45¢: 200 are there already.
        val order = l.place(built(sell(paperOnly = true), 0.55, 9, paper = true).getOrThrow(), now).getOrThrow()
        assertEquals(200.0, order.queueAhead, 1e-9)
        assertEquals("NO" to 0.45, PaperLimitBook.asBid(order.ticket))
        assertTrue("sellers of UP do not lift an offer", l.onTrade(t, "no", 0.55, 5_000.0, now + 1).isEmpty())
        assertTrue(l.onTrade(t, "yes", 0.55, 150.0, now + 2).isEmpty())
        assertEquals("buyers paid 56¢: through our 55¢", 1, l.onTrade(t, "yes", 0.56, 1.0, now + 3).size)
        val sold = paper.snapshot().fills.first { it.outcome == "sell" }
        assertEquals(9 * 0.55 - 9 * 0.50, sold.pnlUsd!!, 1e-9)
        assertEquals(cash + 9 * 0.55, paper.snapshot().cashUsd, 1e-9)
    }

    // ---- copy ----

    @Test
    fun editorCopy() {
        assertEquals("Best bid 50¢ (4,000) · ask 53¢ (900)", LimitCopy.quoteLine(quote))
        assertEquals("Best bid – · ask 53¢", LimitCopy.quoteLine(LimitOrder.Quote(null, 0.53)))
        assertEquals("4,000 contracts are ahead of yours at this price", LimitCopy.queueLine(false, 4_000.0))
        assertEquals("Nothing bid at this price yet: yours would be first", LimitCopy.queueLine(false, 0.0))
        assertTrue(LimitCopy.queueLine(true, null).startsWith("Queue at this price is not shown"))
        assertEquals("If filled: costs $4.59 · pays $9.00 if UP wins (+$4.41) · no fee", LimitCopy.summary(false, "YES", 0.51, 9))
        assertEquals("If filled: you receive $4.95 · no fee", LimitCopy.summary(true, "YES", 0.55, 9))
        assertEquals("REAL MONEY · bid 9 @ 51¢", LimitCopy.confirmLabel(false, false, 0.51, 9))
        assertEquals("PAPER · offer 9 @ 55¢", LimitCopy.confirmLabel(true, true, 0.55, 9))
        assertEquals("real buys start inside the cap", 9, LimitCopy.startContracts(buy(), 0.51, paper = false))
        assertEquals("sells start with everything held", 9, LimitCopy.startContracts(sell(), 0.55, paper = false))
    }

    private object Unused : KalshiTradeApi {
        override suspend fun getBalance() = error("not used")
        override suspend fun getPositions(countFilter: String, limit: Int, cursor: String?) = error("not used")
        override suspend fun createOrderV2(body: CreateOrderV2Request) = error("not used")
        override suspend fun cancelOrderV2(orderId: String, marketTicker: String?, exchangeIndex: Int) = error("not used")
    }
}
