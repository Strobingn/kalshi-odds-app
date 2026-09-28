package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.lastminute.LastMinuteFired
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperKellySizer
import com.dirk.kalshiodds.signal.trade.KalshiFee
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

private const val DEEP = 100_000

class PaperBookTest {

    @Test
    fun startsAtOneThousandAndKellySizesAiFill() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        assertEquals(SignalConstants.PAPER_START_USD, book.snapshot().cashUsd, 1e-9)
        assertEquals(1_000.0, book.snapshot().paperBankrollUsd, 1e-9)
        val fill = book.considerTicket(hunterTicket(), enabled = true, depthContracts = DEEP)
        assertTrue(fill != null)
        assertEquals("YES", fill!!.side)
        assertEquals("AI hunter", fill.source)
        assertEquals(70.0, fill.aiPct!!, 1e-6)
        assertEquals(com.dirk.kalshiodds.signal.paper.PaperPickSource.TICKET.label, fill.pickSource)
        val expected = PaperKellySizer.size(0.70, 0.04, 1_000.0, depthContracts = DEEP)
        assertTrue(expected.ok)
        assertEquals(expected.contracts, fill.contracts)
        assertEquals(expected.allInUsd, fill.stakeUsd, 1e-9)
        assertTrue("no \$10 paper cap: ${fill.stakeUsd}", fill.stakeUsd > 10.0)
        assertTrue(fill.kellyF!! > 0.0)
        assertEquals(0.5, fill.kellyFraction!!, 1e-9)
        assertEquals(1_000.0 - fill.stakeUsd, book.snapshot().cashUsd, 1e-9)
        assertEquals(fill.stakeUsd, book.snapshot().openStakeUsd, 1e-9)
        assertFalse(fill.settled)
    }

    @Test
    fun disabledAndDuplicateTickerDoNotFill() {
        val book = PaperBook()
        assertNull(book.considerTicket(hunterTicket(), enabled = false, depthContracts = DEEP))
        assertTrue(book.considerTicket(hunterTicket(), enabled = true, depthContracts = DEEP) != null)
        assertNull(book.considerTicket(hunterTicket(id = "other"), enabled = true, depthContracts = DEEP))
        assertEquals(1, book.snapshot().fills.size)
    }

    @Test
    fun manualLiveTicketIsNotAutoPapered() {
        val book = PaperBook()
        val manual = hunterTicket().copy(kind = TicketKind.MANUAL, ticker = "KXBTC15M-X")
        assertNull(book.considerTicket(manual, enabled = true, depthContracts = DEEP))
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
        book.considerAlert(alert, ask = 0.20, enabled = true, depthContracts = DEEP)
        assertEquals(1, book.snapshot().fills.size)
        assertEquals("NO", book.snapshot().fills.single().side)
        assertTrue(book.snapshot().fills.single().kellyF!! > 0.0)
        assertTrue(book.snapshot().fills.single().stakeUsd > 10.0)
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        assertTrue(session.snapshot().phase is TicketPhase.Idle)
    }

    @Test
    fun lastMinuteKellyIgnoresFiredContractCount() {
        val book = PaperBook(idFactory = { "lm" }, nowMs = { 2L })
        val fired = LastMinuteFired(
            ticker = "KXBTC15M-LM",
            side = "YES",
            displaySide = "UP",
            winChance = 0.91,
            ask = 0.10,
            evPerDollar = 1.5,
            contracts = 20,
            costUsd = 2.0,
            feeUsd = 0.10,
            profitIfWinUsd = 18.0,
            depthLimited = false,
            depthContracts = 10_000,
            tauSec = 12,
            x = 0.0,
            obsMean = 0.0,
            sigS = 0.0,
            firedAtMs = 2L
        )
        val fill = book.considerLastMinute(fired, enabled = true)
        assertTrue(fill != null)
        val expected = PaperKellySizer.size(0.91, 0.10, 1_000.0, depthContracts = 10_000)
        assertEquals(expected.contracts, fill!!.contracts)
        assertTrue(fill.contracts > 20)
        assertEquals(expected.allInUsd, fill.stakeUsd, 1e-9)
        assertTrue(fill.kellyF!! > 0.0)
    }

    @Test
    fun lastMinuteSkipsWhenAskDepthUnknown() {
        val book = PaperBook()
        val fired = LastMinuteFired(
            ticker = "KXBTC15M-LM0",
            side = "YES",
            displaySide = "UP",
            winChance = 0.91,
            ask = 0.10,
            evPerDollar = 1.5,
            contracts = 20,
            costUsd = 2.0,
            feeUsd = 0.10,
            profitIfWinUsd = 18.0,
            depthLimited = false,
            depthContracts = null,
            tauSec = 12,
            x = 0.0,
            obsMean = 0.0,
            sigS = 0.0,
            firedAtMs = 2L
        )
        assertNull(book.considerLastMinute(fired, enabled = true))
        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(book.snapshot().lastMessage!!.contains("depth", ignoreCase = true))
    }

    @Test
    fun alertSkipsWhenAskDepthUnknown() {
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
        assertNull(book.considerAlert(alert, ask = 0.20, enabled = true))
        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(book.snapshot().lastMessage!!.contains("depth", ignoreCase = true))
    }

    @Test
    fun settleWinLossVoidAndReset() {
        val book = PaperBook()
        val winFill = book.considerTicket(hunterTicket(ticker = "WIN-1"), enabled = true, depthContracts = DEEP)!!
        val lossFill = book.considerTicket(hunterTicket(ticker = "LOSS-1", side = "NO"), enabled = true, depthContracts = DEEP)!!
        val voidFill = book.considerTicket(hunterTicket(ticker = "VOID-1"), enabled = true, depthContracts = DEEP)!!
        book.settle("WIN-1", "yes")
        book.settle("LOSS-1", "yes") // NO side loses
        book.settle("VOID-1", "void")
        val snap = book.snapshot()
        assertEquals(3, snap.fills.count { it.settled })
        assertEquals(0, snap.openCount)
        val win = snap.fills.first { it.ticker == "WIN-1" }
        assertEquals(true, win.won)
        assertEquals(winFill.contracts * 1.0 - winFill.stakeUsd, win.pnlUsd!!, 1e-6)
        val loss = snap.fills.first { it.ticker == "LOSS-1" }
        assertEquals(false, loss.won)
        assertEquals(-lossFill.stakeUsd, loss.pnlUsd!!, 1e-6)
        val voided = snap.fills.first { it.ticker == "VOID-1" }
        assertEquals(0.0, voided.pnlUsd!!, 1e-6)
        assertEquals(win.pnlUsd!! + loss.pnlUsd!! + voided.pnlUsd!!, snap.realizedPnlUsd, 1e-6)
        assertEquals(SignalConstants.PAPER_START_USD + snap.realizedPnlUsd, snap.paperBankrollUsd, 1e-6)
        assertEquals(snap.paperBankrollUsd, snap.cashUsd, 1e-6)
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
        book.considerTicket(ticket, enabled = true, depthContracts = DEEP)
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
    fun winTargetPaperFillUsesKellyNotTicketSize() {
        val book = PaperBook(idFactory = { "pw" }, nowMs = { 11L })
        val ticket = hunterTicket().copy(
            winTargetUsd = 50.0,
            contracts = 20,
            stakeUsd = 8.0,
            limitPrice = 0.40,
            estimatedAvgFill = 0.40,
            ticker = "KXBTC15M-WT"
        )
        val fill = book.considerTicket(ticket, enabled = true, depthContracts = DEEP)
        val expected = PaperKellySizer.size(0.70, 0.40, 1_000.0, depthContracts = DEEP)
        assertEquals(expected.contracts, fill!!.contracts)
        assertEquals(expected.allInUsd, fill.stakeUsd, 1e-9)
        assertTrue(fill.contracts != 20)
        assertTrue(fill.stakeUsd != 8.0)
        assertEquals(1_000.0 - fill.stakeUsd, book.snapshot().cashUsd, 1e-9)
        assertFalse(fill.note.contains("win-target"))
        assertTrue(fill.note.contains("Kelly"))
        assertEquals(KalshiFee.totalCost(fill.contracts, 0.40), fill.stakeUsd, 1e-9)
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
        kind = TicketKind.HUNTER,
        modelChance = 0.70,
        impliedChance = 0.04
    )
}
