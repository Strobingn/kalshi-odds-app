package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-style: ticket Approve in paper mode with **no Kalshi
 * credentials** books a fill, debits paper equity, opens a paper position,
 * and shows up in History. This is the 0.3.6 / 0.3.7 regression.
 */
class PaperApprovePathTest {

    @Test
    fun ticketApproveInPaperModeNoCredentialsBooksFillEquityPositionAndHistory() = runBlocking {
        val ticket = ticket(TicketKind.MANUAL, "KXBTC15M-E2E", 10, 2.0, 0.20)
        val decision = com.dirk.kalshiodds.signal.trade.ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = false,
            canApprove = ticket.canApprove
        )
        assertEquals(com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Paper, decision)
        val (out, book, history) = runApprove(ticket)
        assertTrue(out.message, out.ok)
        val snap = book.snapshot()
        assertEquals(1, snap.fills.size)
        assertFalse(snap.fills.single().settled)
        assertEquals(1, snap.openCount)
        assertTrue(snap.cashUsd < SignalConstantsStart)
        assertTrue(SignalConstantsStart - snap.cashUsd + 1e-9 >= out.stakeUsd)
        assertEquals(out.contracts, snap.fills.single().contracts)
        assertEquals(1, history.size)
        assertTrue(history.single().approved)
        assertEquals("paper filled", history.single().result)
        val bets = HistoryAssembler.bets(history, snap, HistoryAssembler.SourceFilter.PAPER)
        assertEquals(1, bets.size)
        assertEquals("open", bets.single().result)
        assertEquals(ticket.ticker, bets.single().ticker)
        assertFalse(out.message.contains("Kalshi key", ignoreCase = true))
        assertFalse(out.message.contains("API Key", ignoreCase = true))
    }

    @Test
    fun approveManualNoCredentialsBooksFillEquityPositionHistory() = runBlocking {
        assertPath(ticket(TicketKind.MANUAL, "KXBTC15M-M", 10, 2.0, 0.20))
    }

    @Test
    fun approveHunterNoCredentials() = runBlocking {
        assertPath(ticket(TicketKind.HUNTER, "KXBTC15M-H", 25, 1.0, 0.04))
    }

    @Test
    fun approveLongShotNoCredentials() = runBlocking {
        assertPath(ticket(TicketKind.HUNTER_VALUE, "KXBTC15M-L", 40, 8.0, 0.20, win = 50.0))
    }

    @Test
    fun approveWinTargetConfiguredNoCredentials() = runBlocking {
        assertPath(ticket(TicketKind.CONFIGURED, "KXBTC15M-C", 20, 4.0, 0.20, win = 50.0))
    }

    @Test
    fun winTargetAbovePaperCashIsUncappedAndStillBooks() = runBlocking {
        val ticket = ticket(
            TicketKind.HUNTER_VALUE,
            "KXBTC15M-CAP",
            contracts = 60_000,
            stake = 24_000.0,
            px = 0.40,
            win = 50.0
        )
        val (out, book, history) = runApprove(ticket)
        assertTrue(out.message, out.ok)
        assertFalse(out.capped)
        assertEquals(60_000, out.contracts)
        assertEquals(24_000.0, out.stakeUsd, 1e-9)
        assertTrue(book.snapshot().cashUsd < 0.0)
        assertEquals(1, book.snapshot().openCount)
        assertEquals(1, history.size)
        val bets = HistoryAssembler.bets(history, book.snapshot(), HistoryAssembler.SourceFilter.PAPER)
        assertEquals(1, bets.size)
        assertEquals("open", bets.single().result)
        assertFalse(out.message.contains("Kalshi key", ignoreCase = true))
    }

    @Test
    fun blockedTicketShowsReasonNotSilent() = runBlocking {
        val ticket = ticket(TicketKind.MANUAL, "KXBTC15M-B", 0, 0.0, 0.20)
            .copy(blockedReason = "No sellers on YES right now", contracts = 0)
        val (out, book, history) = runApprove(ticket)
        assertFalse(out.ok)
        assertEquals("No sellers on YES right now", out.visibleReason)
        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(history.isEmpty())
    }

    @Test
    fun paperApproveNeverCallsPlaceOrder() = runBlocking {
        var placed = 0
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed += 1
                error("V2 client must not run in paper mode")
            }
        )
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 9L })
        val t = ticket(TicketKind.MANUAL, "KXBTC15M-X", 5, 1.0, 0.20)
        session.addManual(t)
        val out = PaperApprove.apply(session, book, t.id)
        assertTrue(out.ok)
        assertEquals(0, placed)
        assertEquals(0, session.placementCount)
    }

    private fun assertPath(ticket: TradeTicket) = runBlocking {
        val (out, book, history) = runApprove(ticket)
        assertTrue(out.message, out.ok)
        assertTrue(out.message.startsWith("PAPER"))
        val snap = book.snapshot()
        assertEquals(1, snap.openCount)
        assertEquals(ticket.ticker, snap.fills.single().ticker)
        assertEquals(out.contracts, snap.fills.single().contracts)
        assertTrue(snap.cashUsd < SignalConstantsStart)
        val debit = SignalConstantsStart - snap.cashUsd
        assertTrue(debit + 1e-9 >= out.stakeUsd)
        val bets = HistoryAssembler.bets(history, snap)
        assertTrue(bets.any { !it.live && it.ticker == ticket.ticker && it.result == "open" })
        assertTrue(history.single().approved)
        assertEquals("paper filled", history.single().result)
        assertFalse(out.message.contains("API", ignoreCase = true))
    }

    private fun runApprove(ticket: TradeTicket): Triple<PaperBuy.Outcome, PaperBook, List<TicketAttemptRow>> {
        val history = ArrayList<TicketAttemptRow>()
        val session = TicketSession(
            placeOrder = { _, _ -> error("no live V2") }
        )
        val book = PaperBook(idFactory = { "fill-${ticket.ticker}" }, nowMs = { 42L })
        session.addManual(ticket)
        val out = PaperApprove.apply(
            session = session,
            book = book,
            ticketId = ticket.id,
            onHistory = { history += it },
            nowMs = { 42L }
        )
        return Triple(out, book, history)
    }

    private fun ticket(
        kind: TicketKind,
        ticker: String,
        contracts: Int,
        stake: Double,
        px: Double,
        win: Double? = null
    ) = TradeTicket(
        id = "t-$ticker",
        ticker = ticker,
        side = "YES",
        bookSide = "bid",
        stakeUsd = stake,
        limitPrice = px,
        yesLimitPrice = px,
        contracts = contracts,
        estimatedFillUsd = stake,
        maxPayoutUsd = contracts.toDouble(),
        estimatedAvgFill = px,
        sizingNote = "test",
        kind = kind,
        winTargetUsd = win
    )

    companion object {
        private const val SignalConstantsStart = 20_000.0
    }
}
