package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.ApproveRouter
import com.dirk.kalshiodds.signal.trade.LiveOrderGates
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketSession
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

class D3PaperAndLiveGatesTest {

    private val et = ZoneId.of("America/New_York")
    private val close = ZonedDateTime.of(2026, 9, 30, 17, 0, 0, 0, et).toInstant().toEpochMilli()
    private val active = ZonedDateTime.of(2026, 9, 30, 15, 0, 0, 0, et).toInstant().toEpochMilli()

    @Test
    fun paperPathNeverCallsLiveOrderEndpoint() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("D3 paper must never hit POST /portfolio/events/orders")
            }
        )
        val store = D3Store()
        val engine = D3Engine(nowMs = { active })
        val quote = sampleQuote()
        val signal = D3Strategy.evaluate(
            D3Strategy.Inputs(quote = quote, nowMs = active, bankrollUsd = 1_000.0)
        )!!
        val fired = engine.tickPaper(
            quotes = listOf(quote),
            store = store,
            tradesByTicker = emptyMap(),
            paperAutopilot = true,
            bankrollUsd = 1_000.0,
            now = active
        )
        assertTrue(fired.isNotEmpty() || store.snapshot().picks.isNotEmpty())
        val through = listOf(D3TradePrint(quote.ticker, signal.bidPrice - 0.01, 2.0, active + 1_000L))
        engine.tickPaper(
            quotes = listOf(quote),
            store = store,
            tradesByTicker = mapOf(quote.ticker.uppercase() to through),
            paperAutopilot = true,
            bankrollUsd = 1_000.0,
            now = active + 2_000L
        )
        assertTrue(store.snapshot().picks.any { it.filled })
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        val paper = PaperBook()
        paper.considerTicket(
            TicketBuilder.proposeD3(signal, TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false))!!,
            enabled = true,
            depthContracts = 50
        )
        assertTrue(paper.snapshot().fills.isEmpty())
        assertEquals(0, placed.get())
        assertEquals("/trade-api/v2/portfolio/events/orders", KalshiTradeClient.V2_CREATE_PATH)
    }

    @Test
    fun liveTicketIsPostOnlyTenDollarCapAndNeedsBothConfirms() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, id ->
                placed.incrementAndGet()
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = id,
                        orderId = "d3-live",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val signal = D3Strategy.evaluate(
            D3Strategy.Inputs(quote = sampleQuote(), nowMs = active, bankrollUsd = 50_000.0)
        )!!
        val ticket = TicketBuilder.proposeD3(
            signal,
            TicketBuilder.Context(settings = SignalSettings(ticketsEnabled = true), alertsPaused = false)
        )!!
        assertEquals(TicketKind.D3, ticket.kind)
        assertTrue(ticket.postOnly)
        assertTrue((ticket.allInUsd ?: ticket.stakeUsd) <= 10.0 + 1e-6)
        val enforced = LiveOrderSizer.enforce(ticket, feeRate = 0.0)
        assertTrue(enforced.ok)
        assertTrue(enforced.allInUsd <= 10.0 + 1e-9)
        assertEquals(10.0, LiveOrderGates.LIVE_ALL_IN, 1e-9)
        session.replaceProposals(listOf(ticket))
        assertEquals(0, placed.get())
        session.openApprove(ticket.id)
        assertEquals(0, placed.get())
        val live = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = ticket.canApprove,
            intent = ApproveRouter.Intent.Live
        )
        assertEquals(ApproveRouter.Decision.Live, live)
        session.approve(ticket.id)
        assertEquals(1, placed.get())
        assertTrue(confirmSheetRequiresApproveAndRealMoney())
        assertTrue(ticket.gateNote!!.contains("REAL MONEY"))
        assertTrue(ticket.gateNote!!.contains("Approve"))
        assertTrue(D3Copy.CONFIRM_POST_ONLY.contains("REAL MONEY"))
        assertTrue(D3Copy.CONFIRM_POST_ONLY.contains("Approve"))
    }

    @Test
    fun onePositionPerStrikePerDayAndCancelLeavesBand() {
        val store = D3Store()
        val engine = D3Engine(nowMs = { active })
        val q = sampleQuote()
        engine.tickPaper(listOf(q), store, emptyMap(), paperAutopilot = true, bankrollUsd = 1_000.0, now = active)
        assertEquals(1, store.snapshot().picks.size)
        engine.tickPaper(listOf(q), store, emptyMap(), paperAutopilot = true, bankrollUsd = 1_000.0, now = active + 10)
        assertEquals(1, store.snapshot().picks.size)
        val left = q.copy(yesAsk = 0.80, yesBid = 0.78, noAsk = 0.22, noBid = 0.20)
        engine.tickPaper(listOf(left), store, emptyMap(), paperAutopilot = true, bankrollUsd = 1_000.0, now = active + 20)
        val pick = store.snapshot().picks.single()
        assertFalse(pick.filled)
        assertNotNull(pick.cancelledAtMs)
        assertTrue(pick.cancelReason!!.contains("band"))
    }

    private fun confirmSheetRequiresApproveAndRealMoney(): Boolean {
        val src = java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
            .takeIf { it.isFile }
            ?: java.io.File("src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
        val text = src.readText()
        return text.contains("REAL MONEY") &&
            text.contains("confirmApproveLabel") &&
            text.contains("TicketKind.D3") &&
            text.contains("D3Copy.CONFIRM_POST_ONLY")
    }

    private fun sampleQuote() = D3Quote(
        ticker = "KXBTCD-26SEP3017-T90000",
        eventTicker = "KXBTCD-26SEP3017",
        title = "Bitcoin price",
        subtitle = "$90,000 or above",
        strikeUsd = 90_000.0,
        yesBid = 0.88,
        yesAsk = 0.91,
        noBid = 0.09,
        noAsk = 0.12,
        yesBidSize = 20.0,
        yesAskSize = 40.0,
        closeTimeEpochMs = close,
        status = "active"
    )
}
