package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperBuyTest {

    @Test
    fun manualBuyNoCredentials() {
        val book = PaperBook(initial = com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0), idFactory = { "m1" }, nowMs = { 1L })
        val out = PaperBuy.execute(book, ticket(TicketKind.MANUAL, ticker = "KXBTC15M-M", contracts = 10, stake = 2.0, px = 0.20))
        assertTrue(out.ok)
        assertEquals(10, out.contracts)
        assertEquals(2.0, out.stakeUsd, 1e-9)
        val fees = com.dirk.kalshiodds.signal.trade.KalshiFee.total(10, 0.20)
        assertEquals(1_000.0 - 2.0 - fees, book.snapshot().cashUsd, 1e-9)
        assertTrue(out.message.contains("PAPER"))
    }

    @Test
    fun hunterBuy() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val out = PaperBuy.execute(book, ticket(TicketKind.HUNTER, ticker = "KXBTC15M-H", contracts = 25, stake = 1.0, px = 0.04))
        assertTrue(out.ok)
        assertEquals(25, out.fill!!.contracts)
        assertTrue(out.fill!!.note.contains("hunter", ignoreCase = true))
    }

    @Test
    fun longShotBuy() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val out = PaperBuy.execute(
            book,
            ticket(TicketKind.HUNTER_VALUE, ticker = "KXBTC15M-L", contracts = 40, stake = 8.0, px = 0.20, win = 50.0)
        )
        assertTrue(out.ok)
        assertEquals(40, out.contracts)
        assertTrue(out.fill!!.note.contains("win-target"))
    }

    @Test
    fun winTargetConfiguredBuy() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val out = PaperBuy.execute(
            book,
            ticket(TicketKind.CONFIGURED, ticker = "KXBTC15M-C", contracts = 20, stake = 4.0, px = 0.20, win = 50.0)
        )
        assertTrue(out.ok)
        assertEquals(20, out.contracts)
        val fees = com.dirk.kalshiodds.signal.trade.KalshiFee.total(20, 0.20)
        assertEquals(1_000.0 - 4.0 - fees, book.snapshot().cashUsd, 1e-9)
    }

    @Test
    fun winTargetAbovePaperEquityCapsInsteadOfBlocking() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        // $50 target at 40¢ would want 200 ct / $80 — cash is $1,000 so force above cash: 4,000 ct @ 40¢ = $1,600.
        val out = PaperBuy.execute(
            book,
            ticket(
                TicketKind.HUNTER_VALUE,
                ticker = "KXBTC15M-CAP",
                contracts = 4_000,
                stake = 1_600.0,
                px = 0.40,
                win = 50.0
            )
        )
        assertTrue(out.message, out.ok)
        assertTrue(out.capped)
        val (maxQty, _) = PaperBuy.capContracts(4_000, 1_000.0, 0.40)
        assertEquals(maxQty, out.contracts)
        assertTrue(out.contracts in 1..2_499)
        assertEquals(out.contracts * 0.40, out.stakeUsd, 1e-9)
        assertTrue(book.snapshot().cashUsd + 1e-6 >= 0.0)
        assertTrue(book.snapshot().cashUsd < 1_000.0 - out.stakeUsd + 1e-6)
    }

    @Test
    fun blockedTicketSurfacesReason() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val t = ticket(TicketKind.MANUAL, ticker = "KXBTC15M-B", contracts = 0, stake = 0.0, px = 0.0)
            .copy(blockedReason = "No sellers on YES right now", contracts = 0)
        val out = PaperBuy.execute(book, t)
        assertFalse(out.ok)
        assertEquals("No sellers on YES right now", out.visibleReason)
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun openDuplicateShowsReasonNotSilent() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val first = ticket(TicketKind.MANUAL, ticker = "KXBTC15M-D", contracts = 5, stake = 1.0, px = 0.20)
        assertTrue(PaperBuy.execute(book, first).ok)
        val again = PaperBuy.execute(book, first.copy(id = "other"))
        assertFalse(again.ok)
        assertTrue(again.message.contains("Already have an open paper fill"))
    }

    @Test
    fun capContractsMath() {
        val (qty, capped) = PaperBuy.capContracts(400, 100.0, 0.40)
        assertTrue(capped)
        assertTrue(qty in 1..249)
        assertTrue(PaperBuy.costUsd(qty, 0.40) <= 100.0 + 1e-9)
        assertTrue(PaperBuy.costUsd(qty + 1, 0.40) > 100.0)
        val (none, _) = PaperBuy.capContracts(10, 0.01, 0.50)
        assertEquals(0, none)
    }

    @Test
    fun subPennyAskIsNotClampedToOneCent() {
        val book = PaperBook(initial = com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0), idFactory = { "p1" }, nowMs = { 1L })
        val out = PaperBuy.execute(
            book,
            ticket(TicketKind.MANUAL, ticker = "KXBTC15M-01", contracts = 100, stake = 0.10, px = 0.001)
        )
        assertTrue(out.message, out.ok)
        assertEquals(0.001, out.fill!!.limitPrice, 1e-12)
        assertEquals("0.1¢", com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(out.fill!!.limitPrice))
        assertEquals(100, out.fill!!.contracts)
    }

    @Test
    fun neverNeedsKalshiFields() {
        val book = PaperBook(com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0))
        val t = ticket(TicketKind.MANUAL, ticker = "KXBTC15M-K", contracts = 4, stake = 0.80, px = 0.20)
        val out = PaperBuy.execute(book, t)
        assertNotNull(out.fill)
        assertFalse(out.message.contains("API", ignoreCase = true))
        assertFalse(out.message.contains("Kalshi key", ignoreCase = true))
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
}
