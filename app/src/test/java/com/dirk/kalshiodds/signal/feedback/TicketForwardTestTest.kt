package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketForwardTestTest {
    private val book = BookLevelSnapshot(yes = listOf(0.03 to 200.0), no = listOf(0.96 to 40.0))
    private val clip = LiveOrderSizer.sizeWithinDepth(0.04, 40)

    private fun ticket() = TradeTicket(
        id = "proposal", ticker = "KXBTC15M-FWD", side = "YES", bookSide = "bid",
        stakeUsd = clip.allInUsd, limitPrice = 0.04, yesLimitPrice = 0.04,
        contracts = clip.count, estimatedFillUsd = clip.allInUsd,
        maxPayoutUsd = clip.count.toDouble(), estimatedAvgFill = 0.04,
        netEvUsd = 0.15 * clip.count - clip.allInUsd, sizingNote = "book",
        kind = TicketKind.HUNTER, modelChance = 0.15,
        feeUsd = clip.feeUsd, allInUsd = clip.allInUsd, visibleContracts = 40
    )

    @Test fun freezesExactlyTheSizedTicketAtTheFreshBookAsk() {
        val row = TicketForwardTest.capture(ticket(), book, "on-device AI", 0.07, 1L, 1_000_034)!!
        assertEquals(40, row.contracts)
        assertEquals(0.04, row.ask, 1e-9)
        assertEquals(40.0, row.visibleContracts, 1e-9)
        assertEquals(0.035, row.marketYes, 1e-9)
        assertEquals(clip.feeUsd, row.feeUsd, 1e-9)
        assertEquals(0.15 * 40 - clip.allInUsd, row.modeledNetUsd, 1e-9)
        assertEquals("HUNTER", row.kind)
        assertEquals(1_000_034, row.buildCode)
    }

    @Test fun rejectsUnexecutablePriceCrossedBookAndWrongFee() {
        assertNull(TicketForwardTest.capture(ticket().copy(limitPrice = 0.05), book, "AI", 0.07, 1L, 1))
        assertNull(TicketForwardTest.capture(ticket(), book.copy(yes = listOf(0.05 to 200.0)), "AI", 0.07, 1L, 1))
        assertNull(TicketForwardTest.capture(ticket().copy(feeUsd = 0.0), book, "AI", 0.07, 1L, 1))
        assertNull(TicketForwardTest.capture(ticket().copy(kind = TicketKind.MANUAL), book, "AI", 0.07, 1L, 1))
    }

    @Test fun firstSuggestionIsImmutableAndOnlySettledRowsCount() {
        val store = InMemoryResultsStore()
        val first = TicketForwardTest.capture(ticket(), book, "AI", 0.07, 1L, 100)!!
        store.insertTicketForward(listOf(first, first.copy(capturedAtMs = 2L, modelYes = 0.99)))
        store.upsertSettled(listOf(SettledWindowRow(first.ticker, first.series, "no")))
        val losing = store.ticketForward().single()
        assertEquals(1L, losing.capturedAtMs)
        assertEquals(0.15, losing.modelYes, 1e-9)
        assertEquals("no", losing.outcome)
        val pending = first.copy(ticker = "KXBTC15M-PENDING")
        val voided = first.copy(ticker = "KXBTC15M-VOID", outcome = "void")
        val stats = TicketForwardTest.summarize(listOf(losing, pending, voided))
        assertEquals(3, stats.captured)
        assertEquals(1, stats.settled)
        assertEquals(0, stats.wins)
        assertEquals(-clip.allInUsd, stats.quotedProxyPnlUsd!!, 1e-9)
        assertEquals(clip.allInUsd, stats.worstDrawdownUsd!!, 1e-9)
        assertTrue(TicketForwardTest.csv(listOf(losing)).contains("quoted_proxy_pnl_usd"))
    }
}
