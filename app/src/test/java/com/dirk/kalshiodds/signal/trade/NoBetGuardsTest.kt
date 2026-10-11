package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.feedback.EdgeAutoTuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must not tell the user to bet when the "edge" is not real.
 *
 * Reported 2026-10-09 with a screenshot: `BET UP BTC · $5 wins $2330.00
 * profit · closes in 1:11`, UP at 0.2¢, "Model 4% vs market 0% · edge +3 pts".
 * The 4% was the model's floor.
 */
class NoBetGuardsTest {

    private fun market(yesAsk: Double, noAsk: Double, aiYes: Double, fairPp: Double? = null, secondsLeft: Long = 71) = MarketUiModel(
        ticker = "KXBTC15M-26OCT091845-45",
        title = "BTC",
        subtitle = null,
        floorStrike = 82_436.0,
        yesBid = (yesAsk - 0.001).coerceAtLeast(0.001),
        yesAsk = yesAsk,
        noBid = (noAsk - 0.001).coerceAtLeast(0.001),
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
        closeTimeEpochMs = System.currentTimeMillis() + secondsLeft * 1000L,
        status = "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = "YES",
        primaryHeroSide = "YES",
        fairValuePp = fairPp,
        modelBacked = fairPp != null
    )

    private val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)

    @Test
    fun theScreenshotIsNoBet() {
        // With the engine's fair value (the path in the screenshot) and without it.
        for (fair in listOf(4.0, null)) {
            val d = BetCall.decide(market(yesAsk = 0.002, noAsk = 0.999, aiYes = 4.0, fairPp = fair), ctx)
            assertEquals("fair=$fair", BetCall.Headline.NO_BET, d.headline)
            assertFalse(d.isActionable)
            assertNull(d.ticket)
            assertEquals(
                "UP costs 0.2¢. That far out the model cannot tell 0.2% from a few percent, so its \"edge\" is not real. No bet.",
                d.noBetReason
            )
        }
    }

    @Test
    fun theAppNeverSuggestsASidePricedUnderFiveCents() {
        assertTrue(TicketBuilder.isLongShotPrice(0.002))
        assertTrue(TicketBuilder.isLongShotPrice(0.049))
        assertFalse(TicketBuilder.isLongShotPrice(0.05))
        assertFalse(TicketBuilder.isLongShotPrice(0.20))
        assertFalse(TicketBuilder.isLongShotPrice(null))
        assertEquals(
            "DOWN costs 3¢. That far out the model cannot tell 3% from a few percent, so its \"edge\" is not real. No bet.",
            TicketBuilder.longShotReason("NO", 0.03)
        )
        // Neither the hunter nor any other automatic ticket is offered down there, whatever the model says.
        for (ai in listOf(4.0, 30.0, 90.0)) {
            for (ask in listOf(0.002, 0.01, 0.03, 0.045)) {
                val m = market(yesAsk = ask, noAsk = 1.0 - ask + 0.001, aiYes = ai, fairPp = ai, secondsLeft = 600)
                assertTrue("ask $ask ai $ai", TicketBuilder.proposeAll(listOf(m), ctx).none { it.limitPrice < TicketBuilder.MIN_CALL_ASK })
                assertEquals("ask $ask ai $ai", BetCall.Headline.NO_BET, BetCall.decide(m, ctx).headline)
            }
        }
        // The user's own Buy still builds a ticket there: the app just does not recommend it.
        assertTrue(TicketBuilder.proposeManual(market(0.03, 0.98, 4.0, secondsLeft = 600), "YES", ctx) != null)
    }

    // ---- the model says BET only when its record is clear of chance ----

    private fun call(model: Double, mid: Double, yes: Boolean) = EdgeAutoTuner.Sample(
        modelYes = model, marketMid = mid, outcomeYes = yes, edgeAfterFeesPp = (kotlin.math.abs(model - mid) - 0.0175) * 100.0
    )

    @Test
    fun noRecordMeansNoBetCalls() {
        val r = EdgeAutoTuner.tune((0 until 10).map { call(0.70, 0.50, true) }, minSamples = 30)
        assertFalse(r.enoughSamples)
        assertTrue("ten wins in a row is not a record", r.sitOut)
        assertEquals("No bet calls yet: the model has 10 settled calls and needs 30 before its record means anything.", r.reason)
        assertTrue(EdgeAutoTuner.tune(emptyList(), minSamples = 30).sitOut)
    }

    @Test
    fun aRecordThatIsAheadByLuckIsStillNoBet() {
        // 17 right, 13 wrong at even money: ahead, and better than the market on both scores, but well inside chance.
        val lucky = (0 until 30).map { i -> call(0.60, 0.50, yes = i < 17) }
        val r = EdgeAutoTuner.tune(lucky, minSamples = 30)
        assertTrue(r.enoughSamples)
        assertTrue(r.beatsMarket)
        assertTrue((r.evAtThreshold ?: 0.0) > 0.0)
        assertTrue(EdgeAutoTuner.lowerBound(lucky) < 0.0)
        assertTrue(r.sitOut)
        assertTrue(r.reason, r.reason.contains("by no more than luck would give"))
    }

    @Test
    fun aRecordClearOfChanceLetsTheModelCall() {
        // 27 right, 3 wrong at even money.
        val strong = (0 until 30).map { i -> call(0.70, 0.50, yes = i < 27) }
        val r = EdgeAutoTuner.tune(strong, minSamples = 30)
        assertTrue(EdgeAutoTuner.lowerBound(strong) > 0.0)
        assertFalse(r.sitOut)
        assertTrue(r.reason, r.reason.startsWith("Auto-tune"))
    }
}
