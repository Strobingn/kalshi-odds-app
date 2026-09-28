package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperKellySizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Paper Kelly sizing must not raise the live $10 cap, skip the Approve tap,
 * or auto-place a real order.
 */
class LiveOrderUnaffectedByPaperKellyTest {

    @Test
    fun liveClipStaysTenDollarsEvenWhenPaperKellyIsHuge() {
        val ask = 0.25
        val paper = PaperKellySizer.size(0.80, ask, bankrollUsd = 50_000.0, kellyFraction = 1.0)
        assertTrue(paper.ok)
        assertTrue(paper.allInUsd > SignalConstants.LIVE_ALL_IN_CAP_USD)
        val live = LiveOrderSizer.size(ask)
        assertTrue(live.ok)
        assertTrue(live.allInUsd <= SignalConstants.LIVE_ALL_IN_CAP_USD + 1e-9)
        assertTrue(live.count < paper.contracts)
        val settings = SignalSettings(
            paperTradingEnabled = true,
            kellyFraction = 1.0,
            paperBankrollStartUsd = 50_000.0,
            ticketStakeUsd = 10.0
        )
        val ticket = TicketBuilder.proposeManual(
            sample("KXBTC15M-LIVE", ask),
            "YES",
            TicketBuilder.Context(settings = settings, alertsPaused = false, bankrollUsd = 50_000.0)
        )!!
        assertTrue(ticket.canApprove)
        assertTrue((ticket.allInUsd ?: ticket.stakeUsd) <= 10.0 + 1e-6)
        val enforced = LiveOrderSizer.enforce(ticket)
        assertTrue(enforced.ok)
        assertTrue(enforced.allInUsd <= 10.0 + 1e-9)
    }

    @Test
    fun confirmPathStillRequiresApproveAndNeverAutoPlaces() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, id ->
                placed.incrementAndGet()
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = id,
                        orderId = "live-1",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val ticket = TicketBuilder.proposeManual(
            sample("KXBTC15M-CONF", 0.30),
            "YES",
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )!!
        session.replaceProposals(listOf(ticket))
        session.onStart()
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
        assertTrue(HomeCopyConfirmContainsRealMoney())
        assertEquals(10.0, LiveOrderGates.LIVE_ALL_IN, 1e-9)
        assertEquals("/trade-api/v2/portfolio/events/orders", KalshiTradeClient.V2_CREATE_PATH)
    }

    @Test
    fun paperKellyFillDoesNotCallPlaceOrder() = runBlocking {
        val placed = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { _, _ ->
                placed.incrementAndGet()
                error("live order must not run")
            }
        )
        val book = PaperBook()
        val ticket = TradeTicket(
            id = "t-paper",
            ticker = "KXBTC15M-PAPER",
            side = "YES",
            bookSide = "bid",
            stakeUsd = 1.0,
            limitPrice = 0.04,
            yesLimitPrice = 0.04,
            contracts = 25,
            estimatedFillUsd = 1.0,
            maxPayoutUsd = 25.0,
            estimatedAvgFill = 0.04,
            sizingNote = "hunter",
            kind = TicketKind.HUNTER,
            modelChance = 0.70,
            impliedChance = 0.04
        )
        book.considerTicket(ticket, enabled = true)
        assertTrue(book.snapshot().fills.isNotEmpty() || book.snapshot().lastMessage != null)
        assertEquals(0, placed.get())
        assertEquals(0, session.placementCount)
        session.replaceProposals(listOf(ticket))
        assertEquals(0, placed.get())
    }

    private fun HomeCopyConfirmContainsRealMoney(): Boolean {
        val src = java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
            .takeIf { it.isFile }
            ?: java.io.File("src/main/java/com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
        val text = src.readText()
        return text.contains("REAL MONEY") && text.contains("confirmApproveLabel")
    }

    private fun sample(ticker: String, yesAsk: Double, aiYes: Double = 80.0) = MarketUiModel(
        ticker = ticker,
        title = ticker,
        subtitle = null,
        floorStrike = 90_000.0,
        yesBid = yesAsk - 0.01,
        yesAsk = yesAsk,
        noBid = 0.99 - yesAsk,
        noAsk = 1.0 - yesAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = (1.0 - yesAsk) * 100.0,
        aiYesPercent = aiYes,
        aiNoPercent = 100.0 - aiYes,
        volume = 1_000.0,
        volume24h = 1_000.0,
        openInterest = 100.0,
        liquidityDollars = 5_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = "YES"
    )
}
