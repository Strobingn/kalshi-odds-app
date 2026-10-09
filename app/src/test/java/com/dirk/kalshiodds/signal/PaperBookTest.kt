package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(125, fill.contracts) // floor(5 / 0.04)
        assertEquals(5.0, fill.stakeUsd, 1e-9)
        val fee = KalshiFee.total(125, 0.04)
        assertEquals(fee, fill.feeUsd, 1e-9)
        assertEquals(100.0 - 5.0 - fee, book.snapshot().cashUsd, 1e-6)
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
        assertEquals(120.0 - win.feeUsd, win.pnlUsd!!, 1e-6)
        val loss = snap.fills.first { it.ticker == "LOSS-1" }
        assertEquals(false, loss.won)
        assertEquals(-5.0 - loss.feeUsd, loss.pnlUsd!!, 1e-6)
        val void = snap.fills.first { it.ticker == "VOID-1" }
        assertEquals(null, void.won)
        assertEquals(0.0, void.pnlUsd!!, 1e-6)
        val expectedPnl = win.pnlUsd!! + loss.pnlUsd!! + void.pnlUsd!!
        assertEquals(expectedPnl, snap.realizedPnlUsd, 1e-6)
        assertEquals(SignalConstants.PAPER_START_USD + expectedPnl, snap.cashUsd, 1e-6)
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
        assertEquals(100.0 - fill.stakeUsd - fill.feeUsd, book.snapshot().cashUsd, 1e-9)
        assertTrue(fill.note.contains("win-target"))
    }

    @Test
    fun unlimitedAutopilotUsesVisibleDepthOncePerOpenContractWindow() {
        val book = PaperBook(idFactory = { "auto-${bookIds++}" }, nowMs = { 12L })
        val ticket = hunterTicket(ticker = "KXBTC15M-AUTO").copy(
            limitPrice = 0.40,
            estimatedAvgFill = 0.40,
            visibleContracts = 2_000
        )

        val first = book.considerUnboundedTicket(ticket, enabled = true)!!
        val duplicate = book.considerUnboundedTicket(ticket, enabled = true)

        assertEquals(2_000, first.contracts)
        assertTrue(first.feeUsd > 0.0)
        assertNull(duplicate)
        assertEquals(1, book.snapshot().fills.count { !it.settled })
        assertTrue(book.snapshot().cashUsd < 0.0)

        book.settle(ticket.ticker, "yes")
        assertEquals(1, book.snapshot().fills.count { it.settled })
        assertTrue(book.snapshot().realizedPnlUsd.isFinite())

        val nextWindowEntry = book.considerUnboundedTicket(ticket, enabled = true)
        assertTrue(nextWindowEntry != null)
        assertEquals(2_000, nextWindowEntry!!.contracts)
    }

    @Test
    fun paperScalpTracksCanHoldTheSameTickerConcurrentlyAndExitIndependently() {
        val book = PaperBook(idFactory = { "multi-${bookIds++}" }, nowMs = { 12L })
        val base = hunterTicket(ticker = "KXBTC15M-MULTI").copy(
            kind = TicketKind.SCALP,
            paperOnly = true,
            limitPrice = 0.40,
            estimatedAvgFill = 0.40,
            visibleContracts = 100
        )
        val dip = book.considerUnboundedTicket(base.copy(strategyVersion = "scalp-v3-dip-hunter"), enabled = true)!!
        val momentum = book.considerUnboundedTicket(base.copy(strategyVersion = "scalp-v3-momentum-sniper"), enabled = true)!!
        val reversal = book.considerUnboundedTicket(base.copy(strategyVersion = "scalp-v3-extreme-reversal"), enabled = true)!!

        assertEquals(3, book.snapshot().openCount)
        assertNull(book.considerUnboundedTicket(base.copy(strategyVersion = dip.strategyVersion), enabled = true))
        assertNotNull(book.updateAutoPositionHighWater(momentum.id, 0.45))
        assertNotNull(book.autoSell(momentum.id, 0.44, "test"))
        assertEquals(2, book.snapshot().openCount)
        assertTrue(!book.snapshot().fills.single { it.id == dip.id }.settled)
        assertTrue(!book.snapshot().fills.single { it.id == reversal.id }.settled)
    }

    private var bookIds: Int = 0

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
