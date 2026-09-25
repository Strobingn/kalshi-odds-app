package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KalshiFeeAndWinTargetTest {
    @Test
    fun feeAtTwentyCents() {
        // Official: ceil(0.07 × C × P × (1−P) × 100) / 100. 1 ct @ 20¢ → $0.02.
        val fee = KalshiFee.perContract(0.20)
        assertEquals(0.02, fee, 1e-9)
        val net = KalshiFee.netPayout(5, 0.20)
        assertEquals(5.0 - KalshiFee.total(5, 0.20), net, 1e-9)
        assertEquals(0.02, KalshiFee.perContract(0.50), 1e-9)
        // GET /series/fee_changes?series_ticker=KXBTC15M|KXETH15M|KXSOL15M returned []
        // on 2026-09-25 — no multiplier, so default 0.07 applies to these 15m markets.
        assertEquals(0.07, SignalConstants.DEFAULT_FEE_RATE, 1e-12)
    }

    @Test
    fun winTargetDefaultsToFiftyOn() {
        assertTrue(SignalConstants.DEFAULT_WIN_TARGET_ENABLED)
        assertEquals(50.0, SignalConstants.DEFAULT_WIN_TARGET_USD, 1e-9)
        assertEquals(10.0, SignalConstants.DEFAULT_WIN_TARGET_BANKROLL_PCT, 1e-9)
        assertEquals(0.20, SignalConstants.DEFAULT_LONG_SHOT_MAX_ASK, 1e-9)
        val defaults = SignalSettings()
        assertTrue(defaults.winTargetEnabled)
        assertEquals(50.0, defaults.winTargetUsd, 1e-9)
        assertEquals(0.20, defaults.longShotMaxAsk, 1e-9)
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
    fun longShotDoesNotSurfaceWithoutEdge() {
        val market = sample(yesAsk = 0.20, noAsk = 0.80, aiYes = 22.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )
        assertTrue(ticket == null)
    }

    @Test
    fun longShotSizesTwentyCentsTowardFifty() {
        val market = sample(yesAsk = 0.20, noAsk = 0.80, aiYes = 40.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(
                settings = SignalSettings(),
                alertsPaused = false,
                bankrollUsd = 1_000.0
            )
        )
        assertTrue(ticket != null)
        assertEquals(TicketKind.HUNTER_VALUE, ticket!!.kind)
        assertTrue(ticket.modelEdge)
        assertEquals(50.0, ticket.winTargetUsd!!, 1e-9)
        assertEquals(0.20, ticket.impliedChance!!, 1e-9)
        assertEquals(0.40, ticket.modelChance!!, 1e-9)
        assertTrue(ticket.canApprove)
        assertTrue(ticket.gateNote!!.contains("Long-shot"))
        assertTrue(ticket.gateNote!!.contains("Approve still required"))
        assertFalse(ticket.gateNote!!.contains("$1 → $5") || ticket.gateNote!!.contains("$1→$5"))
        // 20¢ + 1.12¢ fee → ~$0.79 profit/ct → ~64 ct / ~$12.80 for $50.
        assertTrue(ticket.contracts in 60..70)
        assertTrue(ticket.stakeUsd in 12.0..14.0)
        assertTrue((ticket.profitIfWinUsd ?: 0.0) + 1e-6 >= 50.0)
        assertFalse(ticket.winTargetCapped)
    }

    @Test
    fun longShotSizesNineCentsNearFiveDollars() {
        val market = sample(yesAsk = 0.09, noAsk = 0.91, aiYes = 40.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(
                settings = SignalSettings(),
                alertsPaused = false,
                bankrollUsd = 1_000.0
            )
        )
        assertTrue(ticket != null)
        assertEquals(TicketKind.HUNTER_VALUE, ticket!!.kind)
        assertTrue(ticket.stakeUsd in 4.5..6.0)
        assertTrue((ticket.profitIfWinUsd ?: 0.0) + 1e-6 >= 50.0)
        assertEquals(50.0, ticket.winTargetUsd!!, 1e-9)
    }

    @Test
    fun longShotDoesNotSurfaceAtTwentyOneCents() {
        val market = sample(yesAsk = 0.21, noAsk = 0.79, aiYes = 80.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        )
        assertTrue(ticket == null)
    }

    @Test
    fun longShotRespectsEditableMaxAsk() {
        val market = sample(yesAsk = 0.25, noAsk = 0.75, aiYes = 80.0)
        val blocked = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(
                settings = SignalSettings(longShotMaxAsk = 0.20),
                alertsPaused = false
            )
        )
        assertTrue(blocked == null)
        val open = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(
                settings = SignalSettings(longShotMaxAsk = 0.30),
                alertsPaused = false,
                bankrollUsd = 1_000.0
            )
        )
        assertTrue(open != null)
        assertEquals(TicketKind.HUNTER_VALUE, open!!.kind)
    }

    @Test
    fun hunterAndManualAlsoSizeToFifty() {
        val cheap = sample(yesAsk = 0.04, noAsk = 0.96, aiYes = 30.0)
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(),
            alertsPaused = false,
            bankrollUsd = 1_000.0
        )
        val hunter = TicketBuilder.proposeHunter(cheap, ctx)
        assertEquals(TicketKind.HUNTER, hunter!!.kind)
        assertEquals(50.0, hunter.winTargetUsd!!, 1e-9)
        assertTrue(hunter.contracts > 25)
        assertTrue((hunter.profitIfWinUsd ?: 0.0) + 1e-6 >= 50.0)

        val fiveCent = sample(yesAsk = 0.05, noAsk = 0.95, aiYes = 30.0).copy(predictedSide = "YES")
        val configured = TicketBuilder.propose(fiveCent, ctx)
        assertEquals(TicketKind.CONFIGURED, configured!!.kind)
        assertEquals(50.0, configured.winTargetUsd!!, 1e-9)

        val manual = TicketBuilder.proposeManual(fiveCent, "YES", ctx)
        assertEquals(TicketKind.MANUAL, manual!!.kind)
        assertEquals(50.0, manual.winTargetUsd!!, 1e-9)
        assertTrue((manual.profitIfWinUsd ?: 0.0) + 1e-6 >= 50.0)
    }

    @Test
    fun twentyCentNetPayoutAfterFeesIsJustUnderFive() {
        val fee = KalshiFee.perContract(0.20, 0.07)
        val net = KalshiFee.netPayout(5, 0.20, 0.07)
        assertEquals(0.02, fee, 1e-9)
        assertEquals(4.94, net, 1e-9)
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
        assertTrue(capped.note.startsWith("Capped: wins $"))
        assertTrue(capped.profitIfWin < 50.0)
    }

    @Test
    fun winTargetWalksVwapNotTopOfBook() {
        val levels = listOf(0.10 to 2.0, 0.20 to 8.0)
        val hit = WinTargetSizer.size(
            askLevels = levels,
            targetProfitUsd = 5.0,
            bankrollUsd = 1_000.0,
            bankrollPct = 50.0,
            feeRate = 0.07
        )
        assertTrue(hit.contracts > 2)
        assertTrue(hit.vwap > 0.10 + 1e-9)
        assertTrue(hit.profitIfWin + 1e-6 >= 5.0 || hit.capped || hit.insufficientDepth)
    }

    @Test
    fun liveCashAndPaperEquityAreDistinctCaps() {
        val levels = listOf(0.20 to 1_000.0)
        val live = WinTargetSizer.size(
            askLevels = levels,
            targetProfitUsd = 50.0,
            bankrollUsd = 200.0,
            bankrollPct = 10.0,
            feeRate = 0.07
        )
        val paper = WinTargetSizer.size(
            askLevels = levels,
            targetProfitUsd = 50.0,
            bankrollUsd = 100.0,
            bankrollPct = 10.0,
            feeRate = 0.07
        )
        assertTrue(live.stakeUsd <= 20.0 + 1e-6)
        assertTrue(paper.stakeUsd <= 10.0 + 1e-6)
        assertTrue(paper.stakeUsd < live.stakeUsd + 1e-9)
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
        assertTrue(thin.capped)
        assertTrue(thin.note.startsWith("Capped: wins $"))
    }

    @Test
    fun paperBookUsesLongShotTicketSize() {
        val market = sample(yesAsk = 0.20, noAsk = 0.80, aiYes = 40.0)
        val ticket = TicketBuilder.proposeHunterValue(
            market,
            TicketBuilder.Context(
                settings = SignalSettings(),
                alertsPaused = false,
                bankrollUsd = 1_000.0
            )
        )!!
        val book = com.dirk.kalshiodds.signal.paper.PaperBook(idFactory = { "ls" }, nowMs = { 1L })
        val fill = book.manualFill(ticket)
        assertTrue(fill != null)
        assertEquals(ticket.contracts, fill!!.contracts)
        assertEquals(ticket.stakeUsd, fill.stakeUsd, 1e-9)
        val fees = KalshiFee.total(fill.contracts, fill.limitPrice)
        assertTrue(abs(100.0 - ticket.stakeUsd - fees - book.snapshot().cashUsd) < 1e-6)
        assertTrue(fill.note.contains("win-target"))
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
