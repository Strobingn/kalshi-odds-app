package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBook
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
    fun startsAtTwentyThousandAndPapersUncappedAggressiveAiFill() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        assertEquals(20_000.0, book.snapshot().cashUsd, 1e-9)
        val fill = book.considerTicket(hunterTicket(), enabled = true)
        assertTrue(fill != null)
        assertEquals("YES", fill!!.side)
        assertEquals("AI hunter", fill.source)
        // 10% of $20k equity = $2,000 at 4¢ → 50,000 ct (no explicit size, no cap)
        assertEquals(50_000, fill.contracts)
        assertEquals(2_000.0, fill.stakeUsd, 1e-9)
        assertEquals(18_000.0, book.snapshot().cashUsd, 1e-9)
        assertEquals(2_000.0, book.snapshot().openStakeUsd, 1e-9)
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
        assertEquals(48_000.0, win.pnlUsd!!, 1e-9) // 50,000 * 1 - 2,000
        val loss = snap.fills.first { it.ticker == "LOSS-1" }
        assertEquals(false, loss.won)
        assertEquals(-2_000.0, loss.pnlUsd!!, 1e-9)
        assertEquals(46_000.0, snap.realizedPnlUsd, 1e-9)
        // cash: 20,000 - 6,000 + 50,000 (win) + 0 (loss) + 2,000 (void refund) = 66,000
        assertEquals(66_000.0, snap.cashUsd, 1e-9)
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
        assertEquals(92.0, book.snapshot().cashUsd, 1e-9)
        assertTrue(fill.note.contains("win-target"))
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
