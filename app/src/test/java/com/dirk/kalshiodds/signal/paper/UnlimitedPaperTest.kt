package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.decision.HonestScorecard
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.charts.rich.RichTradeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regular paper may print as many fills as the window can take, including both sides.
 * Live order code is not on this path.
 */
class UnlimitedPaperTest {
    private val nowMs = HomeFixtures.NOW_MS
    private val settings = SignalSettings(
        paperTradingEnabled = true,
        aiPaperAutopilotEnabled = true,
        paperKellyFraction = 0.5
    )
    private var ids = 0

    @Before
    fun reset() {
        PaperAutopilot.resetSession()
        ids = 0
    }

    @Test
    fun multipleBuysSameTickerSameSideEachGetARow() {
        val book = book()
        val a = book.explicitFill("KXBTC15M-25SEP181700-50", "YES", 0.40, 4, "manual", "a")
        val b = book.explicitFill("KXBTC15M-25SEP181700-50", "YES", 0.42, 3, "manual", "b")
        assertTrue(a.ok)
        assertTrue(b.ok)
        val open = book.snapshot().fills.filter { !it.settled }
        assertEquals(2, open.size)
        assertEquals(listOf("YES", "YES"), open.map { it.side }.sorted())
        assertEquals(2, open.map { it.id }.toSet().size)
        assertEquals(1000.0 - a.stakeUsd - b.stakeUsd, book.snapshot().cashUsd, 1e-6)
        assertTrue(PaperAutopilot.freeBankroll(book.snapshot()) < 1000.0)
    }

    @Test
    fun yesThenNoInTheSameWindowBothFill() {
        val book = book()
        val yes = enter(book, edge(yesAsk = 0.20, aiYes = 80.0), depth = 40)
        assertEquals("YES", yes!!.side)
        val noMarket = edge(yesAsk = 0.78, aiYes = 25.0, spotUsd = 66_700.0, floorStrike = 67_000.0)
        assertNull(PaperAutopilot.consider(book, noMarket, settings, nowMs, yesDepth = 100_000, noDepth = 40))
        val no = PaperAutopilot.consider(book, noMarket, settings, nowMs, yesDepth = 100_000, noDepth = 40)
        assertNotNull(no)
        assertEquals("NO", no!!.side)
        val open = book.snapshot().fills.filter { !it.settled }
        assertEquals(setOf("YES", "NO"), open.map { it.side }.toSet())
        assertEquals(yes.ticker, no.ticker)
        assertFalse(book.snapshot().lastMessage!!.contains("opposite"))
        val free = PaperAutopilot.freeBankroll(book.snapshot())
        assertEquals(1000.0 - open.sumOf { it.stakeUsd }, free, 1e-6)
        assertTrue(free < 1000.0 - yes.stakeUsd + 1e-6)
    }

    @Test
    fun shadowStillRefusesTheOppositeSide() {
        val book = book()
        val yes = enter(book, edge(yesAsk = 0.20, aiYes = 80.0), depth = 40)
        assertEquals("YES", yes!!.side)
        val shadow = ShadowBook(idFactory = { "s${ids++}" }, nowMs = { nowMs })
        val out = AutopilotStep.run(
            paperBook = book,
            shadowBook = shadow,
            market = edge(yesAsk = 0.78, aiYes = 25.0, spotUsd = 66_700.0, floorStrike = 67_000.0),
            settings = settings,
            mode = AutopilotMode.SHADOW,
            nowMs = nowMs,
            yesAsk = 0.78,
            noAsk = 0.22,
            yesDepth = 100_000,
            noDepth = 40,
            book = null,
            assessment = null,
            clientOrderId = "shadow-opp"
        )
        assertTrue(out is AutopilotStep.Outcome.Skip)
        assertTrue((out as AutopilotStep.Outcome.Skip).reason!!.contains("opposite"))
        assertTrue(shadow.snapshot().tickets.isEmpty())
        assertEquals(1, book.snapshot().fills.size)
    }

    @Test
    fun freeBankrollShrinksAndDepthAlreadyTakenIsNotReused() {
        val book = book()
        val first = enter(book, edge(yesAsk = 0.20, aiYes = 80.0), depth = 40)!!
        assertEquals(40, first.contracts)
        val free = PaperAutopilot.freeBankroll(book.snapshot())
        assertTrue(free < 1000.0)
        assertEquals(1000.0 - first.stakeUsd, free, 1e-6)
        val moved = edge(yesAsk = 0.24, aiYes = 80.0)
        assertNull(PaperAutopilot.consider(book, moved, settings, nowMs, yesDepth = 40, noDepth = 40))
        assertNull(PaperAutopilot.consider(book, moved, settings, nowMs, yesDepth = 40, noDepth = 40))
        assertEquals(1, book.snapshot().fills.size)
        assertTrue(book.snapshot().lastMessage!!.contains("no size") || book.snapshot().lastMessage!!.contains("depth"))

        val wider = edge(ticker = "KXBTC15M-25SEP181715-50", yesAsk = 0.24, aiYes = 80.0)
        val p = PaperAutopilot.anchoredYes(0.80, PaperAutopilot.marketYes(wider, 0.24, wider.noAsk)!!)
        val shown = 80
        val left = PaperAutopilot.depthRemaining(book.snapshot().fills, wider.ticker, "YES", shown)
        assertEquals(80, left)
        PaperAutopilot.consider(book, wider, settings, nowMs, yesDepth = shown, noDepth = shown)
        val second = PaperAutopilot.consider(book, wider, settings, nowMs, yesDepth = shown, noDepth = shown)!!
        val sized = PaperKellySizer.size(
            p, 0.24, free, 0.5, depthContracts = shown, capFullKelly = false
        )
        assertEquals(sized.contracts, second.contracts)
        assertTrue(second.contracts <= shown)
        val after = PaperAutopilot.depthRemaining(book.snapshot().fills, wider.ticker, "YES", shown)!!
        assertEquals(shown - second.contracts, after)
        assertTrue(PaperAutopilot.freeBankroll(book.snapshot()) < free)
    }

    @Test
    fun sameQuoteSnapshotDoesNotPrintAnotherFill() {
        val book = book()
        val market = edge(yesAsk = 0.20, aiYes = 80.0)
        val snap = BookLevelSnapshot(yes = listOf(0.18 to 40.0), no = listOf(0.80 to 80.0))
        assertNotNull(enter(book, market, depth = 100_000, bookSnap = snap))
        repeat(4) {
            assertNull(
                PaperAutopilot.consider(
                    book, market, settings, nowMs,
                    yesDepth = 100_000, noDepth = 100_000, book = snap
                )
            )
        }
        assertEquals(1, book.snapshot().fills.size)
        val changed = BookLevelSnapshot(yes = listOf(0.18 to 40.0), no = listOf(0.79 to 90.0))
        val nextAsk = edge(yesAsk = 0.21, aiYes = 80.0)
        // Same-snapshot ticks already re-qualified the edge, so a new book can fill on the next tick.
        assertNotNull(PaperAutopilot.consider(book, nextAsk, settings, nowMs, yesDepth = 100_000, noDepth = 100_000, book = changed))
        assertNull(PaperAutopilot.consider(book, nextAsk, settings, nowMs, yesDepth = 100_000, noDepth = 100_000, book = changed))
        assertEquals(2, book.snapshot().fills.count { it.side == "YES" })
    }

    @Test
    fun bothSidesCanQualifyOnOneWindowWithoutASecondFillOnTheSameSnapshot() {
        val book = book()
        val market = edge(yesAsk = 0.50, aiYes = 50.0, spotUsd = 67_000.0, floorStrike = 67_000.0)
        fun step() = PaperAutopilot.consider(
            book, market, settings, nowMs,
            yesAsk = 0.30, noAsk = 0.30, yesDepth = 100_000, noDepth = 100_000
        )
        assertNull(step())
        assertNotNull(step())
        assertNotNull(step())
        val sides = book.snapshot().fills.map { it.side }.toSet()
        assertEquals(setOf("YES", "NO"), sides)
        assertEquals(2, book.snapshot().fills.size)
        assertNull(step())
        assertNull(step())
        assertEquals(2, book.snapshot().fills.size)
    }

    @Test
    fun settlementPaysTheWinnerAndZerosTheLoserAndWindowPnlSumsBoth() {
        val book = book()
        val ticker = "KXBTC15M-25SEP181700-50"
        assertTrue(book.explicitFill(ticker, "YES", 0.40, 1, "AI paper autopilot", "yes").ok)
        assertTrue(book.explicitFill(ticker, "NO", 0.45, 1, "AI paper autopilot", "no").ok)
        val settled = book.settle(ticker, "yes")
        assertEquals(2, settled.size)
        val yes = settled.first { it.side == "YES" }
        val no = settled.first { it.side == "NO" }
        assertEquals(true, yes.won)
        assertEquals(false, no.won)
        val feeYes = KalshiFee.total(1, 0.40)
        val feeNo = KalshiFee.total(1, 0.45)
        assertEquals(HonestScorecard.pnlAfterFee(1, 0.40, yes.stakeUsd, true, false), yes.pnlUsd!!, 1e-9)
        assertEquals(HonestScorecard.pnlAfterFee(1, 0.45, no.stakeUsd, false, false), no.pnlUsd!!, 1e-9)
        assertEquals(0.15 - feeYes - feeNo, yes.pnlUsd!! + no.pnlUsd!!, 1e-9)
        assertEquals(0.0, no.pnlUsd!! + no.stakeUsd, 1e-9)
        assertEquals(1_000.0 + yes.pnlUsd!! + no.pnlUsd!!, book.snapshot().cashUsd, 1e-6)

        val trades = book.snapshot().fills.mapNotNull { RichTradeMath.fromPaper(it) }
        val window = RichTradeMath.windowSplits(trades).single()
        assertEquals(2, window.n)
        assertEquals(yes.pnlUsd!! + no.pnlUsd!!, window.total, 1e-9)
        assertTrue(window.label.contains("PM"))
        assertTrue(window.up > 0.0)
        assertTrue(window.down < 0.0)

        val entry = log(ticker)
        val ledger = ScorecardLedger.of(listOf(entry), book.snapshot().fills)
        val windowPnl = ScorecardMetrics.windowPnlUsd(listOf(entry), ledger)
        assertEquals(yes.pnlUsd!! + no.pnlUsd!!, windowPnl!!, 1e-9)
    }

    @Test
    fun partialSellOfSixAt55CentsShows55CentsAndFifoClosesOldestFirst() {
        val book = book()
        val ticker = "KXBTC15M-25SEP181700-50"
        assertTrue(book.explicitFill(ticker, "YES", 0.40, 10, "manual", "ten").ok)
        val sold = book.sell(sellTicket(ticker, "YES", 6, 0.55))
        assertNotNull(sold)
        assertEquals(0.55, sold!!.exitPrice!!, 1e-12)
        assertEquals("55¢", KalshiQuoteDisplay.formatPriceCents(sold.shownPrice()))
        val entry = KalshiFee.totalCost(10, 0.40)
        val exitFee = KalshiFee.total(6, 0.55)
        assertEquals(6 * 0.55 - exitFee - entry * 6.0 / 10.0, sold.pnlUsd!!, 1e-6)
        assertTrue(sold.settled)
        assertEquals(6, sold.contracts)
        val open = book.snapshot().fills.filter { !it.settled }
        assertEquals(1, open.size)
        assertEquals(4, open.single().contracts)
        assertEquals(0.40, open.single().limitPrice, 1e-12)
        assertTrue(book.snapshot().lastMessage!!.contains("55¢"))
        val bets = HistoryAssembler.bets(emptyList(), book.snapshot())
        val row = bets.first { it.result == "sold" }
        assertEquals("55¢", KalshiQuoteDisplay.formatPriceCents(row.price))
        assertTrue(ScorecardCopyLine(sold).contains("55¢"))

        val fifo = book()
        assertTrue(fifo.explicitFill(ticker, "YES", 0.30, 4, "manual", "old").ok)
        assertTrue(fifo.explicitFill(ticker, "YES", 0.50, 6, "manual", "new").ok)
        val closed = fifo.sell(sellTicket(ticker, "YES", 5, 0.55))
        assertNotNull(closed)
        val soldRows = fifo.snapshot().fills.filter { it.outcome == "sell" }
        assertEquals(2, soldRows.size)
        assertTrue(soldRows.all { it.exitPrice == 0.55 })
        assertEquals(4, soldRows.first { it.limitPrice == 0.30 }.contracts)
        assertEquals(1, soldRows.first { it.limitPrice == 0.50 }.contracts)
        assertEquals(5, fifo.snapshot().fills.filter { !it.settled }.sumOf { it.contracts })
        val fee5 = KalshiFee.total(5, 0.55)
        val oldStake = KalshiFee.totalCost(4, 0.30)
        val newStake = KalshiFee.totalCost(6, 0.50)
        val fifoPnl = (4 * 0.55 - fee5 * 4.0 / 5.0 - oldStake) + (1 * 0.55 - fee5 * 1.0 / 5.0 - newStake / 6.0)
        assertEquals(fifoPnl, soldRows.sumOf { it.pnlUsd ?: 0.0 }, 1e-6)
    }

    @Test
    fun syncKeysAreUniquePerFill() {
        val book = book()
        val ticker = "KXBTC15M-25SEP181700-50"
        assertTrue(book.explicitFill(ticker, "YES", 0.40, 2, "manual", "a").ok)
        assertTrue(book.explicitFill(ticker, "YES", 0.41, 2, "manual", "b").ok)
        assertTrue(book.explicitFill(ticker, "NO", 0.44, 2, "manual", "c").ok)
        book.sell(sellTicket(ticker, "YES", 1, 0.55))
        val rows = book.snapshot().fillsForSync()
        assertTrue(rows.size >= 4)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val packed = SupabaseSync().pack(SupabaseSync.LocalBundle(paperFills = rows))
            .filter { it.kind == "paper" }
        val keys = packed.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.contains("paper:") })
        assertTrue(rows.map { it.id }.all { id -> keys.any { it.endsWith(id) } })
        assertTrue(packed.any { it.payload.contains("\"exitPrice\"") })
    }

    private fun ScorecardCopyLine(fill: PaperFill): String =
        com.dirk.kalshiodds.ui.ScorecardCopy.autopilotBet(
            fill.copy(source = "AI paper autopilot", pickSource = PaperPickSource.AUTOPILOT.label)
        ).line

    private fun book() = PaperBook(
        initial = PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0),
        idFactory = { "u${ids++}" },
        nowMs = { nowMs }
    )

    private fun enter(
        book: PaperBook,
        market: com.dirk.kalshiodds.domain.MarketUiModel,
        depth: Int,
        bookSnap: BookLevelSnapshot? = null
    ): PaperFill? {
        PaperAutopilot.consider(
            book, market, settings, nowMs,
            yesDepth = depth, noDepth = depth, book = bookSnap
        )
        return PaperAutopilot.consider(
            book, market, settings, nowMs,
            yesDepth = depth, noDepth = depth, book = bookSnap
        )
    }

    private fun edge(
        ticker: String = "KXBTC15M-25SEP181700-50",
        yesAsk: Double,
        aiYes: Double,
        spotUsd: Double = 67_240.0,
        floorStrike: Double = 67_000.0
    ) = HomeFixtures.market(
        ticker = ticker,
        seriesLabel = "Bitcoin",
        yesAsk = yesAsk,
        aiYes = aiYes,
        predicted = "YES",
        closeMs = nowMs + 372_000L,
        floorStrike = floorStrike,
        spotUsd = spotUsd,
        spotDelta = spotUsd - floorStrike
    )

    private fun sellTicket(ticker: String, side: String, contracts: Int, px: Double) = TradeTicket(
        id = "sell-$ticker-$side-$contracts-${ids++}",
        ticker = ticker,
        side = side,
        bookSide = "ask",
        stakeUsd = contracts * px,
        limitPrice = px,
        yesLimitPrice = if (side == "NO") 1.0 - px else px,
        contracts = contracts,
        estimatedFillUsd = contracts * px,
        maxPayoutUsd = contracts.toDouble(),
        estimatedAvgFill = px,
        sizingNote = "paper sell",
        kind = TicketKind.SELL,
        paperOnly = true
    )

    private fun log(ticker: String) = PredictionLogEntry(
        ticker = ticker,
        series = "KXBTC15M",
        predictedYes = 0.60,
        predictedNo = 0.40,
        marketMid = 0.42,
        timestampMs = nowMs,
        closeTimeMs = nowMs + 60_000L,
        outcome = "yes",
        predictedSide = "YES",
        entryAsk = 0.40,
        contracts = 1,
        stakeUsd = 0.40,
        feeUsd = KalshiFee.total(1, 0.40),
        settledAtMs = nowMs + 60_000L
    )
}
