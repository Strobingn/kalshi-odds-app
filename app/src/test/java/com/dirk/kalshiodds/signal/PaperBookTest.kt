package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.getStopOrderManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperBookTest {

    @Test
    fun partialPaperExitsKeepUniqueRealizedSlicesAndResidualPeak() {
        val ticker = "KXBTC15M-PARTIAL"
        val book = PaperBook(
            idFactory = { "fixed-id" },
            nowMs = { 1_000L }
        )
        val opened = book.forceFill(ticker, "YES", 0.40, 10, "test", "open")!!
        book.updatePeak(opened.id, 0.50, 2_000L)
        val first = book.sellAtPrice(ticker, "YES", 0.49, 2, "test", "partial")!!
        val afterFirst = book.snapshot()
        assertTrue(first.settled)
        assertEquals(2, first.contracts)
        assertEquals(1, afterFirst.fills.count { it.id == opened.id })
        assertEquals(8, afterFirst.fills.first { it.id == opened.id }.contracts)
        assertEquals(0.50, afterFirst.fills.first { it.id == opened.id }.peakPrice!!, 1e-9)
        assertEquals(afterFirst.fills.size, afterFirst.fills.map { it.id }.toSet().size)

        val second = book.sellAtPrice(ticker, "YES", 0.48, 3, "test", "partial")!!
        val final = book.snapshot()
        assertTrue(second.settled)
        assertEquals(3, second.contracts)
        assertEquals(5, final.fills.first { it.id == opened.id }.contracts)
        assertEquals(2, final.fills.count { it.settled })
        assertEquals(10, final.fills.sumOf { it.contracts })
        assertEquals(
            final.fills.filter { it.settled }.sumOf { it.pnlUsd ?: 0.0 },
            final.realizedPnlUsd,
            1e-9
        )
    }

    @Test
    fun unknownOrRepeatedDepthCannotSellMoreThanOneSnapshot() {
        val ticker = "KXBTC15M-DEPTH"
        val opened = PaperBook(idFactory = { "depth-id" }, nowMs = { 1_000L })
            .also { it.forceFill(ticker, "YES", 0.40, 10, "test", "open") }
        val manager = opened.getStopOrderManager(stopLossDelayMs = Long.MAX_VALUE, maxHoldMs = Long.MAX_VALUE)
        assertTrue(manager.checkAndExecute(mapOf(ticker to com.dirk.kalshiodds.signal.trade.PaperExitQuote(0.50, 2, 2_000L, "book", "same")), 2_000L).isEmpty())
        assertTrue(manager.checkAndExecute(mapOf(ticker to com.dirk.kalshiodds.signal.trade.PaperExitQuote(0.49, 2, 2_000L, "book", "same")), 2_000L).isNotEmpty())
        assertTrue(manager.checkAndExecute(mapOf(ticker to com.dirk.kalshiodds.signal.trade.PaperExitQuote(0.49, 2, 2_000L, "book", "same")), 2_000L).isEmpty())
        assertEquals(8, opened.snapshot().fills.first { !it.settled }.contracts)
    }

    @Test
    fun reopenedPositionCannotReuseUnchangedDepthSnapshot() {
        val ticker = "KXBTC15M-REOPEN"
        val book = PaperBook(idFactory = { java.util.UUID.randomUUID().toString() }, nowMs = { 1_000L })
        book.forceFill(ticker, "YES", 0.40, 2, "test", "open")
        val manager = book.getStopOrderManager(stopLossDelayMs = Long.MAX_VALUE, maxHoldMs = Long.MAX_VALUE)
        val peak = com.dirk.kalshiodds.signal.trade.PaperExitQuote(0.50, 2, 2_000L, "book", "same")
        val hit = com.dirk.kalshiodds.signal.trade.PaperExitQuote(0.49, 2, 2_000L, "book", "same")
        manager.checkAndExecute(mapOf(ticker to peak), 2_000L)
        assertEquals(2, manager.checkAndExecute(mapOf(ticker to hit), 2_000L).single().contracts)
        book.forceFill(ticker, "YES", 0.40, 2, "test", "reopen")
        manager.checkAndExecute(mapOf(ticker to peak), 2_000L)
        assertTrue(manager.checkAndExecute(mapOf(ticker to hit), 2_000L).isEmpty())
        assertEquals(2, book.snapshot().fills.filter { !it.settled && it.ticker == ticker }.sumOf { it.contracts })
    }

    @Test
    fun startsAtOneHundredAndPapersFiveDollarAiFill() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        assertEquals(SignalConstants.PAPER_START_USD, book.snapshot().cashUsd, 1e-9)
        val fill = book.considerTicket(hunterTicket(), enabled = true)
        assertTrue(fill != null)
        assertEquals("YES", fill!!.side)
        assertEquals("AI hunter", fill.source)
        assertEquals(125, fill.contracts) // floor(5 / 0.04)
        assertEquals(5.0, fill.stakeUsd, 1e-9)
        assertEquals(SignalConstants.PAPER_START_USD - fill.stakeUsd - fill.entryFeeUsd, book.snapshot().cashUsd, 1e-9)
        assertEquals(5.0, book.snapshot().openStakeUsd, 1e-9)
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
        assertEquals(true, win.won)
        val winFee = win.entryFeeUsd
        val lossFee = lossEntryFee(snap, "LOSS-1")
        val voidFee = snap.fills.first { it.ticker == "VOID-1" }.entryFeeUsd
        assertEquals(125.0 - 5.0 - winFee, win.pnlUsd!!, 1e-9)
        val loss = snap.fills.first { it.ticker == "LOSS-1" }
        assertEquals(false, loss.won)
        assertEquals(-5.0 - lossFee, loss.pnlUsd!!, 1e-9)
        assertEquals((125.0 - 5.0 - winFee) + (-5.0 - lossFee) - voidFee, snap.realizedPnlUsd, 1e-9)
        // Cash includes all entry fees and the void refund of stake minus its fee.
        assertEquals(SignalConstants.PAPER_START_USD - 15.0 - winFee - lossFee - voidFee + 125.0 + 5.0, snap.cashUsd, 1e-9)
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
        assertEquals(SignalConstants.PAPER_START_USD - fill.stakeUsd - fill.entryFeeUsd, book.snapshot().cashUsd, 1e-9)
        assertTrue(fill.note.contains("win-target"))
    }

    private fun lossEntryFee(state: com.dirk.kalshiodds.signal.paper.PaperBookState, ticker: String): Double =
        state.fills.first { it.ticker == ticker }.entryFeeUsd

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
