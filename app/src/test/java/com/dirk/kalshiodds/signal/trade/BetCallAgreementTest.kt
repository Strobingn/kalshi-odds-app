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
    fun noBetWhenSixtyThreeCentsMissesMinProfit() {
        val market = sample(yesAsk = 0.63, noAsk = 0.37, aiYes = 70.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertNull(decision.side)
        assertFalse(decision.isActionable)
        assertTrue(decision.noBetReason!!.contains("below") || decision.noBetReason!!.contains("No side"))
    }

    @Test
    fun cheapHunterWithoutModelEdgeIsNoBetHeadline() {
        val market = sample(yesAsk = 0.03, noAsk = 0.97, aiYes = 4.0, predicted = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        // Automatic tickets require positive modeled value after entry + fee;
        // a 3c print the model does not favor never becomes a ticket.
        assertNull("no-edge print must not surface a ticket", TicketBuilder.proposeHunter(market, ctx))
        assertNull(TicketBuilder.proposeAll(listOf(market), ctx).firstOrNull())
        val decision = BetCall.decide(market, ctx)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
    }

    @Test
    fun sortPutsActionableFirst() {
        assertTrue(BetCall.sortKey(BetCall.decide(sample(0.20, 0.80, 80.0, "YES"), SignalSettings())) == 0)
        assertTrue(BetCall.sortKey(BetCall.decide(sample(0.63, 0.37, 70.0, "YES"), SignalSettings())) == 1)
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
