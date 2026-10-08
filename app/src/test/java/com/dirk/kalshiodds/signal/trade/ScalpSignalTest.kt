package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScalpSignalTest {
    private val openedAt = 1_000_000L

    private fun market(yes: Double, r1: Double, r5: Double) = MarketUiModel(
        ticker = "KXBTC15M-TEST", title = "BTC", subtitle = null, floorStrike = 100_000.0,
        yesBid = 0.60, yesAsk = 0.61, noBid = 0.39, noAsk = 0.40, lastPrice = 0.60,
        yesProbabilityPercent = yes * 100.0, noProbabilityPercent = (1.0 - yes) * 100.0,
        volume = 100.0, volume24h = 100.0, closeTimeLocal = null,
        closeTimeEpochMs = openedAt + 900_000L, openTimeEpochMs = openedAt,
        status = "active", seriesLabel = "Bitcoin", spotReturn1m = r1, spotReturn5m = r5
    )

    @Test fun favoriteAndSpotConfirmationCreateEarlyYesScalp() {
        assertEquals("YES", ScalpSignal.candidate(market(0.62, 0.0002, 0.0008), openedAt + 6 * 60_000L)!!.side)
    }

    @Test fun disagreementOrWrongMinuteProducesNoScalp() {
        assertNull(ScalpSignal.candidate(market(0.62, -0.0002, 0.0008), openedAt + 6 * 60_000L))
        assertNull(ScalpSignal.candidate(market(0.62, 0.0002, 0.0008), openedAt + 8 * 60_000L))
    }

    @Test fun ticketIsForcedToPaperEvenWhenItQualifies() {
        val now = openedAt + 6 * 60_000L
        val ticket = TicketBuilder.proposeScalp(
            market(0.62, 0.0002, 0.0008).copy(aiYesPercent = 95.0, aiNoPercent = 5.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        )!!
        assertEquals(TicketKind.SCALP, ticket.kind)
        org.junit.Assert.assertTrue(ticket.paperOnly)
        org.junit.Assert.assertTrue(ticket.canPaper)
    }
}
