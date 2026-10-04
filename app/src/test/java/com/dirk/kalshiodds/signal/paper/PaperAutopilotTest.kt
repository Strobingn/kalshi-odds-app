package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.flip.FlipCheck
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.ScorecardCopy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DEEP = 100_000

class PaperAutopilotTest {

    @Before
    fun resetAutopilot() {
        PaperAutopilot.resetSession()
    }

    private val nowMs = HomeFixtures.NOW_MS
    private val settings = SignalSettings(
        paperTradingEnabled = true,
        aiPaperAutopilotEnabled = true,
        paperKellyFraction = 0.5,
        ticketsEnabled = true
    )

    @Test
    fun defaultToggleIsOn() {
        assertTrue(SignalConstants.DEFAULT_AI_PAPER_AUTOPILOT)
        assertTrue(SignalSettings().aiPaperAutopilotEnabled)
    }

    @Test
    fun picksSideByEvAtAskNotFavorite() {
        val pYes = 0.35
        val yesAsk = 0.80
        val noAsk = 0.22
        val evYes = PaperAutopilot.evPerContract(pYes, yesAsk)!!
        val evNo = PaperAutopilot.evPerContract(1.0 - pYes, noAsk)!!
        assertTrue(evYes < 0.0)
        assertTrue(evNo > 0.0)
        val picked = PaperAutopilot.pickSide(pYes, yesAsk, noAsk)
        assertEquals("NO", picked!!.side)
        assertTrue(picked.evPerContract > evYes)
        val bothBad = PaperAutopilot.pickSide(0.50, 0.70, 0.70)
        assertNull(bothBad)
    }

    @Test
    fun multipleEntriesPerWindowWhenPriceMoves() {
        val clock = AtomicLong(nowMs)
        val ids = AtomicInteger(0)
        val book = PaperBook(
            idFactory = { "ap-${ids.incrementAndGet()}" },
            nowMs = { clock.get() }
        )
        val first = edgeMarket(yesAsk = 0.20, aiYes = 80.0)
        val a = enter(book, first, nowMs, depth = 4)
        assertNotNull(a)
        clock.addAndGet(5_000)
        val same = PaperAutopilot.consider(book, first, settings, nowMs + 5_000, yesDepth = 4, noDepth = 4)
        assertNull(same)
        assertEquals(1, book.snapshot().fills.size)
        clock.addAndGet(5_000)
        val moved = edgeMarket(yesAsk = 0.28, aiYes = 80.0)
        val b = PaperAutopilot.consider(book, moved, settings, nowMs + 10_000, yesDepth = 4, noDepth = 4)
        assertNotNull(b)
        assertEquals(2, book.snapshot().fills.size)
        assertEquals(2, book.snapshot().fills.count { !it.settled })
        assertTrue(book.snapshot().fills.all { it.pickSource == PaperPickSource.AUTOPILOT.label })
    }

    @Test
    fun kellySizesOnChangingBankroll() {
        val book = PaperBook(idFactory = { "k1" }, nowMs = { nowMs })
        val firstMkt = edgeMarket(ticker = "KXBTC15M-WIN1-50", yesAsk = 0.25, aiYes = 80.0)
        val first = enter(book, firstMkt, nowMs)!!
        val afterOpen = book.snapshot().paperBankrollUsd
        assertEquals(1_000.0, afterOpen, 1e-9)
        book.settle(first.ticker, "yes")
        val afterWin = book.snapshot().paperBankrollUsd
        assertTrue(afterWin > 1_000.0)
        val secondMkt = edgeMarket(ticker = "KXBTC15M-WIN2-50", yesAsk = 0.25, aiYes = 80.0)
        val second = enter(book, secondMkt, nowMs + 1)!!
        val sizedOnNew = PaperKellySizer.size(
            0.80,
            0.25,
            bankrollUsd = afterWin,
            kellyFraction = 0.5,
            depthContracts = DEEP
        )
        assertEquals(sizedOnNew.contracts, second.contracts)
        assertTrue(second.stakeUsd > first.stakeUsd - 1e-6 || second.contracts >= first.contracts)
        assertEquals(afterWin, book.snapshot().fills.first { it.id == second.id }.let {
            book.snapshot().paperBankrollUsd
        }, 1e-6)
    }

    @Test
    fun depthCapLimitsContracts() {
        val book = PaperBook()
        val m = edgeMarket(yesAsk = 0.25, aiYes = 80.0)
        val fill = enter(book, m, nowMs, depth = 2)
        assertNotNull(fill)
        assertEquals(2, fill!!.contracts)
        val uncapped = PaperKellySizer.size(0.80, 0.25, 1_000.0, depthContracts = DEEP)
        assertTrue(uncapped.contracts > 2)
    }

    @Test
    fun flipChanceBlocksLotteryPrint() {
        val book = PaperBook()
        val lottery = HomeFixtures.deadWindowLotteryBtc()
        val fill = PaperAutopilot.consider(
            book,
            lottery,
            settings,
            nowMs,
            yesAsk = lottery.yesAsk,
            noAsk = lottery.noAsk,
            yesDepth = DEEP,
            noDepth = DEEP
        )
        assertNull(fill)
        assertTrue(book.snapshot().fills.isEmpty())
        val downAsk = lottery.noAsk ?: 0.001
        val verdict = FlipCheck.evaluateMarket(lottery, nowMs)
        assertNotNull(verdict)
        assertFalse(FlipCheck.allowsRealisticMove(verdict!!, "NO", downAsk))
    }

    @Test
    fun toggleOffPlacesNoAiBets() {
        val book = PaperBook()
        val m = edgeMarket()
        val off = settings.copy(aiPaperAutopilotEnabled = false)
        assertNull(PaperAutopilot.consider(book, m, off, nowMs, yesDepth = DEEP, noDepth = DEEP))
        assertTrue(book.snapshot().fills.isEmpty())
        val paperOff = settings.copy(paperTradingEnabled = false)
        assertNull(PaperAutopilot.consider(book, m, paperOff, nowMs, yesDepth = DEEP, noDepth = DEEP))
        assertTrue(book.snapshot().fills.isEmpty())
    }

    @Test
    fun neverCallsLiveOrderPath() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("live order must not run")
            }
        )
        val book = PaperBook()
        val m = edgeMarket()
        val fill = enter(book, m, nowMs)
        assertNotNull(fill)
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        assertTrue(session.snapshot().phase is TicketPhase.Idle)
        val src = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/paper/PaperAutopilot.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/signal/paper/PaperAutopilot.kt")
        ).first { it.isFile }.readText()
        assertFalse(src.contains("placeOrder"))
        assertFalse(src.contains("KalshiTradeClient"))
        assertFalse(src.contains("TicketSession"))
        assertTrue(src.contains("Never calls Kalshi"))
    }

    @Test
    fun favoriteLockDoesNotOverrideNegativeEvYes() {
        val m = edgeMarket(
            yesAsk = 0.80,
            aiYes = 35.0,
            predicted = "YES",
            spotUsd = 66_700.0,
            floorStrike = 67_000.0
        )
        assertEquals("YES", m.primaryHeroSide)
        val picked = PaperAutopilot.pickSide(0.35, 0.80, 0.22)
        assertEquals("NO", picked!!.side)
        val book = PaperBook()
        val fill = enter(book, m, nowMs, yesAsk = 0.80, noAsk = 0.22)
        assertNotNull(fill)
        assertEquals("NO", fill!!.side)
    }

    @Test
    fun scorecardListsEveryAiPaperBetSeparateFromManual() {
        val book = PaperBook(idFactory = { "sc1" }, nowMs = { nowMs })
        val fill = enter(book, edgeMarket(), nowMs)!!
        book.settle(fill.ticker, "yes")
        val manual = TradeTicket(
            id = "man",
            ticker = "KXBTC15M-MAN-50",
            side = "YES",
            bookSide = "bid",
            stakeUsd = 10.0,
            limitPrice = 0.40,
            yesLimitPrice = 0.40,
            contracts = 25,
            estimatedFillUsd = 10.0,
            maxPayoutUsd = 25.0,
            estimatedAvgFill = 0.40,
            sizingNote = "tile",
            kind = TicketKind.MANUAL
        )
        book.manualFill(manual)
        val view = ScorecardCopy.of(emptyList(), book.snapshot())
        assertTrue(view.autopilot.bets.isNotEmpty())
        assertTrue(view.autopilot.bets.all { it.ticker == fill.ticker })
        assertTrue(view.autopilot.bets.none { it.ticker.contains("MAN") })
        val line = view.autopilot.bets.single().line
        assertTrue(line.contains("AI"))
        assertTrue(line.contains("EV"))
        assertTrue(line.contains("stake"))
        assertTrue(line.contains("fee"))
        assertTrue(view.allLines().contains(ScorecardCopy.AUTOPILOT_TITLE))
        assertTrue(view.allLines().contains(ScorecardCopy.MANUAL_TITLE) || view.showsEmptyState)
        assertTrue(view.allLines().contains(ScorecardCopy.AI_TITLE) || view.showsEmptyState)
    }

    @Test
    fun staleRestAskDoesNotCreateBetWhenLiveBookHasNoSellers() {
        val book = PaperBook()
        val m = edgeMarket(yesAsk = 0.20, aiYes = 80.0)
        val emptyYesSellers = com.dirk.kalshiodds.signal.engine.BookLevelSnapshot(
            yes = listOf(0.18 to 40.0),
            no = emptyList()
        )
        val fill = PaperAutopilot.consider(
            book,
            m,
            settings,
            nowMs,
            yesAsk = 0.20,
            noAsk = 0.82,
            yesDepth = DEEP,
            noDepth = DEEP,
            book = emptyYesSellers
        )
        assertNull(fill)
        assertTrue(book.snapshot().fills.isEmpty())
        val liveSellers = com.dirk.kalshiodds.signal.engine.BookLevelSnapshot(
            yes = listOf(0.18 to 40.0),
            no = listOf(0.80 to 40.0)
        )
        PaperAutopilot.consider(
            book,
            m,
            settings,
            nowMs,
            yesAsk = 0.20,
            noAsk = 0.82,
            yesDepth = DEEP,
            noDepth = DEEP,
            book = liveSellers
        )
        val ok = PaperAutopilot.consider(
            book,
            m,
            settings,
            nowMs,
            yesAsk = 0.20,
            noAsk = 0.82,
            yesDepth = DEEP,
            noDepth = DEEP,
            book = liveSellers
        )
        assertNotNull(ok)
        assertEquals(0.20, ok!!.limitPrice, 1e-9)
    }

    @Test
    fun relatedCryptoAndFallbackMlpWeightsStayZero() {
        assertEquals(0.0, com.dirk.kalshiodds.signal.engine.ScoringEngine.W_RELATED, 0.0)
        assertEquals(0.0, com.dirk.kalshiodds.signal.engine.ScoringEngine.W_RELATED_LATE, 0.0)
        assertEquals(0.0, com.dirk.kalshiodds.signal.engine.ScoringEngine.W_AI, 0.0)
        assertEquals(0.0, com.dirk.kalshiodds.signal.engine.ScoringEngine.W_AI_LATE, 0.0)
        val engine = com.dirk.kalshiodds.signal.engine.ScoringEngine()
        val w = engine.blendWeights(
            tte = com.dirk.kalshiodds.signal.engine.TteRegime.EARLY,
            regime = com.dirk.kalshiodds.signal.engine.RegimeTag.QUIET,
            hasAi = true,
            hasRelated = true,
            hasVel = false,
            hasImb = false,
            hasLeadLag = false,
            hasDepth = false,
            hasCancel = false,
            hasSpot = false
        )!!
        assertEquals(0.0, w.related, 1e-12)
        assertEquals(0.0, w.ai, 1e-12)
    }

    private fun enter(
        book: PaperBook,
        market: MarketUiModel,
        atMs: Long,
        depth: Int = DEEP,
        yesAsk: Double? = market.yesAsk,
        noAsk: Double? = market.noAsk,
        bookSnap: com.dirk.kalshiodds.signal.engine.BookLevelSnapshot? = null
    ): PaperFill? {
        PaperAutopilot.consider(
            book, market, settings, atMs,
            yesAsk = yesAsk, noAsk = noAsk, yesDepth = depth, noDepth = depth, book = bookSnap
        )
        return PaperAutopilot.consider(
            book, market, settings, atMs,
            yesAsk = yesAsk, noAsk = noAsk, yesDepth = depth, noDepth = depth, book = bookSnap
        )
    }

    private fun edgeMarket(
        ticker: String = "KXBTC15M-25SEP181700-50",
        yesAsk: Double = 0.20,
        aiYes: Double = 80.0,
        predicted: String = "YES",
        spotUsd: Double = 67_240.0,
        floorStrike: Double = 67_000.0
    ): MarketUiModel = HomeFixtures.market(
        ticker = ticker,
        seriesLabel = "Bitcoin",
        yesAsk = yesAsk,
        aiYes = aiYes,
        predicted = predicted,
        closeMs = nowMs + 372_000L,
        floorStrike = floorStrike,
        spotUsd = spotUsd,
        spotDelta = spotUsd - floorStrike
    )
}
