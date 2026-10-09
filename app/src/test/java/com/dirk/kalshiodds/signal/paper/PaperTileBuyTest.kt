package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.HomeScorecardSummary
import com.dirk.kalshiodds.ui.ScorecardCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PaperTileBuyTest {

    @Test
    fun tapUsesTenDollarLiveAskMath() {
        val book = PaperBook(idFactory = { "p31" }, nowMs = { 1L })
        val at31 = HomeFixtures.actionableBtc().copy(
            yesAsk = 0.31,
            yesBid = 0.30,
            noAsk = 0.70,
            noBid = 0.69
        )
        val up = PaperTileBuy.place(book, at31, "YES")
        assertTrue(up.message, up.ok)
        assertEquals(30, up.contracts)
        assertEquals(0.45, HomeCopy.tenDollarWins(0.31).feeUsd, 1e-9)
        assertEquals(9.75, up.stakeUsd, 1e-9)
        assertEquals(20.25, HomeCopy.tenDollarWins(0.31).profitUsd!!, 1e-9)
        assertEquals("Paper UP · 30 ct · $9.75 · +$20.25 if it wins", up.message)
        assertEquals(1, book.snapshot().fills.size)
        assertEquals("YES", book.snapshot().fills.single().side)
        assertEquals(PaperTileBuy.SOURCE, book.snapshot().fills.single().source)
        assertEquals(90.25, book.snapshot().cashUsd, 1e-9)

        val downBook = PaperBook(idFactory = { "p70" }, nowMs = { 1L })
        val down = PaperTileBuy.place(downBook, at31, "NO")
        assertTrue(down.message, down.ok)
        assertEquals(13, down.contracts)
        assertEquals(0.20, HomeCopy.tenDollarWins(0.70).feeUsd, 1e-9)
        assertEquals(9.30, down.stakeUsd, 1e-9)
        assertEquals(3.70, HomeCopy.tenDollarWins(0.70).profitUsd!!, 1e-9)
        assertEquals("Paper DOWN · 13 ct · $9.30 · +$3.70 if it wins", down.message)
        assertEquals("NO", downBook.snapshot().fills.single().side)
    }

    @Test
    fun noAskDisablesWithClearReasonAndWritesNothing() {
        val book = PaperBook()
        val missing = HomeFixtures.actionableBtc().copy(
            yesAsk = null,
            noAsk = null,
            yesBid = null,
            noBid = null
        )
        assertEquals(PaperTileBuy.NO_ASK_UP, PaperTileBuy.disabledReason(missing, "YES"))
        assertEquals(PaperTileBuy.NO_ASK_DOWN, PaperTileBuy.disabledReason(missing, "NO"))
        assertFalse(PaperTileBuy.enabled(missing, "YES"))
        val out = PaperTileBuy.place(book, missing, "YES")
        assertFalse(out.ok)
        assertEquals(PaperTileBuy.NO_ASK_UP, out.message)
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun neverTouchesKalshiOrderOrPortfolioApis() {
        val src = java.io.File("src/main/java/com/dirk/kalshiodds/signal/paper/PaperTileBuy.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/paper/PaperTileBuy.kt")
        val text = src.readText()
        assertFalse(text.contains("KalshiTradeClient"))
        assertFalse(text.contains("GET /portfolio"))
        assertFalse(text.contains("createLimit"))
        assertFalse(text.contains("TicketSession"))
        val vm = java.io.File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        val paperFn = vm.readText().substringAfter("fun paperBuySide").substringBefore("fun paperSellTicket")
        assertTrue(paperFn.contains("PaperTileBuy.place"))
        assertTrue(paperFn.contains("resolveActionWindow"))
        assertTrue(paperFn.contains("NEXT_WINDOW_LOADING"))
        assertFalse(paperFn.contains("ticketSession.approve"))
        assertFalse(paperFn.contains("tradeClient"))
        assertFalse(paperFn.contains("GET /portfolio"))
        val buyFn = vm.readText().substringAfter("fun buyMarket").substringBefore("private fun applyScoreOverlay")
        assertTrue(buyFn.contains("resolveActionWindow"))
        assertTrue(buyFn.contains("NEXT_WINDOW_LOADING"))
        assertFalse(buyFn.contains("WINDOW_CLOSED"))
        assertFalse(buyFn.contains("Market closed"))
    }

    @Test
    fun settlementUpdatesScorecardPaperPnl() {
        val ids = AtomicInteger()
        val book = PaperBook(idFactory = { "s${ids.incrementAndGet()}" }, nowMs = { 2L })
        val market = HomeFixtures.actionableBtc().copy(
            yesAsk = 0.31,
            yesBid = 0.30,
            noAsk = 0.70,
            noBid = 0.69
        )
        val placed = PaperTileBuy.place(book, market, "YES")
        assertTrue(placed.ok)
        val open = HomeScorecardSummary.of(emptyList(), book.snapshot().liveRealizedPnlUsd)
        assertEquals(0.0, open.paperPnlUsd, 1e-9)
        assertEquals(HomeScorecardSummary.NO_SETTLED, open.line())

        val settled = book.settle(market.ticker, "yes")
        assertEquals(1, settled.size)
        assertEquals(true, settled.single().won)
        assertEquals(20.25, settled.single().pnlUsd!!, 1e-9)
        val after = HomeScorecardSummary.of(emptyList(), book.snapshot().liveRealizedPnlUsd)
        assertEquals(20.25, after.paperPnlUsd, 1e-9)
        assertTrue(after.line(), after.line().contains("paper +$20.25") || after.line() == HomeScorecardSummary.NO_SETTLED)
        assertEquals("paper +$20.25", ScorecardCopy.paperPnlLine(after.copy(settledCount = 1)))

        val lossBook = PaperBook(idFactory = { "loss" }, nowMs = { 3L })
        assertTrue(PaperTileBuy.place(lossBook, market, "NO").ok)
        lossBook.settle(market.ticker, "yes")
        val lost = HomeScorecardSummary.of(emptyList(), lossBook.snapshot().liveRealizedPnlUsd)
        assertEquals(-9.30, lost.paperPnlUsd, 1e-9)
        assertEquals("paper −$9.30", ScorecardCopy.paperPnlLine(lost.copy(settledCount = 1)))
    }

    @Test
    fun openPositionLineUsesLiveContractsAndCents() {
        val fill = HomeFixtures.openPaperFill(
            ticker = "KXBTC15M-X",
            side = "YES",
            contracts = 14,
            limitPrice = 0.70,
            stakeUsd = 9.30
        )
        assertEquals("Paper: UP 14 @ 70c", HomeCopy.paperPositionLine(fill))
        assertEquals(
            "Paper: UP 14 @ 70c",
            HomeCopy.paperPositionLine(
                com.dirk.kalshiodds.signal.paper.PaperBookState(fills = listOf(fill)),
                "KXBTC15M-X"
            )
        )
        assertNull(HomeCopy.paperPositionLine(fill.copy(settled = true, won = true, pnlUsd = 3.70)))
    }

    @Test
    fun liveCapUnchanged() {
        assertEquals(10.0, HomeCopy.TILE_STAKE_USD, 1e-9)
        assertEquals(10.0, com.dirk.kalshiodds.signal.config.SignalConstants.LIVE_ALL_IN_CAP_USD, 1e-9)
        assertNotNull(PaperTileBuy::place)
    }
}
