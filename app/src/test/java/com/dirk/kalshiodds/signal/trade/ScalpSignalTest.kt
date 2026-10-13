package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
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

    @Test fun selectorUsesAiFairValueWithoutAnEntryGate() {
        assertEquals("YES", ScalpSignal.candidate(market(0.01, -0.9, -0.9).copy(aiYesPercent = 51.0), openedAt).side)
        assertEquals("NO", ScalpSignal.candidate(market(0.99, 0.9, 0.9).copy(aiYesPercent = 49.0), openedAt).side)
    }

    @Test fun settlementAwareDirectionWinsOverRawAiFallback() {
        val candidate = ScalpSignal.candidate(
            market(0.90, 0.02, 0.04).copy(aiYesPercent = 95.0, predictedSide = "NO"),
            openedAt + 4 * 60_000L
        )
        assertEquals("NO", candidate.side)
        assertEquals("settlement-aware engine", candidate.selectedFrom)
        assertEquals(660L, candidate.timeToCloseSec)
    }

    @Test fun twentyIndependentTracksProduceConcurrentSidesWithoutEntryGates() {
        val candidates = ScalpSignal.candidates(
            market(0.50, 0.004, 0.020).copy(predictedSide = "YES"),
            openedAt + 60_000L
        )
        assertEquals(20, candidates.size)
        assertEquals(ScalpSignal.Track.values().map { it.formulaVersion }, candidates.map { it.formulaVersion })
        assertEquals(listOf("YES", "YES", "NO", "NO", "YES", "YES", "NO", "YES"), candidates.take(8).map { it.side })
    }

    @Test fun modelAndMarketChannelsUseTheirOwnAvailableInputs() {
        val candidates = ScalpSignal.candidates(
            market(0.50, 0.004, 0.020).copy(
                aiYesPercent = 70.0,
                digitalFairPp = 30.0,
                importedModelPp = 51.0,
                mlpPp = 49.0,
                cnnPp = 51.0,
                gbmPp = 49.0,
                survivalYesPp = 51.0,
                mmShadowPp = 49.0,
                oddsHistory = listOf(45f, 55f),
                spotVsTargetUsd = -25.0
            ),
            openedAt + 60_000L
        ).associateBy { it.track }

        assertEquals("NO", candidates.getValue(ScalpSignal.Track.FAIR_VALUE_DISLOCATION).side)
        assertEquals("YES", candidates.getValue(ScalpSignal.Track.MODEL_DISLOCATION).side)
        assertEquals("YES", candidates.getValue(ScalpSignal.Track.IMPORTED_MODEL).side)
        assertEquals("NO", candidates.getValue(ScalpSignal.Track.MLP_CHANNEL).side)
        assertEquals("YES", candidates.getValue(ScalpSignal.Track.CNN_CHANNEL).side)
        assertEquals("NO", candidates.getValue(ScalpSignal.Track.GBM_CHANNEL).side)
        assertEquals("YES", candidates.getValue(ScalpSignal.Track.SURVIVAL_CHANNEL).side)
        assertEquals("NO", candidates.getValue(ScalpSignal.Track.MM_SHADOW).side)
        assertEquals("YES", candidates.getValue(ScalpSignal.Track.ODDS_TAPE).side)
        assertEquals("NO", candidates.getValue(ScalpSignal.Track.SPOT_TARGET).side)
    }

    @Test fun ticketCanOpenOutsideTheOldTimeAndSpotFilters() {
        val now = openedAt + 14 * 60_000L
        val ticket = TicketBuilder.proposeScalp(
            market(0.49, -0.9, 0.9).copy(aiYesPercent = 50.0, yesAskSize = 12.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        )!!
        assertEquals(TicketKind.SCALP, ticket.kind)
        assertEquals(12, ticket.contracts)
        org.junit.Assert.assertFalse(ticket.modelEdge)
    }

    @Test fun ticketIsForcedToPaperEvenWhenItQualifies() {
        val now = openedAt + 14 * 60_000L
        val ticket = TicketBuilder.proposeScalp(
            market(0.62, -0.0002, -0.0008).copy(aiYesPercent = 5.0, aiNoPercent = 95.0, yesAskSize = 10.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        )!!
        assertEquals(TicketKind.SCALP, ticket.kind)
        org.junit.Assert.assertTrue(ticket.paperOnly)
        org.junit.Assert.assertTrue(ticket.canPaper)
    }

    @Test fun multipleTracksProduceIndependentPaperTicketsForOneMarket() {
        val tickets = TicketBuilder.proposeScalps(
            market(0.50, -0.003, 0.010).copy(predictedSide = "YES", yesAskSize = 10.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = openedAt + 10_000L)
        )
        assertEquals(ScalpSignal.Track.values().size, tickets.size)
        assertEquals(ScalpSignal.Track.values().size, tickets.mapNotNull { it.strategyVersion }.toSet().size)
        org.junit.Assert.assertTrue(tickets.all { it.paperOnly && it.canPaper && !it.canApprove })
    }
}
