package com.dirk.kalshiodds.signal.ws

import com.dirk.kalshiodds.signal.external.CfIndexBook
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.trade.LiveDailyCap
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kalshi's read-only account channels (fill / user_orders / market_positions) and the 5 Hz index. */
class AccountChannelsTest {

    @Test
    fun parsesAFill() {
        val raw = """{"type":"fill","sid":13,"msg":{"trade_id":"t9","order_id":"o1","market_ticker":"KXBTC15M-26OCT161430-30",
            "is_taker":false,"side":"yes","yes_price_dollars":"0.4700","count_fp":"8.00","action":"buy",
            "client_order_id":"c1","post_position_fp":"8.00","ts":1760625000}}"""
        val f = KalshiWsMessages.parse(raw, 0L) as KalshiWsMessages.Parsed.Fill
        assertEquals("o1", f.orderId)
        assertEquals("KXBTC15M-26OCT161430-30", f.ticker)
        assertEquals("yes", f.outcomeSide)
        assertEquals(8.0, f.count, 1e-9)
        assertEquals(0.47, f.yesPrice!!, 1e-9)
        assertEquals(0.47, f.sidePrice!!, 1e-9)
        assertEquals(false, f.isTaker)
        assertEquals(1760625000000L, f.tsMs)
        assertEquals("Filled 8 YES at 47¢", SignalNotifier.fillTitle(f))
    }

    @Test
    fun aNoFillPricesTheNoSideAndBookSideWorksAlone() {
        val raw = """{"type":"fill","msg":{"order_id":"o2","market_ticker":"KXETH15M-X","book_side":"ask","yes_price_dollars":"0.3000","count_fp":"3"}}"""
        val f = KalshiWsMessages.parse(raw, 0L) as KalshiWsMessages.Parsed.Fill
        assertEquals("no", f.outcomeSide)
        assertEquals(0.70, f.sidePrice!!, 1e-9)
        assertEquals("Filled 3 NO at 70¢", SignalNotifier.fillTitle(f))
    }

    @Test
    fun parsesUserOrderAndPosition() {
        val o = KalshiWsMessages.parse(
            """{"type":"user_order","msg":{"order_id":"o1","ticker":"KXBTC15M-X","status":"resting","fill_count_fp":"2.00","remaining_count_fp":"7.00","client_order_id":"c1"}}""", 0L
        ) as KalshiWsMessages.Parsed.UserOrder
        assertEquals("resting", o.status)
        assertEquals(2.0, o.fillCount!!, 1e-9)
        assertEquals(7.0, o.remainingCount!!, 1e-9)
        val p = KalshiWsMessages.parse(
            """{"type":"market_position","msg":{"market_ticker":"KXBTC15M-X","position_fp":"9.00","realized_pnl_dollars":"1.2500"}}""", 0L
        ) as KalshiWsMessages.Parsed.Position
        assertEquals(9.0, p.position!!, 1e-9)
        assertEquals(1.25, p.realizedPnlUsd!!, 1e-9)
        // Older centi-cent field.
        val old = KalshiWsMessages.parse(
            """{"type":"market_position","msg":{"market_ticker":"KXBTC15M-X","position":5,"realized_pnl":12500}}""", 0L
        ) as KalshiWsMessages.Parsed.Position
        assertEquals(1.25, old.realizedPnlUsd!!, 1e-9)
    }

    @Test
    fun badAccountFramesAreIgnored() {
        assertTrue(KalshiWsMessages.parse("""{"type":"fill","msg":{"order_id":"o"}}""", 0L) is KalshiWsMessages.Parsed.Other)
        assertTrue(KalshiWsMessages.parse("""{"type":"user_order","msg":{"ticker":"X"}}""", 0L) is KalshiWsMessages.Parsed.Other)
    }

    @Test
    fun subscribeCommandsAndErrorIds() {
        val cmd = KalshiWsMessages.subscribePrivate(4)
        assertTrue(cmd, cmd.contains("\"channels\":[\"fill\",\"user_orders\",\"market_positions\"]"))
        assertFalse(cmd, cmd.contains("market_tickers"))
        assertTrue(KalshiWsMessages.isPrivateChannel("market_positions"))
        assertFalse(KalshiWsMessages.isPrivateChannel("ticker"))
        val fast = KalshiWsMessages.subscribeIndices(5, CfIndexBook.INDEX_IDS, KalshiWsMessages.INDEX_CHANNEL_5HZ)
        assertTrue(fast, fast.contains("cfbenchmarks_value_5hz"))
        val err = KalshiWsMessages.parse("""{"id":5,"type":"error","msg":{"code":8,"msg":"Unknown channel"}}""", 0L)
            as KalshiWsMessages.Parsed.Error
        assertEquals(5, err.id)
        val v = KalshiWsMessages.parse(
            """{"type":"cfbenchmarks_value_5hz","msg":{"index_id":"BRTI","data":{"value":"68000.5"}}}""", 0L
        )
        assertTrue(v is KalshiWsMessages.Parsed.IndexValue)
    }

    @Test
    fun fiveHertzFramesKeepTheAveragesFromTheOneHertzFeed() {
        val book = CfIndexBook()
        val slow = KalshiWsMessages.parse(
            """{"type":"cfbenchmarks_value","msg":{"index_id":"BRTI","data":{"value":"68000"},"avg_60s_data":{"value":"67990"},"last_60s_windowed_average_15min":{"value":"67980"}}}""", 0L
        ) as KalshiWsMessages.Parsed.IndexValue
        val fast = KalshiWsMessages.parse(
            """{"type":"cfbenchmarks_value_5hz","msg":{"index_id":"BRTI","data":{"value":"68010"}}}""", 0L
        ) as KalshiWsMessages.Parsed.IndexValue
        val close = 900_000L
        book.onUpdate(slow, close - 30_000L)
        book.onUpdate(fast, close - 29_800L)
        val cur = book.fresh("BTC", close - 29_800L)!!
        assertEquals(68010.0, cur.value, 1e-9)
        assertEquals(67990.0, cur.avg60!!, 1e-9)
        assertEquals(67980.0, book.finalMinuteAverage("BTC", close, close - 29_800L)!!, 1e-9)
    }

    // ---- working orders -----------------------------------------------------

    private fun ticket() = TradeTicket(
        id = "t1", ticker = "KXBTC15M-X", side = "YES", bookSide = "bid", stakeUsd = 5.0,
        limitPrice = 0.50, yesLimitPrice = 0.50, contracts = 10, estimatedFillUsd = 5.0, maxPayoutUsd = 10.0,
        estimatedAvgFill = 0.50, sizingNote = "rest", feeUsd = 0.0, allInUsd = 5.0,
        postOnly = true
    )

    private fun placedSession(cancel: suspend (PlacedOrder) -> Result<PlacedOrder> = { Result.success(it) }) =
        TicketSession(
            placeOrder = { t, cid ->
                Result.success(PlacedOrder(t, cid, "o1", fillCount = 0.0, remainingCount = 10.0, averageFillPrice = null, placedAtMs = 1L))
            },
            cancelOrder = cancel
        ).also { s ->
            runBlocking {
                s.replaceProposals(listOf(ticket()))
                s.openApprove("t1")
                assertTrue(s.approve("t1").phase is TicketPhase.Submitted)
            }
        }

    @Test
    fun partialThenFullFillStopsResting() {
        val s = placedSession()
        val part = s.applyOrderUpdate("o1", null, "resting", fillCount = 4.0, remainingCount = 6.0)!!
        assertEquals(4, part.filledContracts)
        assertTrue(part.isResting)
        val done = s.applyOrderUpdate("o1", null, "executed", fillCount = 10.0, remainingCount = 0.0)!!
        assertFalse(done.isResting)
        assertNull(done.error)
        assertFalse(s.snapshot().working.single().isResting)
        // Same update again changes nothing.
        assertNull(s.applyOrderUpdate("o1", null, "executed", fillCount = 10.0, remainingCount = 0.0))
        // Unknown orders are ignored.
        assertNull(s.applyOrderUpdate("other", null, "executed", 1.0, 0.0))
    }

    @Test
    fun aKalshiCancelRecordsTheUnfilledCountForTheCap() {
        val s = placedSession()
        s.applyOrderUpdate("o1", null, "resting", fillCount = 4.0, remainingCount = 6.0)
        val gone = s.applyOrderUpdate("o1", null, "canceled", fillCount = 4.0, remainingCount = 0.0)!!
        assertFalse(gone.isResting)
        assertTrue(gone.error!!.startsWith(TicketSession.ORDER_CANCELLED_ON_KALSHI))
        // 6 of 10 contracts gave back: 60% of the $5 all-in.
        assertEquals(3.0, LiveDailyCap.cancelledCostOf(gone), 1e-9)
        // Once marked cancelled, later frames leave it alone.
        assertNull(s.applyOrderUpdate("o1", null, "canceled", 4.0, 0.0))
    }

    @Test
    fun theAppsOwnCancelIsLeftToItsRestAnswer() = runBlocking {
        var session: TicketSession? = null
        var duringCancel: PlacedOrder? = PlacedOrder(ticket(), "x", "x", 0.0, 0.0, null, 0L)
        val s = placedSession(cancel = { order ->
            // Kalshi's WebSocket says "canceled" while the REST cancel is in flight.
            duringCancel = session!!.applyOrderUpdate("o1", null, "canceled", 0.0, 0.0)
            Result.success(order.copy(remainingCount = 0.0, error = "cancelled (−10)"))
        })
        session = s
        s.cancelWorking("o1")
        assertNull(duringCancel)
        assertNotNull(s.snapshot().working.single().error)
        assertEquals("cancelled (−10)", s.snapshot().working.single().error)
    }
}
