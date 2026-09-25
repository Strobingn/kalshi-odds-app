package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Paper fill on ticker X must never intercept Live Approve on X.
 * Old Auto + paper-on routed Live Approve into PaperBook.explicitFill
 * ("Already have an open paper fill…").
 */
class LiveApprovePaperIsolationTest {

    @Test
    fun liveIntentIgnoresPaperToggleAndOpenPaperFill() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true,
            intent = ApproveRouter.Intent.Live
        )
        assertEquals(ApproveRouter.Decision.Live, d)
    }

    @Test
    fun paperFillOnTickerDoesNotBlockLiveApproveHttp() = runBlocking {
        val book = PaperBook()
        book.reset()
        val ticker = "KXETH15M-26SEP251230-30"
        val market = sample(ticker, yesAsk = 0.25, noAsk = 0.75, aiYes = 80.0)
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val paperFill = book.explicitFill(
            ticker = ticker,
            side = "NO",
            limitPrice = 0.15,
            wantContracts = 15,
            source = "paper",
            note = "open paper fill"
        )
        assertTrue(paperFill.ok)

        val http = AtomicInteger(0)
        val session = TicketSession(
            placeOrder = { ticket, clientOrderId ->
                http.incrementAndGet()
                Result.success(
                    PlacedOrder(
                        ticket = ticket,
                        clientOrderId = clientOrderId,
                        orderId = "live-1",
                        fillCount = 0.0,
                        remainingCount = ticket.contracts.toDouble(),
                        averageFillPrice = ticket.limitPrice,
                        placedAtMs = 1L
                    )
                )
            }
        )
        val liveTicket = TicketBuilder.proposeManual(market, "YES", ctx)!!
        session.addManual(liveTicket)
        val liveDecision = ApproveRouter.decide(
            paperTradingEnabled = true,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = liveTicket.canApprove,
            intent = ApproveRouter.Intent.Live
        )
        assertEquals(ApproveRouter.Decision.Live, liveDecision)
        session.approve(liveTicket.id)
        assertEquals("paper fill must not swallow Live Approve HTTP", 1, http.get())
    }

    @Test
    fun keyIdWithoutPemBlocksLiveWithPemMessage() {
        val d = ApproveRouter.decide(
            paperTradingEnabled = false,
            paperOnly = false,
            isSell = false,
            liveCredentialsConfigured = true,
            canApprove = true,
            intent = ApproveRouter.Intent.Live,
            keyIdWithoutPem = true
        )
        val blocked = d as ApproveRouter.Decision.Blocked
        assertTrue(blocked.reason.contains("PEM"))
    }

    private fun sample(ticker: String, yesAsk: Double, noAsk: Double, aiYes: Double) = MarketUiModel(
        ticker = ticker,
        title = ticker,
        subtitle = null,
        floorStrike = 4000.0,
        yesBid = yesAsk - 0.01,
        yesAsk = yesAsk,
        noBid = noAsk - 0.01,
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        aiYesPercent = aiYes,
        aiNoPercent = 100.0 - aiYes,
        volume = 1000.0,
        volume24h = 1000.0,
        openInterest = 100.0,
        liquidityDollars = 5000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
        status = "active",
        seriesLabel = "Ethereum",
        passedFilter = true,
        predictedSide = "YES"
    )
}
