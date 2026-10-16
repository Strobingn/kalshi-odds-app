package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperArchive
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.ui.ScorecardCopy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.29: Combined Paper P&L is the current book's settled fills,
 * including rows that rolled past the 80-fill ledger. Updating the
 * app recomputes the cards; it does not wipe the book.
 */
class ScorecardPaperPnlTest {

    @Test
    fun combinedRealPnlMatchesBankrollWithMoreThanEightyFills() {
        val n = SignalConstants.PAPER_LEDGER_MAX + 8
        val seq = AtomicInteger()
        val book = PaperBook(idFactory = { "p${seq.getAndIncrement()}" }, nowMs = { seq.get().toLong() })
        repeat(n) { i ->
            val ticker = "KXBTC15M-L$i"
            val fill = book.forceFill(
                ticker = ticker,
                side = "YES",
                limitPrice = 0.10,
                contracts = 1,
                source = "AI hunter",
                note = "ledger"
            )
            assertTrue(fill != null)
            book.settle(ticker, "yes")
        }
        book.acknowledgePaperSync(Long.MAX_VALUE)
        val paper = book.snapshot()
        assertTrue(paper.syncTail.isEmpty())
        assertTrue(paper.fills.size <= SignalConstants.PAPER_LEDGER_MAX)
        assertTrue(paper.scorecardFills().size >= n)
        val orphan = PredictionLogEntry(
            ticker = "KXBTC15M-NOFILL",
            series = "KXBTC15M",
            predictedYes = 0.80,
            predictedNo = 0.20,
            marketMid = 0.40,
            timestampMs = 9L,
            closeTimeMs = 9L,
            outcome = "yes",
            predictedSide = "YES",
            settledAtMs = 9L,
            entryAsk = 0.001,
            contracts = 9_346,
            stakeUsd = 10.0,
            feeUsd = 0.0
        )
        val view = ScorecardCopy.of(listOf(orphan), paper)
        val expected = paper.paperBankrollUsd - paper.startingUsd
        assertEquals(expected, view.ledger.combined.money.pnlUsd, 0.01)
        assertEquals(expected, view.ledger.ai.money.pnlUsd, 0.01)
        assertEquals(0.0, view.ledger.manual.money.pnlUsd, 0.01)
        assertNull(view.reconcileWarning)
        assertTrue(view.ledger.combined.wins >= n)
        assertEquals(0, view.ledger.hypotheticalPicks)
        val biggest = view.ledger.combined.money.biggestWinUsd ?: 0.0
        assertTrue("biggest win $biggest is a real fill, not a 0.1¢ clip", biggest < 20.0)
    }

    @Test
    fun archivedFillsMoveToTheirOwnCardAndDoNotChangeCombined() {
        val old = PaperFill(
            id = "old",
            ticker = "KXBTC15M-OLD",
            side = "YES",
            stakeUsd = 10.0,
            contracts = 20,
            limitPrice = 0.40,
            source = "AI hunter",
            createdAtMs = 1L,
            settled = true,
            won = true,
            pnlUsd = 9_336.0,
            note = "pre-reset"
        )
        val live = old.copy(id = "live", ticker = "KXBTC15M-LIVE", pnlUsd = -1.50, won = false, createdAtMs = 2L)
        val paper = PaperBookState(
            startingUsd = 1_000.0,
            cashUsd = 998.50,
            fills = listOf(live, old),
            lifetimeRealizedPnlUsd = -1.50,
            archived = listOf(
                PaperArchive(
                    archivedAtMs = 5L,
                    startingUsd = 1_000.0,
                    cashUsd = 10.0,
                    fills = listOf(old)
                )
            )
        )
        val view = ScorecardCopy.of(emptyList(), paper)
        assertEquals(-1.50, view.ledger.combined.money.pnlUsd, 0.01)
        assertNull(view.reconcileWarning)
        assertEquals(1, view.archive.settledCount)
        assertEquals(9_336.0, view.archive.pnlUsd, 0.01)
        assertTrue(view.allLines().any { it.contains(ScorecardCopy.ARCHIVE_TITLE) })
        assertEquals("1-0 · +\$9336.00 · 1 fills", view.archive.line)
    }

    @Test
    fun mismatchShowsVisibleWarning() {
        val live = PaperFill(
            id = "live",
            ticker = "KXBTC15M-LIVE",
            side = "YES",
            stakeUsd = 1.0,
            contracts = 2,
            limitPrice = 0.40,
            source = "AI hunter",
            createdAtMs = 2L,
            settled = true,
            won = true,
            pnlUsd = 1.0,
            note = "live"
        )
        val paper = PaperBookState(
            startingUsd = 1_000.0,
            lifetimeRealizedPnlUsd = 50.0,
            fills = listOf(live)
        )
        val view = ScorecardCopy.of(emptyList(), paper)
        assertEquals(1.0, view.ledger.combined.money.pnlUsd, 0.01)
        assertEquals(ScorecardCopy.RECONCILE_WARN, view.reconcileWarning)
        assertTrue(view.allLines().contains(ScorecardCopy.RECONCILE_WARN))
    }
}
