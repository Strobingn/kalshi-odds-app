package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetCallSafetyTest {

    @Test
    fun finalThirtySecondsDoesNotRecommendCheapDownAgainstStrongUpDirection() {
        val nowMs = 1_000_000L
        val market = finalWindowMarket(nowMs)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(),
            alertsPaused = false,
            nowMs = nowMs,
            // A 0.1c NO ask comes from a 99.9c YES bid. The snapshot has
            // enough visible depth that this regression cannot be dismissed
            // as a missing-book case.
            books = mapOf(market.ticker to BookLevelSnapshot(yes = listOf(0.999 to 5_000.0)))
        )

        val manualDown = TicketBuilder.proposeManual(market, "NO", ctx)
        assertTrue("manual Buy may still construct a user-requested ticket", manualDown != null)
        assertTrue("this reproduces the old 5% vs 0.1c mechanical edge", BetCall.qualifies(manualDown!!, market, ctx))

        val decision = BetCall.decide(market, ctx)

        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
        assertTrue(decision.noBetReason.orEmpty().contains("final-window"))
    }

    @Test
    fun recommendationRequiresVerifiedOrderBookButManualBuyRemainsAvailable() {
        val nowMs = 2_000_000L
        val market = finalWindowMarket(nowMs).copy(
            closeTimeEpochMs = nowMs + 5 * 60_000L,
            spotVsTargetUsd = null,
            primaryHeroSide = "YES",
            predictedSide = "YES",
            importedModelPp = 80.0,
            aiYesPercent = 80.0
        )
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = nowMs)

        assertTrue(TicketBuilder.proposeManual(market, "YES", ctx)?.canApprove == true)
        val decision = BetCall.decide(market, ctx)

        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertTrue(decision.noBetReason.orEmpty().contains("verified live order-book"))
    }

    @Test
    fun automaticRecommendationAbstainsOnAmbiguousConformalSetOrPoorFill() {
        val nowMs = 3_000_000L
        val market = finalWindowMarket(nowMs).copy(
            closeTimeEpochMs = nowMs + 5 * 60_000L,
            conformalAmbiguous = true,
            pFill = 0.25,
            predictedSide = "YES",
            primaryHeroSide = "YES",
            aiYesPercent = 99.0,
            importedModelPp = 99.0
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(),
            alertsPaused = false,
            nowMs = nowMs,
            books = mapOf(market.ticker to BookLevelSnapshot(yes = listOf(0.999 to 5_000.0)))
        )

        val decision = BetCall.decide(market, ctx)

        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertTrue(decision.noBetReason.orEmpty().contains("uncertain"))
    }

    private fun finalWindowMarket(nowMs: Long) = MarketUiModel(
        ticker = "KXBTC15M-SAFETY",
        title = "Bitcoin price up?",
        subtitle = "Target \$85,684",
        floorStrike = 85_684.0,
        yesBid = 0.999,
        yesAsk = null,
        noBid = null,
        noAsk = 0.001,
        lastPrice = 0.999,
        yesProbabilityPercent = 99.9,
        noProbabilityPercent = 0.1,
        aiYesPercent = 95.0,
        aiNoPercent = 5.0,
        importedModelPp = 95.0,
        aiConfidence = 0.95,
        volume = 2_000.0,
        volume24h = 2_000.0,
        openInterest = 500.0,
        liquidityDollars = 10_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = nowMs + 30_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = "NO",
        primaryHeroSide = "NO",
        spotVsTargetUsd = 78.0
    )
}
