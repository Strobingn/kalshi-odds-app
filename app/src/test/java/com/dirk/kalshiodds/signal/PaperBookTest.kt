package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.ScalpExit
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperBookTest {

    @Test
    fun startsAtOneHundredAndPapersFiveDollarAiFill() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        assertEquals(100.0, book.snapshot().cashUsd, 1e-9)
        val fill = book.considerTicket(hunterTicket(), enabled = true)
        assertTrue(fill != null)
        assertEquals("YES", fill!!.side)
        assertEquals("AI hunter", fill.source)
        val qty = ScalpExit.contractsFor(0.04, 100.0)
        assertEquals(qty, fill.contracts)
        assertTrue(fill.contracts > 125)
        assertEquals(qty * 0.04, fill.stakeUsd, 1e-6)
        assertTrue(fill.stakeUsd > 5.0)
        val fee = KalshiFee.total(fill.contracts, 0.04)
        assertEquals(fee, fill.feeUsd, 1e-9)
        assertEquals(100.0 - fill.stakeUsd - fee, book.snapshot().cashUsd, 1e-6)
        assertEquals(fill.stakeUsd, book.snapshot().openStakeUsd, 1e-6)
        assertFalse(fill.settled)
    }

    @Test
    fun disabledAndDuplicateTickerDoNotFill() {
        val book = PaperBook()
        assertNull(book.considerTicket(hunterTicket(), enabled = false))
        assertTrue(book.considerTicket(hunterTicket(), enabled = true) != null)
        assertNull(book.considerTicket(hunterTicket(id = "other"), enabled = true))
        assertEquals(1, book.snapshot().fills.size)
    }

    @Test
    fun manualLiveTicketIsNotAutoPapered() {
        val book = PaperBook()
        val manual = hunterTicket().copy(kind = TicketKind.MANUAL, ticker = "KXBTC15M-X")
        assertNull(book.considerTicket(manual, enabled = true))
        assertTrue(book.manualFill(manual) != null)
        assertTrue(book.snapshot().fills.single().source.contains("paper", ignoreCase = true))
    }

    @Test
    fun alertPapersWithoutCallingPlaceOrder() {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("live order must not run")
            }
        )
        val book = PaperBook()
        val alert = SignalAlert(
            id = "a1",
            ticker = "KXBTC15M-SIG",
            series = "KXBTC15M",
            deltaPp = 8.0,
            fairValuePp = 62.0,
            marketMidPp = 54.0,
            reason = "edge",
            createdAtMs = 1L,
            receiveElapsedNanos = 1L,
            predictedSide = "NO"
        )
        book.considerAlert(alert, ask = 0.20, enabled = true)
        assertEquals(1, book.snapshot().fills.size)
        assertEquals("NO", book.snapshot().fills.single().side)
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        assertTrue(session.snapshot().phase is TicketPhase.Idle)
    }

    @Test
    fun settleWinLossVoidAndReset() {
        val book = PaperBook()
        book.considerTicket(hunterTicket(ticker = "WIN-1"), enabled = true)
        book.considerTicket(hunterTicket(ticker = "LOSS-1", side = "NO"), enabled = true)
        book.considerTicket(hunterTicket(ticker = "VOID-1"), enabled = true)
        book.settle("WIN-1", "yes")
        book.settle("LOSS-1", "yes") // NO side loses
        book.settle("VOID-1", "void")
        val snap = book.snapshot()
        assertEquals(3, snap.fills.count { it.settled })
        assertEquals(0, snap.openCount)
        val win = snap.fills.first { it.ticker == "WIN-1" }
        val loss = snap.fills.first { it.ticker == "LOSS-1" }
        val voided = snap.fills.first { it.ticker == "VOID-1" }
        assertEquals(true, win.won)
        assertEquals(win.contracts * 1.0 - win.stakeUsd - win.feeUsd, win.pnlUsd!!, 1e-6)
        assertEquals(false, loss.won)
        assertEquals(-loss.stakeUsd - loss.feeUsd, loss.pnlUsd!!, 1e-6)
        assertEquals(0.0, voided.pnlUsd!!, 1e-6)
        assertEquals(win.pnlUsd!! + loss.pnlUsd!!, snap.realizedPnlUsd, 1e-6)
        val spent = listOf(win, loss, voided).sumOf { it.stakeUsd + it.feeUsd }
        val cash = 100.0 - spent + win.contracts * 1.0 + voided.stakeUsd + voided.feeUsd
        assertEquals(cash, snap.cashUsd, 1e-6)
        book.reset()
        assertEquals(SignalConstants.PAPER_START_USD, book.snapshot().cashUsd, 1e-9)
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun liveApproveStillGatedAndSeparateFromPaper() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, id ->
                placed.incrementAndGet()
                Result.success(
                    com.dirk.kalshiodds.signal.trade.PlacedOrder(
                        ticket = ticket,
                        clientOrderId = id,
                        orderId = "live-1",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = null,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val book = PaperBook()
        val ticket = hunterTicket()
        session.replaceProposals(listOf(ticket))
        book.considerTicket(ticket, enabled = true)
        assertEquals(0, placed.get())
        session.approve("nope")
        assertEquals(0, placed.get())
        assertTrue(session.openApprove(ticket.id))
        session.approve(ticket.id)
        assertEquals(1, placed.get())
        assertEquals(1, book.snapshot().fills.size)
        assertTrue(book.snapshot().fills.single().source.contains("hunter"))
    }

    @Test
    fun winTargetPaperFillUsesTicketSizeNotFive() {
        val book = PaperBook(idFactory = { "pw" }, nowMs = { 11L })
        val ticket = hunterTicket().copy(
            winTargetUsd = 50.0,
            contracts = 20,
            stakeUsd = 8.0,
            limitPrice = 0.40,
            estimatedAvgFill = 0.40,
            ticker = "KXBTC15M-WT"
        )
        val fill = book.considerTicket(ticket, enabled = true)
        assertEquals(20, fill!!.contracts)
        assertEquals(8.0, fill.stakeUsd, 1e-9)
        assertEquals(100.0 - 8.0 - fill.feeUsd, book.snapshot().cashUsd, 1e-9)
        assertTrue(fill.note.contains("win-target"))
    }

    @Test
    fun aiBuysUnderFiftyAndSkipsACoinFlip() {
        val book = PaperBook()
        assertNull(book.considerTicket(hunterTicket().copy(limitPrice = 0.50, ticker = "KXBTC15M-50"), enabled = true))
        assertNull(book.considerTicket(hunterTicket().copy(limitPrice = 0.62, ticker = "KXBTC15M-62"), enabled = true))
        assertEquals(100.0, book.snapshot().cashUsd, 1e-9)
        val cheap = book.considerTicket(hunterTicket().copy(limitPrice = 0.40, ticker = "KXBTC15M-40"), enabled = true)
        val lower = PaperBook().considerTicket(hunterTicket().copy(limitPrice = 0.10, ticker = "KXBTC15M-10"), enabled = true)
        assertTrue(cheap != null && lower != null)
        assertTrue(lower!!.stakeUsd > cheap!!.stakeUsd)
        assertTrue(lower.contracts > cheap.contracts)
    }

    @Test
    fun aiHoldsTheRiseAndSellsWhenTheBidRollsOver() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        val fill = book.considerTicket(hunterTicket(), enabled = true)!!
        val key = "${fill.ticker.uppercase()}|${fill.side}"
        assertTrue(book.exitIfRisen(mapOf(key to fill.limitPrice)).isEmpty())
        assertFalse(book.snapshot().fills.single().settled)
        assertTrue(book.exitIfRisen(mapOf(key to 0.15)).isEmpty())
        assertEquals(0.15, book.snapshot().fills.single().peakBid, 1e-9)
        assertFalse(book.snapshot().fills.single().settled)
        assertTrue(book.exitIfRisen(mapOf(key to 0.14)).isEmpty())
        assertFalse(book.snapshot().fills.single().settled)

        val bid = 0.12
        val sold = book.exitIfRisen(mapOf(key to bid))
        assertEquals(1, sold.size)
        assertEquals("sell", sold.single().outcome)
        assertEquals(true, sold.single().won)
        val sellFee = KalshiFee.total(fill.contracts, bid)
        val pnl = fill.contracts * bid - sellFee - fill.stakeUsd - fill.feeUsd
        assertTrue(pnl > 0.0)
        assertEquals(pnl, sold.single().pnlUsd!!, 1e-6)
        assertEquals(100.0 + pnl, book.snapshot().cashUsd, 1e-6)
        assertEquals(0, book.snapshot().openCount)
        assertTrue(sold.single().note.contains("came off the high"))
        assertTrue(book.exitIfRisen(mapOf(key to bid)).isEmpty())
    }

    @Test
    fun aiSellsAWinnerWhenTheFirstSevenMinutesFlatten() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        val fill = book.considerTicket(hunterTicket(), enabled = true)!!
        val key = "${fill.ticker.uppercase()}|${fill.side}"
        assertTrue(book.exitIfRisen(mapOf(key to 0.15), mapOf(key to 4L * 60L * 1000L)).isEmpty())
        assertFalse(book.snapshot().fills.single().settled)
        val sold = book.exitIfRisen(mapOf(key to 0.15), mapOf(key to ScalpExit.MOVE_WINDOW_MS))
        assertEquals(1, sold.size)
        assertEquals("sell", sold.single().outcome)
        assertTrue(sold.single().won == true)
        assertTrue(sold.single().note.contains("flattened"))
        assertEquals(0, book.snapshot().openCount)
    }

    @Test
    fun manualPaperFillIsNotSoldJustBecauseTheBidRose() {
        val book = PaperBook(idFactory = { "m" }, nowMs = { 1L })
        val ticket = hunterTicket().copy(kind = TicketKind.MANUAL)
        val fill = book.manualFill(ticket)
        assertTrue(fill != null)
        val key = "${ticket.ticker.uppercase()}|YES"
        assertTrue(book.exitIfRisen(mapOf(key to 0.20)).isEmpty())
        assertEquals(1, book.snapshot().openCount)
    }

    private fun hunterTicket(
        id: String = "t1",
        ticker: String = "KXBTC15M-26SEP241445-45",
        side: String = "YES"
    ) = TradeTicket(
        id = id,
        ticker = ticker,
        side = side,
        bookSide = if (side == "YES") "bid" else "ask",
        stakeUsd = 1.0,
        limitPrice = 0.04,
        yesLimitPrice = if (side == "YES") 0.04 else 0.96,
        contracts = 25,
        estimatedFillUsd = 1.0,
        maxPayoutUsd = 25.0,
        estimatedAvgFill = 0.04,
        sizingNote = "hunter",
        kind = TicketKind.HUNTER
    )
}
