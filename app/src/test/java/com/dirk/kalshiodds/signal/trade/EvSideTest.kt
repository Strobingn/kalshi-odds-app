package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Side by expected value at the ask (docs/ml-review-2026-09-27.md #2).
 * Mirrored by `ev_side` in tools/backtest/pipeline.py (test_parity.py).
 */
class EvSideTest {

    private val feeRate = SignalConstants.DEFAULT_FEE_RATE
    private val stake = SignalConstants.DEFAULT_TICKET_STAKE_USD

    @Test
    fun fairEqualToTheMidNeverClearsAskPlusFee() {
        val r = EvSide.decide(pYes = 0.50, yesAsk = 0.51, noAsk = 0.51)
        assertNotNull(r)
        assertNull(r!!.side)
        assertTrue(r.evYes!! < 0.0 && r.evNo!! < 0.0)
        assertTrue(r.reason, r.reason.startsWith("Neither side beats its ask + fee by 3¢"))
        for (mid in listOf(0.05, 0.20, 0.41, 0.65, 0.93)) {
            val atMid = EvSide.decide(mid, yesAsk = mid + 0.01, noAsk = 1.0 - mid + 0.01)!!
            assertNull("fair == mid $mid", atMid.side)
        }
    }

    @Test
    fun evIsFairMinusAskMinusAmortizedFee() {
        // Same numbers as check_ev_side in tools/backtest/test_parity.py.
        // $5 at 45¢: 11 ct, ceil_cent(4.95 + 0.190575) = $5.15 → 20¢ fee / 11.
        assertEquals(0.20 / 11.0, KalshiFee.perContract(0.45, feeRate, stake), 1e-12)
        assertEquals(0.0175, KalshiFee.perContract(0.56, feeRate, stake), 1e-12)
        val r = EvSide.decide(0.60, yesAsk = 0.45, noAsk = 0.56)!!
        assertEquals(0.60 - 0.45 - 0.20 / 11.0, r.evYes!!, 1e-12)
        assertEquals(0.40 - 0.56 - 0.0175, r.evNo!!, 1e-12)
        assertEquals("YES", r.side)
        assertTrue(r.reason, r.reason.startsWith("UP beats its 45¢ ask + fee by"))
    }

    @Test
    fun marginIsMeasuredAfterTheFee() {
        val ask = 0.40
        val fee = KalshiFee.perContract(ask, feeRate, stake)
        val over = ask + fee + EvSide.DEFAULT_MARGIN + 0.001
        val under = ask + fee + EvSide.DEFAULT_MARGIN - 0.001
        assertEquals("YES", EvSide.decide(over, yesAsk = ask, noAsk = 0.99)!!.side)
        assertNull(EvSide.decide(under, yesAsk = ask, noAsk = 0.99)!!.side)
    }

    @Test
    fun clearNoEdgeIsNoEvenWhenHeroAndFairLeanYes() {
        // Fair 55% still says YES is more likely; the 31¢ NO ask is the bet.
        val m = market(yesAsk = 0.70, noAsk = 0.31, hero = "YES", fairPp = 55.0)
        assertEquals("NO", TicketBuilder.resolveSide(m))
        val r = TicketBuilder.evDecision(m)!!
        assertEquals("NO", r.side)
        assertTrue(r.evYes!! < 0.0)
        assertEquals(0.45 - 0.31 - 0.015, r.evNo!!, 1e-12)
        assertTrue(r.reason, r.reason.startsWith("DOWN beats its 31¢ ask"))
    }

    @Test
    fun missingAsksFallBackToTheOldSideOrder() {
        val noQuotes = market(yesAsk = null, noAsk = null, yesBid = null, noBid = null, hero = "NO", fairPp = 70.0)
        assertNull(TicketBuilder.evDecision(noQuotes))
        assertEquals("NO", TicketBuilder.resolveSide(noQuotes))
        assertNull(EvSide.decide(0.70, yesAsk = null, noAsk = null))
        assertNull("0¢ / 100¢ prints are not asks", EvSide.decide(0.70, yesAsk = 0.0, noAsk = 1.0))
        assertNull(EvSide.decide(Double.NaN, yesAsk = 0.40, noAsk = 0.61))
        // Not scored by the engine (no fair) → hero side as before.
        val unscored = market(yesAsk = 0.40, noAsk = 0.61, hero = "NO", fairPp = null)
        assertNull(TicketBuilder.evDecision(unscored))
        assertEquals("NO", TicketBuilder.resolveSide(unscored))
    }

    @Test
    fun missingNoAskIsDerivedFromTheYesBid() {
        val r = EvSide.decide(0.20, yesAsk = null, noAsk = null, yesBid = 0.40)!!
        assertEquals(0.60, r.noAsk!!, 1e-12)
        assertNull(r.yesAsk)
        assertNull(r.evYes)
        assertEquals("NO", r.side)
        // TicketBuilder derives the same way from the market quote.
        val m = market(yesAsk = null, noAsk = null, yesBid = 0.40, noBid = null, hero = "YES", fairPp = 20.0)
        assertEquals("NO", TicketBuilder.resolveSide(m))
    }

    @Test
    fun evSkipMeansNoConfiguredTicket() {
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketRespectGates = true,
                ticketsEnabled = true,
                ticketStakeUsd = 5.0,
                winTargetEnabled = false
            ),
            alertsPaused = false,
            idFactory = { "id" },
            nowMs = 1L
        )
        val cheap = market(yesAsk = 0.03, noAsk = 0.98, hero = "YES", fairPp = null, volume = 5_000.0, closeMs = null)
        assertEquals("unscored: hero side as before", "YES", TicketBuilder.propose(cheap, ctx)?.side)
        val atMid = cheap.copy(fairValuePp = 3.5)
        assertNull(TicketBuilder.resolveSide(atMid))
        assertNull("fair ≈ price: no ticket", TicketBuilder.propose(atMid, ctx))
        val value = cheap.copy(fairValuePp = 12.0)
        assertEquals("12% fair vs a 3¢ ask", "YES", TicketBuilder.propose(value, ctx)?.side)
    }

    @Test
    fun betCallIsNoBetWhenNeitherSideClearsEv() {
        // BetCallAgreementTest calls this BET UP from the hero side and an 80% AI;
        // with the engine's fair at the price, EV at the ask says no bet.
        val m = market(yesAsk = 0.20, noAsk = 0.80, hero = "YES", fairPp = 20.5, aiYes = 80.0)
        val d = BetCall.decide(m, ctx())
        assertEquals(BetCall.Headline.NO_BET, d.headline)
        assertNull(d.side)
        assertTrue(d.noBetReason!!, d.noBetReason!!.startsWith("Neither side"))
    }

    @Test
    fun betCallFollowsTheEvSideAgainstTheHero() {
        val m = market(yesAsk = 0.80, noAsk = 0.20, hero = "YES", fairPp = 10.0)
        val d = BetCall.decide(m, ctx())
        assertEquals(BetCall.Headline.BET_DOWN, d.headline)
        assertEquals("NO", d.side)
        assertEquals("NO", d.ticket!!.side)
        assertTrue(d.ticket!!.canApprove)
    }

    @Test
    fun engineScoredBetCallFixturesKeepTheirCalls() {
        // BetCallAgreementTest fixtures, now carrying the engine fair they show.
        val cases = listOf(
            Triple(market(0.20, 0.80, hero = "YES", fairPp = 80.0), BetCall.Headline.BET_UP, "YES"),
            Triple(market(0.80, 0.20, hero = "NO", fairPp = 10.0), BetCall.Headline.BET_DOWN, "NO"),
            // EV picks UP at 63¢, but that ticket misses the $10 min profit.
            Triple(market(0.63, 0.37, hero = "YES", fairPp = 70.0), BetCall.Headline.NO_BET, null),
            // 3¢ print with a 4% fair: EV too small after the fee.
            Triple(market(0.03, 0.97, hero = "YES", fairPp = 4.0), BetCall.Headline.NO_BET, null)
        )
        for ((m, headline, side) in cases) {
            val d = BetCall.decide(m, ctx())
            assertEquals("${m.yesAsk}", headline, d.headline)
            assertEquals("${m.yesAsk}", side, d.side)
            if (side != null) assertEquals(side, d.ticket!!.side)
        }
        val minProfit = BetCall.decide(market(0.63, 0.37, hero = "YES", fairPp = 70.0), ctx())
        assertTrue(minProfit.noBetReason!!, minProfit.noBetReason!!.contains("below"))
    }

    private fun ctx() = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)

    private fun market(
        yesAsk: Double?,
        noAsk: Double?,
        yesBid: Double? = yesAsk?.let { (it - 0.01).coerceAtLeast(0.01) },
        noBid: Double? = noAsk?.let { (it - 0.01).coerceAtLeast(0.01) },
        hero: String,
        fairPp: Double?,
        aiYes: Double? = fairPp,
        volume: Double = 2_000.0,
        closeMs: Long? = System.currentTimeMillis() + 600_000L
    ) = MarketUiModel(
        ticker = "KXBTC15M-EV",
        title = "BTC",
        subtitle = null,
        floorStrike = 100_000.0,
        yesBid = yesBid,
        yesAsk = yesAsk,
        noBid = noBid,
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk?.times(100.0),
        noProbabilityPercent = noAsk?.times(100.0),
        aiYesPercent = aiYes,
        aiNoPercent = aiYes?.let { 100.0 - it },
        volume = volume,
        volume24h = volume,
        openInterest = if (volume >= 5_000.0) volume else 200.0,
        liquidityDollars = 8_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = closeMs,
        status = if (closeMs == null) "open" else "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = hero,
        primaryHeroSide = hero,
        fairValuePp = fairPp
    )
}
