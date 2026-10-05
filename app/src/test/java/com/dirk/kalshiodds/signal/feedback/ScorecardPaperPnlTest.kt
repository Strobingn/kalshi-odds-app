package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.ui.ScorecardCopy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardPaperPnlTest {

    @Test
    fun cardPnlEqualsBankrollMinusResetAndSkipsImaginaryDollars() {
        val ids = AtomicInteger()
        val book = PaperBook(
            nowMs = { 10_000L },
            idFactory = { "fill-${ids.incrementAndGet()}" }
        )
        val ai = book.fillTenDollar(
            ticker = "KXBTC15M-AI",
            side = "YES",
            ask = 0.40,
            contracts = 10,
            feeUsd = 0.17,
            allInUsd = 4.17,
            source = "AI hunter",
            message = "ai paper"
        )
        assertTrue(ai.ok)
        book.settle("KXBTC15M-AI", "yes")
        val manual = book.fillTenDollar(
            ticker = "KXBTC15M-MAN",
            side = "NO",
            ask = 0.50,
            contracts = 4,
            feeUsd = 0.07,
            allInUsd = 2.07,
            source = PaperTileBuy.SOURCE,
            message = "manual paper"
        )
        assertTrue(manual.ok)
        book.settle("KXBTC15M-MAN", "yes")
        // A real penny fill moves the bankroll, so the card has to keep it.
        val pennyFill = book.fillTenDollar(
            ticker = "KXBTC15M-PENNYFILL",
            side = "YES",
            ask = 0.001,
            contracts = 100,
            feeUsd = 0.0,
            allInUsd = 0.10,
            source = "AI hunter",
            message = "penny fill"
        )
        assertTrue(pennyFill.ok)
        book.settle("KXBTC15M-PENNYFILL", "yes")

        val paper = book.snapshot()
        val entries = listOf(
            log("KXBTC15M-IMAGINARY", side = "YES", ask = 0.001, contracts = 9_346, stake = 10.0),
            log("KXBTC15M-LOG", side = "YES", ask = 0.55, contracts = 18, stake = 10.0),
            log("KXBTC15M-HIGH", side = "NO", ask = 0.991, contracts = 10, stake = 10.0)
        )
        val view = ScorecardCopy.of(entries, paper)
        val cardSum = view.ledger.ai.money.pnlUsd + view.ledger.manual.money.pnlUsd
        assertEquals(view.ledger.combined.money.pnlUsd, cardSum, 1e-9)
        assertEquals(paper.cashUsd - paper.startingUsd, cardSum, 1e-6)
        assertEquals(paper.liveRealizedPnlUsd, cardSum, 1e-6)

        assertTrue(view.ledger.picks.none { it.ticker == "KXBTC15M-IMAGINARY" })
        assertTrue(view.ledger.picks.none { it.ticker == "KXBTC15M-HIGH" })
        val logRow = view.ledger.picks.single { it.ticker == "KXBTC15M-LOG" }
        assertTrue(logRow.won)
        assertFalse(logRow.countsMoney)
        assertEquals(0.0, logRow.pnlUsd!!, 1e-9)
        assertNull(logRow.contracts)
        assertTrue(view.recent.single { it.ticker == "KXBTC15M-LOG" }.line.contains(ScorecardCopy.WL_ONLY))
        assertFalse(view.recent.single { it.ticker == "KXBTC15M-LOG" }.line.contains("$"))

        val pennyRow = view.ledger.picks.single { it.ticker == "KXBTC15M-PENNYFILL" }
        assertTrue(pennyRow.countsMoney)
        assertEquals(pennyFill.fill!!.let { 100.0 - 0.10 }, pennyRow.pnlUsd!!, 1e-6)

        assertEquals(
            view.ledger.ai.settledCount + view.ledger.manual.settledCount,
            view.ledger.combined.settledCount
        )
        assertEquals(view.settledCount, view.ledger.combined.settledCount)
        val metrics = ScorecardMetrics.compute(entries)
        assertTrue(metrics.sampleCount != view.settledCount)
        val header = ScorecardCopy.headerCounts(view.ledger)
        assertEquals(
            "Open ${view.ledger.openCount} · void ${view.ledger.voidCount} · settled ${view.settledCount}",
            header
        )
        assertFalse(header.contains("settled ${metrics.sampleCount}"))

        val before = cardSum
        book.reset()
        val reset = book.snapshot()
        assertEquals(1, reset.archived.size)
        assertEquals(3, reset.archived.single().fills.size)
        assertTrue(reset.fills.isEmpty())
        val after = ScorecardCopy.of(entries, reset)
        assertEquals(reset.cashUsd - reset.startingUsd, after.ledger.combined.money.pnlUsd, 1e-6)
        assertEquals(0.0, after.ledger.combined.money.pnlUsd, 1e-9)
        assertTrue(after.ledger.picks.none { it.countsMoney })
        val archive = after.archive
        assertNotNull(archive)
        assertEquals(before, archive!!.money.pnlUsd, 1e-6)
        assertTrue(after.allLines().contains(ScorecardCopy.ARCHIVE_TITLE))
        assertTrue(after.allLines().contains(ScorecardCopy.ARCHIVE_NOTE))
    }

    @Test
    fun priceBandIncludesTwoCentsAndDropsJustOutside() {
        assertTrue(ScorecardLedger.scoreableLogPrice(null))
        assertTrue(ScorecardLedger.scoreableLogPrice(0.02))
        assertTrue(ScorecardLedger.scoreableLogPrice(0.98))
        assertFalse(ScorecardLedger.scoreableLogPrice(0.019))
        assertFalse(ScorecardLedger.scoreableLogPrice(0.981))
        assertNull(ScorecardLedger.paperClipFromAsk(0.001))
        assertNotNull(ScorecardLedger.paperClipFromAsk(0.34))

        val snap = ScorecardLedger.of(
            listOf(
                log("KXBTC15M-LO", "YES", 0.02),
                log("KXBTC15M-HI", "YES", 0.98),
                log("KXBTC15M-UNDER", "YES", 0.019),
                log("KXBTC15M-OVER", "NO", 0.981)
            ),
            emptyList()
        )
        assertEquals(listOf("KXBTC15M-LO", "KXBTC15M-HI").sorted(), snap.picks.map { it.ticker }.sorted())
        assertEquals(2, snap.combined.settledCount)
        assertEquals(0.0, snap.combined.money.pnlUsd, 1e-9)
    }

    private fun log(
        ticker: String,
        side: String,
        ask: Double,
        contracts: Int? = null,
        stake: Double? = null
    ) = PredictionLogEntry(
        ticker = ticker,
        series = "KXBTC15M",
        predictedYes = if (side == "YES") 0.70 else 0.30,
        predictedNo = if (side == "YES") 0.30 else 0.70,
        marketMid = 0.50,
        timestampMs = 1_000L,
        closeTimeMs = 1_000L,
        outcome = if (side == "YES") "yes" else "no",
        score = 1,
        predictedSide = side,
        settledAtMs = 1_000L,
        entryAsk = ask,
        contracts = contracts,
        stakeUsd = stake,
        feeUsd = 0.07
    )
}
