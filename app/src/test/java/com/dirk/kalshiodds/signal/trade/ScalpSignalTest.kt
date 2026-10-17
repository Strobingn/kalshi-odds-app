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

    @Test fun twentyTracksRemainVisibleWithVersionedV6Formulas() {
        val candidates = ScalpSignal.candidates(
            market(0.50, 0.004, 0.020).copy(predictedSide = "YES"),
            openedAt + 60_000L
        )
        assertEquals(20, candidates.size)
        assertEquals(ScalpSignal.Track.values().map { it.formulaVersion }, candidates.map { it.formulaVersion })
        assertEquals(listOf("YES", "YES", "NO", "NO", "YES", null, "NO", "YES"), candidates.take(8).map { it.side })
    }

    @Test fun missingFeatureIsRecordedAsNoObservationInsteadOfBaselineFallback() {
        val candidates = ScalpSignal.candidates(
            market(0.50, 0.004, 0.020).copy(predictedSide = "YES"),
            openedAt + 60_000L
        ).associateBy { it.track }

        val imported = candidates.getValue(ScalpSignal.Track.IMPORTED_MODEL)
        assertEquals(null, imported.side)
        org.junit.Assert.assertTrue(imported.selectedFrom.contains("unavailable"))
        org.junit.Assert.assertTrue(imported.fingerprint.contains("NO_OBSERVATION"))
    }

    @Test fun exitProbabilityUsesTheTrackInputRatherThanGenericAi() {
        val m = market(0.50, 0.004, 0.020).copy(
            aiYesPercent = 90.0,
            importedModelPp = 35.0,
            finalMinuteFairPp = 80.0,
            finalMinuteSamples = 30
        )
        assertEquals(0.35, ScalpSignal.exitProbability("scalp-v6-imported-model", m, "YES")!!, 1e-9)
        assertEquals(0.20, ScalpSignal.exitProbability("scalp-v6-fair-value-follower", m, "NO")!!, 1e-9)
        org.junit.Assert.assertNull(ScalpSignal.exitProbability("scalp-v6-momentum-sniper", m, "YES"))
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

    @Test fun retiredBaselineCannotOpenAnAutomaticPaperTicket() {
        val now = openedAt + 14 * 60_000L
        val ticket = TicketBuilder.proposeScalp(
            market(0.49, -0.9, 0.9).copy(aiYesPercent = 50.0, yesAskSize = 12.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        )
        org.junit.Assert.assertNull(ticket)
    }

    @Test fun ticketIsForcedToPaperEvenWhenItQualifies() {
        val now = openedAt + 14 * 60_000L
        val ticket = TicketBuilder.proposeScalp(
            market(0.62, -0.0002, -0.0008).copy(digitalFairPp = 82.0, yesAskSize = 10.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        )!!
        assertEquals(TicketKind.SCALP, ticket.kind)
        org.junit.Assert.assertTrue(ticket.paperOnly)
        org.junit.Assert.assertTrue(ticket.canPaper)
    }

    @Test fun onlyEvidenceGatedTracksProduceIndependentPaperTicketsForOneMarket() {
        val tickets = TicketBuilder.proposeScalps(
            market(0.50, -0.003, 0.010).copy(predictedSide = "YES", digitalFairPp = 70.0, gbmPp = 70.0, yesAskSize = 10.0),
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = openedAt + 2 * 60_000L)
        )
        assertEquals(4, tickets.size)
        assertEquals(tickets.size, tickets.mapNotNull { it.strategyVersion }.toSet().size)
        org.junit.Assert.assertTrue(tickets.all { it.paperOnly && it.canPaper && !it.canApprove })
    }

    @Test fun probabilityTracksStoreTheChosenSideProbabilityAndRequireTheirConfiguredEdge() {
        val candidate = ScalpSignal.candidates(
            market(0.50, 0.0, 0.0).copy(digitalFairPp = 30.0),
            openedAt + 2 * 60_000L
        ).first { it.track == ScalpSignal.Track.FAIR_VALUE_DISLOCATION }

        assertEquals("NO", candidate.side)
        assertEquals(0.70, candidate.entryProbability!!, 1e-9)
        org.junit.Assert.assertTrue(ScalpSignal.allowsEntry(candidate, sideAsk = 0.65, allInPerContract = 0.66))
        org.junit.Assert.assertFalse(ScalpSignal.allowsEntry(candidate, sideAsk = 0.68, allInPerContract = 0.67))
    }
}
