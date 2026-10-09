package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.HomeScorecardSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaperAutopilotGuardsTest {
    private val nowMs = HomeFixtures.NOW_MS
    private val settings = SignalSettings(
        paperTradingEnabled = true,
        aiPaperAutopilotEnabled = true,
        paperKellyFraction = 0.5
    )

    @Before
    fun reset() {
        PaperAutopilot.resetSession()
    }

    @Test
    fun windowBudgetCapsEveryClipInTheWindow() {
        val book = PaperBook(idFactory = { "w1" }, nowMs = { nowMs })
        val market = edge(yesAsk = 0.20, aiYes = 80.0)
        val first = enter(book, market)
        assertNotNull(first)
        val spent = book.snapshot().fills.sumOf { it.stakeUsd }
        val free = PaperAutopilot.freeBankroll(book.snapshot()) + spent
        val budget = PaperAutopilot.windowBudgetUsd(
            free,
            PaperKellySizer.fullKelly(first!!.aiPct!! / 100.0, first.limitPrice)
        )
        assertTrue("spent $spent budget $budget", spent <= budget + 0.05)
        val moved = edge(yesAsk = 0.30, aiYes = 80.0)
        assertNull(PaperAutopilot.consider(book, moved, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("budget"))
        assertEquals(1, book.snapshot().fills.size)
    }

    @Test
    fun freeBankrollSubtractsOpenExposure() {
        val book = PaperBook(idFactory = { "f1" }, nowMs = { nowMs })
        val parked = book.forceFill(
            ticker = "KXBTC15M-OPEN",
            side = "YES",
            limitPrice = 0.50,
            contracts = 400,
            source = "manual",
            note = "open"
        )
        assertNotNull(parked)
        val snap = book.snapshot()
        // 400 × 50¢ + Kalshi fee ceil(0.07 × 400 × 0.25) = $7 on the paper fill.
        assertEquals(207.0, snap.openStakeUsd, 1e-6)
        assertEquals(793.0, PaperAutopilot.freeBankroll(snap), 1e-6)
        val market = edge(ticker = "KXBTC15M-NEXT", yesAsk = 0.25, aiYes = 80.0)
        val fill = enter(book, market)!!
        val anchored = PaperAutopilot.anchoredYes(0.80, 0.25)
        val onFull = PaperKellySizer.size(anchored, 0.25, 1_000.0, 0.5, depthContracts = 100_000)
        val onFree = PaperKellySizer.size(
            anchored,
            0.25,
            PaperAutopilot.freeBankroll(snap),
            0.5,
            depthContracts = 100_000,
            maxStakeUsd = PaperAutopilot.windowBudgetUsd(
                PaperAutopilot.freeBankroll(snap),
                PaperKellySizer.fullKelly(anchored, 0.25)
            )
        )
        assertTrue(onFull.contracts > onFree.contracts)
        assertEquals(onFree.contracts, fill.contracts)
    }

    @Test
    fun oppositeSideIsBlockedAfterAFill() {
        val book = PaperBook(idFactory = { "o1" }, nowMs = { nowMs })
        val yes = enter(book, edge(yesAsk = 0.20, aiYes = 80.0), depth = 30) // ≥ \$5 floor (0.3.40)
        assertEquals("YES", yes!!.side)
        val no = edge(yesAsk = 0.75, aiYes = 30.0)
        assertNull(PaperAutopilot.consider(book, no, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("opposite"))
        assertEquals(1, book.snapshot().fills.size)
    }

    @Test
    fun minEdgeNeedsTwoConsecutiveEvaluations() {
        val book = PaperBook()
        val thin = edge(yesAsk = 0.20, aiYes = 30.0)
        assertNull(PaperAutopilot.consider(book, thin, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("4pp"))
        val wide = edge(ticker = "KXBTC15M-WIDE", yesAsk = 0.20, aiYes = 80.0)
        assertNull(PaperAutopilot.consider(book, wide, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("2 consecutive"))
        val fill = PaperAutopilot.consider(book, wide, settings, nowMs, yesDepth = 100_000, noDepth = 100_000)
        assertNotNull(fill)
    }

    @Test
    fun asksUnder10cOrOver90cAreBlocked() {
        val book = PaperBook()
        val cheap = edge(yesAsk = 0.05, aiYes = 80.0)
        assertNull(PaperAutopilot.consider(book, cheap, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("10"))
        val dear = edge(ticker = "KXBTC15M-DEAR", yesAsk = 0.95, aiYes = 99.0)
        assertNull(PaperAutopilot.consider(book, dear, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun lastSixtySecondsBlocksEntry() {
        val book = PaperBook()
        val late = edge(yesAsk = 0.20, aiYes = 80.0).copy(closeTimeEpochMs = nowMs + 30_000L)
        assertNull(PaperAutopilot.consider(book, late, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("60"))
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun clampBindingSkipsTheWindow() {
        val book = PaperBook()
        val clamped = edge(yesAsk = 0.40, aiYes = 98.0)
        assertNull(PaperAutopilot.consider(book, clamped, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("clamped"))
        val later = edge(yesAsk = 0.40, aiYes = 70.0)
        assertNull(PaperAutopilot.consider(book, later, settings, nowMs, yesDepth = 100_000, noDepth = 100_000))
        assertTrue(book.snapshot().lastMessage!!.contains("clamped"))
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun aiPickScoresModelWinnerAndKeepsEvSide() {
        val modelUp = log("KXBTC15M-UP", predictedYes = 0.80, predictedSide = "NO", outcome = "yes")
        val clamped = log("KXBTC15M-CLAMP", predictedYes = 0.98, predictedSide = "NO", outcome = "yes")
        val summary = HomeScorecardSummary.of(listOf(modelUp, clamped), 12.0)
        assertEquals(1, summary.wins)
        assertEquals(0, summary.losses)
        assertEquals(0, summary.evSideWins)
        assertEquals(2, summary.evSideLosses)
        val line = summary.line()
        assertTrue(line.contains("1-0"))
        assertTrue(line.contains("EV side 0-2"))
        assertTrue(line.contains("lifetime paper"))
    }

    @Test
    fun lifetimePaperPnlSurvivesLedgerTrim() {
        val seq = java.util.concurrent.atomic.AtomicInteger()
        val book = PaperBook(idFactory = { "p${seq.incrementAndGet()}" }, nowMs = { seq.get().toLong() })
        val n = com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_LEDGER_MAX + 5
        repeat(n) { i ->
            book.forceFill("KXBTC15M-L$i", "YES", 0.10, 1, "test", "n")
            book.settle("KXBTC15M-L$i", "no")
        }
        val snap = book.snapshot()
        assertTrue(snap.fills.size < n)
        assertNotNull(snap.lifetimeRealizedPnlUsd)
        val summary = HomeScorecardSummary.of(
            listOf(log("KXBTC15M-SHOW", 0.70, "YES", "yes")),
            snap.lifetimeRealizedPnlUsd!!
        )
        assertEquals(snap.lifetimeRealizedPnlUsd!!, summary.paperPnlUsd, 1e-6)
        assertTrue(summary.line().contains("lifetime paper"))
        assertTrue(snap.syncTail.isNotEmpty() || snap.fillsForSync().size >= n)
    }

    private fun enter(
        book: PaperBook,
        market: com.dirk.kalshiodds.domain.MarketUiModel,
        depth: Int = 100_000
    ) = run {
        PaperAutopilot.consider(book, market, settings, nowMs, yesDepth = depth, noDepth = depth)
        PaperAutopilot.consider(book, market, settings, nowMs, yesDepth = depth, noDepth = depth)
    }

    private fun edge(
        ticker: String = "KXBTC15M-25SEP181700-50",
        yesAsk: Double,
        aiYes: Double
    ) = HomeFixtures.market(
        ticker = ticker,
        seriesLabel = "Bitcoin",
        yesAsk = yesAsk,
        aiYes = aiYes,
        predicted = "YES",
        closeMs = nowMs + 372_000L
    )

    private fun log(ticker: String, predictedYes: Double, predictedSide: String, outcome: String) =
        PredictionLogEntry(
            ticker = ticker,
            series = "KXBTC15M",
            predictedYes = predictedYes,
            predictedNo = 1.0 - predictedYes,
            marketMid = 0.90,
            timestampMs = 1L,
            closeTimeMs = 2L,
            outcome = outcome,
            predictedSide = predictedSide
        )
}
