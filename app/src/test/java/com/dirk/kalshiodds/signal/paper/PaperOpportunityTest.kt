package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperOpportunityTest {
    @Test
    fun researchCandidateUsesVisibleTouchWithoutTheLiveFiveDollarGate() {
        val market = MarketUiModel(
            ticker = "KXBTC15M-TEST",
            title = "BTC",
            subtitle = null,
            floorStrike = 100_000.0,
            yesBid = 0.19,
            yesAsk = 0.20,
            noBid = 0.79,
            noAsk = 0.80,
            lastPrice = 0.20,
            yesProbabilityPercent = 20.0,
            noProbabilityPercent = 80.0,
            aiYesPercent = 80.0,
            aiNoPercent = 20.0,
            volume = 1_000.0,
            volume24h = 1_000.0,
            closeTimeLocal = null,
            closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
            status = "active",
            seriesLabel = "Bitcoin"
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(),
            alertsPaused = false,
            books = mapOf(market.ticker to BookLevelSnapshot(
                yes = listOf(0.19 to 125.0),
                no = listOf(0.80 to 125.0)
            ))
        )

        val candidate = PaperOpportunity.best(market, ctx)!!

        assertEquals("YES", candidate.side)
        assertEquals(125, candidate.visibleContracts)
        assertTrue(candidate.expectedNetPerContractUsd > 0.0)
    }
}
