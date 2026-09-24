package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiFeeAndWinTargetTest {
    @Test
    fun feeAtTwentyCents() {
        val fee = KalshiFee.perContract(0.20)
        assertEquals(0.07 * 0.20 * 0.80, fee, 1e-9)
        val net = KalshiFee.netPayout(5, 0.20)
        assertEquals(5.0 - 5 * fee, net, 1e-9)
    }

    @Test
    fun oneDollarToFiveIsTwentyCentsGross() {
        val max = PayoutGate.maxLimitForPayout(
            SignalConstants.HUNTER_VALUE_STAKE_USD,
            SignalConstants.HUNTER_VALUE_PAYOUT_USD
        )
        assertEquals(0.20, max!!, 1e-9)
        val pass = PayoutGate.evaluate(1.0, 0.20, quotedSize = 50.0, minPayoutUsd = 5.0)
        assertTrue(pass.reason, pass.ok)
        assertEquals(5, pass.contracts)
        val fail = PayoutGate.evaluate(1.0, 0.25, quotedSize = 50.0, minPayoutUsd = 5.0)
        assertFalse(fail.ok)
    }

    @Test
    fun modelBeatsImpliedRequiresFeePlusMargin() {
        assertFalse(TicketBuilder.modelBeatsImplied(0.22, 0.20, 0.07, 0.03))
        assertTrue(TicketBuilder.modelBeatsImplied(0.40, 0.20, 0.07, 0.03))
    }

    @Test
    fun hunterValueSurfacesAtTwentyCents() {
        val market = sample(yesAsk = 0.20, noAsk = 0.80, aiYes = 22.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )
        assertTrue(ticket != null)
        assertEquals(TicketKind.HUNTER_VALUE, ticket!!.kind)
        assertEquals(5, ticket.contracts)
        assertFalse(ticket.modelEdge)
    }

    @Test
    fun hunterValueHighlightsEdgeWhenModelClearsFee() {
        val market = sample(yesAsk = 0.18, noAsk = 0.82, aiYes = 40.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )
        assertTrue(ticket != null)
        assertTrue(ticket!!.modelEdge)
        assertEquals(0.18, ticket.impliedChance!!, 1e-9)
        assertEquals(0.40, ticket.modelChance!!, 1e-9)
        assertTrue(ticket.canApprove)
        assertTrue(ticket.gateNote!!.contains("Approve still required"))
    }

    @Test
    fun hunterValueDoesNotSurfaceAtTwentyOneCents() {
        val market = sample(yesAsk = 0.21, noAsk = 0.79, aiYes = 80.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )
        assertTrue(ticket == null)
    }

    @Test
    fun existingHunterAndConfiguredTiersStillFire() {
        val cheap = sample(yesAsk = 0.04, noAsk = 0.96, aiYes = 30.0)
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        val hunter = TicketBuilder.proposeHunter(cheap, ctx)
        assertEquals(TicketKind.HUNTER, hunter!!.kind)
        assertEquals(25, hunter.contracts)

        val fiveCent = sample(yesAsk = 0.05, noAsk = 0.95, aiYes = 30.0).copy(predictedSide = "YES")
        val configured = TicketBuilder.propose(fiveCent, ctx)
        assertEquals(TicketKind.CONFIGURED, configured!!.kind)
        assertTrue(configured.maxPayoutUsd + 1e-9 >= 100.0)
    }

    @Test
    fun twentyCentNetPayoutAfterFeesIsJustUnderFive() {
        // Gate stays gross $5 / 5 contracts (user: ask ≤ 20¢). Fees are for edge + P&L.
        val fee = KalshiFee.perContract(0.20, 0.07)
        val net = KalshiFee.netPayout(5, 0.20, 0.07)
        assertEquals(0.0112, fee, 1e-9)
        assertEquals(4.944, net, 1e-9)
        assertTrue(net < 5.0)
        val profit = KalshiFee.netProfit(5, 0.20, 0.07)
        assertEquals(net - 1.0, profit, 1e-9)
    }

    @Test
    fun winTargetWalksDepthAndCaps() {
        val levels = listOf(0.40 to 10.0, 0.42 to 10.0, 0.50 to 200.0)
        val hit = WinTargetSizer.size(
            askLevels = levels,
            targetProfitUsd = 10.0,
            bankrollUsd = 200.0,
            bankrollPct = 50.0,
            feeRate = 0.07
        )
        assertTrue(hit.contracts > 0)
        assertTrue(hit.profitIfWin + 1e-6 >= 10.0 || hit.capped || hit.insufficientDepth)

        val capped = WinTargetSizer.size(
            askLevels = listOf(0.80 to 1_000.0),
            targetProfitUsd = 50.0,
            bankrollUsd = 20.0,
            bankrollPct = 10.0,
            absCapUsd = 2.0,
            feeRate = 0.07
        )
        assertTrue(capped.capped || capped.stakeUsd <= 2.0 + 1e-6)
        assertTrue(capped.note.contains("Capped") || capped.profitIfWin < 50.0)
    }

    @Test
    fun winTargetInsufficientDepth() {
        val thin = WinTargetSizer.size(
            askLevels = listOf(0.30 to 1.0),
            targetProfitUsd = 50.0,
            bankrollUsd = 1_000.0,
            bankrollPct = 50.0
        )
        assertTrue(thin.insufficientDepth || thin.contracts < 50)
    }

    private fun sample(yesAsk: Double, noAsk: Double, aiYes: Double) = MarketUiModel(
        ticker = "KXBTC15M-26SEP241930-30",
        title = "BTC",
        subtitle = null,
        floorStrike = 84_000.0,
        yesBid = yesAsk - 0.01,
        yesAsk = yesAsk,
        noBid = noAsk - 0.01,
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        aiYesPercent = aiYes,
        aiNoPercent = 100.0 - aiYes,
        volume = 1_000.0,
        volume24h = 1_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
        status = "active",
        seriesLabel = "Bitcoin"
    )
}
