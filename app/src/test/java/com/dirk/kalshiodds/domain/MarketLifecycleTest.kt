package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketPhase
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class KalshiPriceTest {

    @Test
    fun dollarsStringIncludingDeciCent() {
        assertEquals(0.046, KalshiPrice.parseDollars("0.0460")!!, 1e-9)
        assertEquals(0.006, KalshiPrice.parseDollars("0.0060")!!, 1e-9)
        assertEquals(0.45, KalshiPrice.parseDollars("0.4500")!!, 1e-9)
    }

    @Test
    fun zeroAndUnitAreNotQuotes() {
        assertNull(KalshiPrice.parseDollars("0.0000"))
        assertNull(KalshiPrice.parseDollars("0"))
        assertNull(KalshiPrice.parseDollars("1.0000"))
        assertNull(KalshiPrice.parseDollars(""))
        assertNull(KalshiPrice.parseDollars(null))
        assertNull(KalshiPrice.usable(0.0))
        assertNull(KalshiPrice.usable(1.0))
    }

    @Test
    fun integerCentsStillParse() {
        assertEquals(0.45, KalshiPrice.parseDollars("45")!!, 1e-9)
        assertEquals(0.02, KalshiPrice.parseDollars("2")!!, 1e-9)
        assertEquals(0.01, KalshiPrice.parseDollars("1")!!, 1e-9)
        assertNull(KalshiPrice.parseDollars("1.0000"))
        assertNull(KalshiPrice.parseDollars("100"))
    }

    @Test
    fun centsWrittenWithDecimalAreNotDollars() {
        // Device report: 10¢+ vanished; 1.5¢ / 1.8¢ fed the wrong units.
        assertEquals(0.015, KalshiPrice.parseDollars("1.5")!!, 1e-12)
        assertEquals(0.018, KalshiPrice.parseDollars("1.80")!!, 1e-12)
        assertEquals(0.099, KalshiPrice.parseDollars("9.9")!!, 1e-12)
        assertEquals(0.10, KalshiPrice.parseDollars("10.00")!!, 1e-12)
        assertEquals(0.25, KalshiPrice.parseDollars("25.00")!!, 1e-12)
        assertEquals(0.105, KalshiPrice.parseDollars("10.5")!!, 1e-12)
        assertEquals(0.905, KalshiPrice.parseDollars("90.5")!!, 1e-12)
        assertNull(KalshiPrice.parseDollars("1.0000"))
    }

    @Test
    fun deciCentIntegersAbove99DoNotVanish() {
        assertEquals(0.15, KalshiPrice.parseDollars("150")!!, 1e-12)
        assertEquals(0.250, KalshiPrice.parseDollars("250")!!, 1e-12)
        assertEquals(0.440, KalshiPrice.parseDollars("440")!!, 1e-12)
        assertNull(KalshiPrice.parseDollars("1000"))
    }

    @Test
    fun fractionalCentsStayExact() {
        assertEquals(0.001, KalshiPrice.parseDollars("0.0010")!!, 1e-12)
        assertEquals(0.015, KalshiPrice.parseDollars("0.0150")!!, 1e-12)
        assertEquals(0.018, KalshiPrice.parseDollars("0.0180")!!, 1e-12)
        assertEquals(0.099, KalshiPrice.parseDollars("0.0990")!!, 1e-12)
        assertEquals(0.105, KalshiPrice.parseDollars("0.1050")!!, 1e-12)
        assertEquals(0.905, KalshiPrice.parseDollars("0.9050")!!, 1e-12)
        assertNotNull(KalshiPrice.usable(0.001))
        assertNotNull(KalshiPrice.usable(0.015))
        assertEquals("0.0010", KalshiPrice.toWireDollars(0.001))
        assertEquals("0.0150", KalshiPrice.toWireDollars(0.015))
    }

    @Test
    fun impliedAskFromOppositeBid() {
        assertEquals(0.046, KalshiPrice.impliedAskFromOppositeBid(0.954)!!, 1e-9)
        assertNull(KalshiPrice.impliedAskFromOppositeBid(1.0))
        assertNull(KalshiPrice.impliedAskFromOppositeBid(0.0))
    }
}

class MarketAskMappingTest {

    @Test
    fun restDollarsMapToUiAsks() {
        val ui = MarketDto(
            ticker = "KXBTC15M-26SEP241700-00",
            title = "BTC",
            yesBidDollars = "0.0450",
            yesAskDollars = "0.0460",
            noBidDollars = "0.9540",
            noAskDollars = "0.9550",
            yesAskSizeFp = "37.26",
            status = "active",
            closeTime = "2026-09-24T21:00:00Z"
        ).toUiModel(SeriesKind.BTC)
        assertEquals(0.046, ui.yesAsk!!, 1e-9)
        assertEquals(0.955, ui.noAsk!!, 1e-9)
        assertEquals(37.26, ui.yesAskSize!!, 1e-9)
        assertEquals("active", ui.status)
        assertNotNull(ui.closeTimeEpochMs)
    }

    @Test
    fun emptyDollarsAreNullAsks() {
        val ui = MarketDto(
            ticker = "KXBTC15M-26SEP241645-45",
            yesAskDollars = "0.0000",
            yesBidDollars = "0.0000",
            noAskDollars = "1.0000",
            noBidDollars = "1.0000",
            status = "closed",
            closeTime = "2026-09-24T20:45:00Z"
        ).toUiModel(SeriesKind.BTC)
        assertNull(ui.yesAsk)
        assertNull(ui.yesBid)
        assertNull(ui.noAsk)
        assertNull(ui.noBid)
        assertNull(TicketBuilder.bestAsk(ui, "YES"))
        assertNull(TicketBuilder.bestAsk(ui, "NO"))
    }

    @Test
    fun yesAskFallsBackToOneMinusNoBid() {
        val ui = MarketDto(
            ticker = "KXBTC15M-OPEN",
            yesAskDollars = "0.0000",
            noBidDollars = "0.9800",
            status = "active"
        ).toUiModel(SeriesKind.BTC)
        assertNull(ui.yesAsk)
        assertEquals(0.02, TicketBuilder.bestAsk(ui, "YES")!!, 1e-9)
    }

    @Test
    fun bookSnapshotSuppliesYesAskWhenRestEmpty() {
        val ui = MarketDto(
            ticker = "KXBTC15M-BOOK",
            yesAskDollars = null,
            noBidDollars = null,
            status = "active"
        ).toUiModel(SeriesKind.BTC)
        val book = BookLevelSnapshot(yes = emptyList(), no = listOf(0.97 to 40.0))
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            books = mapOf(ui.ticker to book),
            nowMs = 1_000L
        )
        assertEquals(0.03, TicketBuilder.bestAsk(ui, "YES", ctx)!!, 1e-9)
    }
}

class ExpiredMarketPruneTest {

    private val now = 1_800_000L

    @Test
    fun closedByStatusAndCloseTime() {
        val closed = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-45",
            status = "closed",
            closeMs = now - 1_000L,
            ask = 0.03
        )
        val expiredActive = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-00",
            status = "active",
            closeMs = now - 1L,
            ask = 0.04
        )
        val live = sampleMarket(
            ticker = "KXBTC15M-26SEP241700-00",
            status = "active",
            closeMs = now + 600_000L,
            ask = 0.03
        )
        assertTrue(MarketLifecycle.isClosed(closed, now))
        assertTrue(MarketLifecycle.isClosed(expiredActive, now))
        assertTrue(MarketLifecycle.isTradable(live, now))
        assertFalse(MarketLifecycle.isTradable(closed, now))
    }

    @Test
    fun hunterAndConfiguredSkipExpired() {
        val expired = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-45",
            status = "closed",
            closeMs = now - 5_000L,
            ask = 0.03,
            volume = 5_000.0
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true, ticketRespectGates = false),
            alertsPaused = false,
            idFactory = { "x" },
            nowMs = now
        )
        assertNull(TicketBuilder.proposeHunter(expired, ctx))
        assertNull(TicketBuilder.propose(expired, ctx))
        assertTrue(TicketBuilder.proposeAll(listOf(expired), ctx).isEmpty())
    }

    @Test
    fun rollingWindowMovesToLiveSuccessor() {
        val expired = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-45",
            status = "closed",
            closeMs = now - 1_000L,
            ask = null
        )
        val live = sampleMarket(
            ticker = "KXBTC15M-26SEP241700-45",
            status = "active",
            closeMs = now + 600_000L,
            ask = 0.03,
            volume = 5_000.0
        )
        val next = MarketLifecycle.liveSuccessor(expired.ticker, listOf(expired, live), now)
        assertEquals("KXBTC15M-26SEP241700-45", next!!.ticker)
        assertEquals(live.ticker, MarketLifecycle.resolveLive(expired, listOf(expired, live), now).ticker)
    }

    @Test
    fun featuredLiveSkipsExpiredAtm() {
        val deadAtm = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-45",
            status = "closed",
            closeMs = now - 1L,
            ask = 0.50,
            yesPct = 50.0
        )
        val liveFar = sampleMarket(
            ticker = "KXBTC15M-26SEP241700-00",
            status = "active",
            closeMs = now + 600_000L,
            ask = 0.12,
            yesPct = 12.0
        )
        assertEquals(
            "KXBTC15M-26SEP241700-00",
            MarketLifecycle.featuredLive(listOf(deadAtm, liveFar), now)!!.ticker
        )
    }

    @Test
    fun sessionDropsExpiredManualAndKeepsLiveHunter() {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, clientId ->
                placed.incrementAndGet()
                Result.success(
                    com.dirk.kalshiodds.signal.trade.PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientId,
                        orderId = "ord",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = null,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val expiredManual = sampleTicket("m1", "KXBTC15M-26SEP241645-45").copy(kind = TicketKind.MANUAL)
        session.addManual(expiredManual)
        val liveHunter = sampleTicket("h1", "KXBTC15M-26SEP241700-00").copy(kind = TicketKind.HUNTER)
        session.replaceProposals(listOf(liveHunter), liveTickers = setOf("KXBTC15M-26SEP241700-00"))
        assertTrue(session.snapshot().proposals.none { it.ticker.contains("1645") })
        assertTrue(session.snapshot().proposals.any { it.ticker.endsWith("1700-00") })
        assertEquals(0, placed.get())
    }

    @Test
    fun blockedClosedTicketDoesNotPlace() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, clientId ->
                placed.incrementAndGet()
                Result.success(
                    com.dirk.kalshiodds.signal.trade.PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientId,
                        orderId = "ord",
                        fillCount = 0.0,
                        remainingCount = 0.0,
                        averageFillPrice = null,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val blocked = sampleTicket("b1", "KXBTC15M-26SEP241645-45").copy(
            kind = TicketKind.MANUAL,
            blockedReason = TicketBuilder.MARKET_CLOSED,
            contracts = 0
        )
        session.addManual(blocked)
        session.approve("b1")
        assertEquals(0, placed.get())
        assertTrue(session.snapshot().phase is TicketPhase.AwaitingApprove)
    }

    @Test
    fun manualOnClosedShowsMarketClosed() {
        val expired = sampleMarket(
            ticker = "KXBTC15M-26SEP241645-45",
            status = "closed",
            closeMs = now - 1L,
            ask = null
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "closed" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeManual(expired, "NO", ctx)!!
        assertEquals(TicketBuilder.MARKET_CLOSED, ticket.blockedReason)
        assertFalse(ticket.canApprove)
        assertEquals(0, ticket.contracts)
    }

    @Test
    fun manualOnOpenWithNoAskShowsSellersMessage() {
        val open = sampleMarket(
            ticker = "KXBTC15M-26SEP241700-00",
            status = "active",
            closeMs = now + 600_000L,
            ask = null
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketsEnabled = true),
            alertsPaused = false,
            idFactory = { "empty" },
            nowMs = now
        )
        val ticket = TicketBuilder.proposeManual(open, "YES", ctx)!!
        assertEquals(TicketBuilder.noSellers("YES"), ticket.blockedReason)
        assertFalse(ticket.canApprove)
    }

    @Test
    fun stalePageErrorDetected() {
        assertTrue(TicketSession.stalePageError("No ask to size a limit on KXBTC15M-26SEP241645-45"))
        assertFalse(TicketSession.stalePageError("Unauthorized — check Key ID + PEM in Settings"))
    }
}

private fun sampleMarket(
    ticker: String,
    status: String,
    closeMs: Long?,
    ask: Double?,
    volume: Double = 2_000.0,
    yesPct: Double? = ask?.times(100.0)
) = MarketUiModel(
    ticker = ticker,
    title = "BTC",
    subtitle = null,
    floorStrike = null,
    yesBid = ask?.let { (it - 0.01).coerceAtLeast(0.001) },
    yesAsk = ask,
    noBid = ask?.let { 1.0 - it },
    noAsk = ask?.let { 1.0 - (it - 0.01) },
    lastPrice = ask,
    yesProbabilityPercent = yesPct,
    noProbabilityPercent = yesPct?.let { 100.0 - it },
    volume = volume,
    volume24h = volume,
    openInterest = volume,
    liquidityDollars = volume,
    closeTimeLocal = null,
    closeTimeEpochMs = closeMs,
    status = status,
    seriesLabel = "Bitcoin",
    passedFilter = true,
    muted = false,
    predictedSide = "NO",
    edgePp = -8.0,
    netEdgePp = -6.0
)

private fun sampleTicket(id: String, ticker: String) = TradeTicket(
    id = id,
    ticker = ticker,
    side = "NO",
    bookSide = "ask",
    stakeUsd = 1.0,
    limitPrice = 0.03,
    yesLimitPrice = 0.97,
    contracts = 25,
    estimatedFillUsd = 1.0,
    maxPayoutUsd = 25.0,
    estimatedAvgFill = 0.03,
    sizingNote = "test"
)
