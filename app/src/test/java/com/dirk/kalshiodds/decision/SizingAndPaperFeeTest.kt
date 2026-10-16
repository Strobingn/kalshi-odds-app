package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperAutopilot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperKellySizer
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.HomeFixtures
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SizingAndPaperFeeTest {
    private val now = HomeFixtures.NOW_MS
    private val settings = SignalSettings(paperTradingEnabled = true, aiPaperAutopilotEnabled = true, paperKellyFraction = 0.5)

    @Before
    fun reset() = PaperAutopilot.resetSession()

    @Test
    fun kellyUnderFiveDollarsIsSkippedNotRoundedUp() {
        assertTrue(AutopilotMinStake.below(4.99))
        assertFalse(AutopilotMinStake.below(5.0))
        assertTrue(AutopilotMinStake.below(Double.NaN))
        assertEquals("NO BET — Kelly stake below \$5 minimum (skipped, not rounded up)", AutopilotMinStake.REASON)
    }

    @Test
    fun noLiveBalanceMeansNoBet() {
        assertFalse(LiveBalancePolicy.fresh(null, now, now))
        assertFalse(LiveBalancePolicy.fresh(0.0, now, now))
        assertFalse(LiveBalancePolicy.fresh(250.0, null, now))
        assertFalse(LiveBalancePolicy.fresh(250.0, now - LiveBalancePolicy.FRESH_MS - 1, now))
        assertTrue(LiveBalancePolicy.fresh(250.0, now - 1_000, now))
        assertEquals("NO BET — Kalshi balance unavailable", LiveBalancePolicy.REASON)
    }

    @Test
    fun paperHalfKellyDefault() {
        assertEquals(0.5, SignalSettings().paperKellyFraction, 1e-12)
    }

    @Test
    fun liveSizingScalesWithRealBalance() {
        val small = PaperKellySizer.size(winProb = 0.7, ask = 0.6, bankrollUsd = 20.0, kellyFraction = 0.5, feeRate = 0.07, depthContracts = 1000)
        val big = PaperKellySizer.size(winProb = 0.7, ask = 0.6, bankrollUsd = 2_000.0, kellyFraction = 0.5, feeRate = 0.07, depthContracts = 1000)
        assertTrue(big.allInUsd > small.allInUsd)
        assertTrue(AutopilotMinStake.below(small.allInUsd))
    }

    @Test
    fun everyPaperFillPaysTheRoundedUpFee() {
        val ids = AtomicInteger()
        val book = PaperBook(idFactory = { "f${ids.incrementAndGet()}" }, nowMs = { now })
        val market = HomeFixtures.market(
            ticker = "KXBTC15M-25SEP181700-50", seriesLabel = "Bitcoin", yesAsk = 0.20, aiYes = 80.0,
            predicted = "YES", closeMs = now + 372_000L, floorStrike = 67_000.0, spotUsd = 67_240.0, spotDelta = 240.0
        )
        PaperAutopilot.consider(book, market, settings, now, yesDepth = 80, noDepth = 80)
        val fill = PaperAutopilot.consider(book, market, settings, now, yesDepth = 80, noDepth = 80)
        assertNotNull(fill)
        val f = fill!!
        assertTrue(f.contracts <= 80)
        assertEquals(KalshiFee.totalCost(f.contracts, f.limitPrice, settings.feeRate), f.stakeUsd, 1e-9)
        assertTrue(f.stakeUsd > f.contracts * f.limitPrice)
    }

    @Test
    fun gatedNoBetVerdictBlocksPaperFillWithItsReason() {
        val book = PaperBook(idFactory = { "g" }, nowMs = { now })
        val market = HomeFixtures.market(
            ticker = "KXBTC15M-25SEP181700-50", seriesLabel = "Bitcoin", yesAsk = 0.20, aiYes = 80.0,
            predicted = "YES", closeMs = now + 372_000L, floorStrike = 67_000.0, spotUsd = 67_240.0, spotDelta = 240.0
        )
        val a = DecisionPipeline.assess(
            DecisionPipeline.Input(
                ticker = market.ticker, series = "KXBTC15M", nowMs = now, closeTimeMs = market.closeTimeEpochMs,
                rawModelYes = 0.8, marketYes = 0.2, yesAsk = 0.20, noAsk = 0.81, yesBid = 0.19, noBid = 0.80,
                yesDepth = 80, noDepth = 80, bookAgeMs = 500L, spot = 67_240.0, strike = 67_000.0, volPerSec = null,
                settlementSource = "CF BRTI", settlementFresh = true
            )
        )
        assertFalse(a.allow)
        val d = PaperAutopilot.evaluate(market, settings, book.snapshot(), now, yesDepth = 80, noDepth = 80, assessment = a)
        assertTrue(d.skip)
        assertEquals(a.reason, d.reason)
        assertNull(PaperAutopilot.tick(book, market, settings, now, yesDepth = 80, noDepth = 80, assessment = a).fill)
    }
}
