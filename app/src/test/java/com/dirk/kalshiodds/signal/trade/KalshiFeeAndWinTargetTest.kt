package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KalshiFeeAndWinTargetTest {
    @Test
    fun feeAtTwentyCents() {
        // 1 ct @ 20¢: model 0.07×1×0.20×0.80 = 0.0112 → ceil_6dp 0.011200
        // debit = ceil_cent(0.20 + 0.0112) = 0.22, order fee = $0.02
        assertEquals(0.02, KalshiFee.total(1, 0.20), 1e-9)
        // $5 ticket: C=25, model = 0.28, order fee = $0.28, amortized = 0.0112
        assertEquals(25, KalshiFee.contractsForStake(5.0, 0.20))
        assertEquals(0.28, KalshiFee.total(25, 0.20), 1e-9)
        assertEquals(0.0112, KalshiFee.perContract(0.20, stakeUsd = 5.0), 1e-9)
        // $10 @ 20¢: C=50, fee $0.56, amortized still $0.0112
        assertEquals(0.0112, KalshiFee.perContract(0.20), 1e-9)
        // $5 @ 50¢: C=10, fee $0.18, amortized $0.018
        assertEquals(0.018, KalshiFee.perContract(0.50, stakeUsd = 5.0), 1e-9)
        // $10 @ 50¢: C=20, fee $0.35, amortized $0.0175
        assertEquals(0.0175, KalshiFee.perContract(0.50), 1e-9)
        // Settlement is C × $1 — fee is not taken from the payout.
        assertEquals(5.0, KalshiFee.netPayout(5, 0.20), 1e-9)
        assertEquals(5.0, KalshiFee.settlementPayout(5), 1e-9)
        // GET /series/KXBTC15M|KXETH15M|KXSOL15M → fee_type=quadratic, multiplier=1
        // GET /series/fee_changes?series_ticker=KXBTC15M returned [] on 2026-09-25.
        assertEquals(0.07, SignalConstants.DEFAULT_FEE_RATE, 1e-12)
        assertEquals(0.07, KalshiFee.TAKER_COEFFICIENT, 1e-12)
        assertEquals(0.0175, KalshiFee.MAKER_COEFFICIENT, 1e-12)
    }

    @Test
    fun publishedScheduleTableMatchesOrderLevelFee() {
        // https://kalshi.com/docs/kalshi-fee-schedule.pdf General Trading Fees Table
        assertEquals(0.01, KalshiFee.total(1, 0.01), 1e-9)
        assertEquals(0.07, KalshiFee.total(100, 0.01), 1e-9)
        assertEquals(0.01, KalshiFee.total(1, 0.05), 1e-9)
        assertEquals(0.34, KalshiFee.total(100, 0.05), 1e-9)
        assertEquals(0.02, KalshiFee.total(1, 0.20), 1e-9)
        assertEquals(1.12, KalshiFee.total(100, 0.20), 1e-9)
        assertEquals(0.02, KalshiFee.total(1, 0.50), 1e-9)
        assertEquals(1.75, KalshiFee.total(100, 0.50), 1e-9)
        assertEquals(0.01, KalshiFee.total(1, 0.99), 1e-9)
        assertEquals(0.07, KalshiFee.total(100, 0.99), 1e-9)
    }

    @Test
    fun feeRoundingDocExample() {
        // docs.kalshi.com/getting_started/fee_rounding FCM-cleared fill:
        // model $0.00363825 → trade fee ceil_6dp = $0.003639
        assertEquals(0.003639, KalshiFee.ceil6dp(0.00363825), 1e-12)
        // revenue −$0.055, aligned floor_cent(−0.055 − 0.003639) = −$0.06
        // order fee = $0.06 − $0.055 = $0.005
        val trade = KalshiFee.ceil6dp(0.00363825)
        val debit = KalshiFee.ceilCent(0.055 + trade)
        assertEquals(0.06, debit, 1e-12)
        assertEquals(0.005, debit - 0.055, 1e-12)
    }

    @Test
    fun winTargetDefaultsToFiftyOn() {
        assertFalse(SignalConstants.DEFAULT_WIN_TARGET_ENABLED)
        assertEquals(50.0, SignalConstants.DEFAULT_WIN_TARGET_USD, 1e-9)
        assertEquals(10.0, SignalConstants.LIVE_ALL_IN_CAP_USD, 1e-9)
        assertEquals(0.0, SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD, 1e-9)
        assertEquals(0.20, SignalConstants.DEFAULT_LONG_SHOT_MAX_ASK, 1e-9)
        val defaults = SignalSettings()
        assertFalse(defaults.winTargetEnabled)
        assertEquals(0.0, defaults.minProfitIfWinUsd, 1e-9)
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
        assertEquals(0.0, ticket.winTargetUsd!!, 1e-9)
        assertEquals(0.20, ticket.impliedChance!!, 1e-9)
        assertEquals(0.40, ticket.modelChance!!, 1e-9)
        assertTrue(ticket.canApprove)
        assertTrue(ticket.gateNote!!.contains("Long-shot"))
        assertTrue(ticket.gateNote!!.contains("Approve still required"))
        assertFalse(ticket.gateNote!!.contains("$1 → $5") || ticket.gateNote!!.contains("$1→$5"))
        assertEquals(LiveOrderSizer.size(0.20).count, ticket.contracts)
        assertTrue(ticket.stakeUsd in 9.0..10.0 + 1e-6)
        assertTrue((ticket.profitIfWinUsd ?: 0.0) + 1e-6 >= 10.0)
        assertTrue(ticket.winTargetCapped)
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
        assertTrue(ticket.stakeUsd in 9.0..10.0 + 1e-6)
        assertTrue((ticket.profitIfWinUsd ?: 0.0) + 1e-6 >= 10.0)
        assertEquals(0.0, ticket.winTargetUsd!!, 1e-9)
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
        assertEquals(0.0, hunter.winTargetUsd!!, 1e-9)
        assertTrue(hunter.stakeUsd in 9.0..10.0 + 1e-6)
        assertTrue((hunter.profitIfWinUsd ?: 0.0) + 1e-6 >= 10.0)

        val fiveCent = sample(yesAsk = 0.05, noAsk = 0.95, aiYes = 30.0).copy(predictedSide = "YES")
        val configured = TicketBuilder.propose(fiveCent, ctx)
        assertEquals(TicketKind.CONFIGURED, configured!!.kind)
        assertEquals(0.0, configured.winTargetUsd!!, 1e-9)

        val manual = TicketBuilder.proposeManual(fiveCent, "YES", ctx)
        assertEquals(TicketKind.MANUAL, manual!!.kind)
        assertEquals(0.0, manual.winTargetUsd!!, 1e-9)
        assertTrue((manual.profitIfWinUsd ?: 0.0) + 1e-6 >= 10.0)
        assertTrue(manual.stakeUsd <= 10.0 + 1e-9)
    }

    @Test
    fun screenshotSixtyThreeCentFiveDollarManualStaysUnderFiveAndIsBelowMinProfit() {
        val market = sample(yesAsk = 0.63, noAsk = 0.37, aiYes = 55.0).copy(
            ticker = "KXETH15M-26SEP251230-30",
            predictedSide = "YES",
            yesBid = 0.55,
            noBid = 0.37
        )
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(
                ticketStakeUsd = 5.0,
                winTargetEnabled = true,
                winTargetUsd = 50.0,
                minProfitIfWinUsd = 10.0
            ),
            alertsPaused = false
        )
        val ticket = TicketBuilder.proposeManual(market, "YES", ctx)!!
        assertEquals(TicketKind.MANUAL, ticket.kind)
        assertEquals(LiveOrderSizer.size(0.63).count, ticket.contracts)
        assertTrue(ticket.stakeUsd <= 10.0 + 1e-9)
        assertFalse(ticket.belowMinProfit)
        assertTrue(ticket.canApprove)
        val enforced = LiveOrderSizer.enforce(ticket.copy(blockedReason = null))
        assertTrue(enforced.ok)
        assertTrue(enforced.allInUsd <= 10.0 + 1e-9)
    }

    @Test
    fun twentyCentProfitAfterFeesUsesBuySideCost() {
        // 5 ct @ 20¢: model 0.07×5×0.20×0.80 = 0.056 → fee $0.06
        // settlement $5.00, cost $1.00 + $0.06 = $1.06, profit $3.94
        assertEquals(0.06, KalshiFee.total(5, 0.20, 0.07), 1e-9)
        assertEquals(5.0, KalshiFee.netPayout(5, 0.20, 0.07), 1e-9)
        val profit = KalshiFee.netProfit(5, 0.20, 0.07)
        assertEquals(3.94, profit, 1e-9)
        assertEquals(5.0 - 1.06, profit, 1e-9)
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
        val book = com.dirk.kalshiodds.signal.paper.PaperBook(initial = com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0), idFactory = { "ls" }, nowMs = { 1L })
        val fill = book.manualFill(ticket)
        assertTrue(fill != null)
        assertEquals(ticket.contracts, fill!!.contracts)
        assertEquals(ticket.contracts * ticket.limitPrice, fill.stakeUsd, 1e-6)
        val debit = fill.stakeUsd + KalshiFee.total(fill.contracts, fill.limitPrice)
        assertTrue(abs(1_000.0 - debit - book.snapshot().cashUsd) < 1e-6)
        assertTrue(fill.note.contains("win-target") || fill.note.contains("Paper"))
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
