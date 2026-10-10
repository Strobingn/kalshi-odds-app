package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetCallAgreementTest {

    @Test
    fun betUpHeadlineMatchesTicketAndApproveSide() {
        val market = sample(yesAsk = 0.20, noAsk = 0.80, aiYes = 80.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.BET_UP, decision.headline)
        assertEquals("YES", decision.side)
        assertEquals("YES", decision.ticket!!.side)
        assertTrue(decision.ticket!!.canApprove)
        assertEquals(decision.side, decision.ticket!!.side)
    }

    @Test
    fun betDownHeadlineMatchesTicketAndApproveSide() {
        val market = sample(yesAsk = 0.80, noAsk = 0.20, aiYes = 10.0, predicted = "NO")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.BET_DOWN, decision.headline)
        assertEquals("NO", decision.side)
        assertEquals("NO", decision.ticket!!.side)
        assertTrue(decision.ticket!!.canApprove)
        assertEquals(decision.side, decision.ticket!!.side)
    }

    @Test
    fun edgeSizedSixtyThreeCentsWithModelEdgeIsActionable() {
        val market = sample(yesAsk = 0.63, noAsk = 0.37, aiYes = 70.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val decision = BetCall.decide(market, ctx)
        // Edge sizing grew the clip past the $10 min profit that the flat $5
        // cap used to leave on the floor at 63¢.
        assertEquals(BetCall.Headline.BET_UP, decision.headline)
        assertTrue(decision.isActionable)
        assertTrue((decision.profitIfWinUsd ?: 0.0) >= 10.0 - 1e-6)
    }

    @Test
    fun cheapHunterWithoutModelEdgeIsNoBetHeadline() {
        val market = sample(yesAsk = 0.03, noAsk = 0.97, aiYes = 4.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val hunter = TicketBuilder.proposeHunter(market, ctx)
        assertTrue("hunter ticket still appears in the list", hunter != null)
        assertEquals(TicketKind.HUNTER, hunter!!.kind)
        assertFalse(
            "3¢ print the model does not favor is not an edge",
            hunter.modelEdge || TicketBuilder.modelBeatsImplied(
                hunter.modelChance,
                hunter.impliedChance,
                ctx.settings.feeRate,
                stakeUsd = ctx.settings.ticketStakeUsd
            )
        )
        assertFalse(BetCall.qualifies(hunter, market, ctx))
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
    }

    @Test
    fun sortPutsActionableFirst() {
        assertTrue(BetCall.sortKey(BetCall.decide(sample(0.20, 0.80, 80.0, "YES"), SignalSettings())) == 0)
        // No model edge at 63¢ (AI 55%) — stays NO BET even edge-sized.
        assertTrue(BetCall.sortKey(BetCall.decide(sample(0.63, 0.37, 55.0, "YES"), SignalSettings())) == 1)
    }

    private fun sample(yesAsk: Double, noAsk: Double, aiYes: Double, predicted: String) = MarketUiModel(
        ticker = "KXETH15M-TEST",
        title = "ETH",
        subtitle = null,
        floorStrike = 4000.0,
        yesBid = (yesAsk - 0.01).coerceAtLeast(0.01),
        yesAsk = yesAsk,
        noBid = (noAsk - 0.01).coerceAtLeast(0.01),
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        aiYesPercent = aiYes,
        aiNoPercent = 100.0 - aiYes,
        volume = 2000.0,
        volume24h = 2000.0,
        openInterest = 200.0,
        liquidityDollars = 8000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
        status = "active",
        seriesLabel = "Ethereum",
        passedFilter = true,
        predictedSide = predicted,
        primaryHeroSide = predicted
    )
}
