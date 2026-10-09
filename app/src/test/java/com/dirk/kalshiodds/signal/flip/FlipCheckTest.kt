package com.dirk.kalshiodds.signal.flip

import com.dirk.kalshiodds.signal.lastminute.LastMinuteStrategy
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class FlipCheckTest {

    /** 0.3.39: last-minute play is retired; these tests cover its dormant code. */
    @get:org.junit.Rule
    val legacyLastMinute = com.dirk.kalshiodds.signal.lastminute.LegacyLastMinuteRule()

    @Test
    fun screenshotLotteryIsNoBetAndHasNoTicket() {
        val market = HomeFixtures.deadWindowLotteryBtc()
        val now = HomeFixtures.NOW_MS
        val verdict = FlipCheck.evaluateMarket(market, now)
        assertNotNull(verdict)
        assertTrue(verdict!!.distanceUsd in 12.0..13.0 || verdict.distanceUsd in 28.0..30.0)
        assertEquals(12.0, verdict.secondsLeft, 1e-6)
        assertTrue(verdict.flipProb < FlipCheck.MIN_FLIP_PROB)
        assertTrue(verdict.noBetLine.contains("Flip chance"))
        assertTrue(verdict.noBetLine.contains("12s"))
        assertTrue(verdict.typicalMoveUsd in 3.0..6.0)
        assertFalse(verdict.eligible("NO", 0.001, 0.04))
        assertFalse(verdict.eligible("YES", 0.001, 0.96))

        val call = BetCall.decide(market, HomeFixtures.settings(true), now)
        assertEquals(BetCall.Headline.NO_BET, call.headline)
        assertFalse(call.isActionable)
        assertNull(call.ticket)
        assertTrue(call.noBetReason!!.startsWith("Flip chance"))
        assertNull(TicketBuilder.proposeLastMinute(market, TicketBuilder.Context(HomeFixtures.settings(true), false, nowMs = now)))

        val tiles = HomeCopy.tileAiPercents(market)
        assertTrue((tiles.upPct ?: 0) + (tiles.downPct ?: 0) == 100)
        assertTrue((tiles.downPct ?: 100) <= 4)
    }

    @Test
    fun genuineCloseWindowStaysEligible() {
        val geo = FlipCheck.Geometry(
            spotUsd = 84_550.0,
            targetUsd = 84_547.0,
            secondsLeft = 40.0,
            sigmaPerSecUsd = FlipCheck.SIGMA_FLOOR_USD_PER_SEC
        )
        val verdict = FlipCheck.evaluate(geo, rawPUp = 0.62)
        assertTrue(verdict.flipProb >= FlipCheck.MIN_FLIP_PROB)
        assertTrue(verdict.eligible("YES", 0.30, 0.62))
        assertTrue(FlipCheck.beatsAllIn(0.62, 0.30))

        val market = HomeFixtures.closeWindowEligibleBtc()
        val call = BetCall.decide(market, HomeFixtures.settings(true), HomeFixtures.NOW_MS)
        if (market.lastMinute?.fired != null) {
            assertEquals(BetCall.Headline.BET_UP, call.headline)
            assertTrue(call.isActionable)
            assertNotNull(call.ticket)
            assertEquals(0.30, call.ticket!!.limitPrice, 1e-9)
        } else {
            assertTrue(verdict.eligible("YES", 0.30, verdict.cappedPUp))
        }
    }

    @Test
    fun feesAreIncludedInEligibility() {
        val ask = 0.30
        val fee = KalshiFee.perContract(ask, 0.07, 10.0)
        assertTrue(fee > 0.0)
        val justBelow = ask + fee + FlipCheck.EDGE_MARGIN - 0.001
        val justAbove = ask + fee + FlipCheck.EDGE_MARGIN + 0.001
        assertFalse(FlipCheck.beatsAllIn(justBelow, ask))
        assertTrue(FlipCheck.beatsAllIn(justAbove, ask))
        val geo = FlipCheck.Geometry(84_550.0, 84_547.0, 40.0, FlipCheck.SIGMA_FLOOR_USD_PER_SEC)
        val v = FlipCheck.evaluate(geo, 0.99)
        assertFalse(v.eligible("YES", ask, justBelow))
        assertTrue(v.eligible("YES", ask, 0.99))
    }

    @Test
    fun finalMinuteAveragingShrinksVariance() {
        val sigma = FlipCheck.SIGMA_FLOOR_USD_PER_SEC
        val openVar = FlipCheck.settlementVarianceUsd(120.0, sigma)
        val lateVar = FlipCheck.settlementVarianceUsd(12.0, sigma)
        assertTrue(openVar > lateVar)
        assertTrue(lateVar > 0.0)
        val geoLate = FlipCheck.Geometry(
            spotUsd = 84_559.28,
            targetUsd = 84_547.0,
            secondsLeft = 12.0,
            sigmaPerSecUsd = sigma,
            observedAvgUsd = 84_559.28,
            observedSeconds = 48.0
        )
        val mean = FlipCheck.settlementMeanUsd(geoLate)
        assertEquals(84_559.28, mean, 1e-6)
        val v = FlipCheck.evaluate(geoLate, 0.96)
        assertTrue(v.flipProb < 0.01)
        assertTrue(v.cappedPDown < 0.01)
        assertTrue(v.cappedPUp > 0.99)
    }

    @Test
    fun liveAskMatchesTileNotLatchedFire() {
        val fired = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-ASK",
                tauSec = 45,
                x = 0.0006,
                obsMean = 0.0004,
                sigS = 5e-5,
                upAsk = 0.03,
                downAsk = 0.97,
                nowMs = HomeFixtures.NOW_MS,
                spotUsd = 84_180.0,
                strikeUsd = 84_144.0
            )
        ).fired
        assertNotNull(fired)
        val market = HomeFixtures.actionableBtc().copy(
            yesAsk = 0.001,
            noAsk = 0.999,
            yesBid = 0.999,
            lastMinute = HomeFixtures.lastMinuteFiredBtc().lastMinute?.copy(fired = fired?.copy(ask = 0.007))
        )
        val live = TicketBuilder.liveAsk(market, "YES")
        assertEquals(0.001, live!!, 1e-9)
        assertTrue(fired!!.ask >= 0.03 - 1e-9 || fired.ask == 0.007)
    }

    @Test
    fun sigmaFloorAndRobustTicks() {
        assertEquals(FlipCheck.SIGMA_FLOOR_USD_PER_SEC, FlipCheck.sigmaFromLogVol(null, null), 1e-9)
        val now = 1_700_000_000_000L
        val ticks = (0 until 40).map { i ->
            val t = now - (40 - i) * 15_000L
            t to (84_500.0 + (i % 3 - 1) * 2.0)
        }
        val sig = FlipCheck.sigmaFromSpotTicks(ticks, now)
        assertTrue(sig >= FlipCheck.SIGMA_FLOOR_USD_PER_SEC)
        assertEquals(4.0, FlipCheck.typicalMoveUsd(FlipCheck.SIGMA_FLOOR_USD_PER_SEC, 12.0), 0.2)
    }

    @Test
    fun lastMinuteDoesNotFireScreenshotLottery() {
        val input = LastMinuteStrategy.Inputs(
            ticker = "KXBTC15M-LOT",
            tauSec = 12,
            x = ln(84_559.28 / 84_547.0),
            obsMean = ln(84_559.28 / 84_547.0),
            sigS = 5e-5,
            upAsk = 0.999,
            downAsk = 0.001,
            nowMs = HomeFixtures.NOW_MS,
            spotUsd = 84_559.28,
            strikeUsd = 84_547.0
        )
        val snap = LastMinuteStrategy.evaluate(input)
        assertNull(snap.fired)
        assertFalse(snap.down?.qualifies == true)
        assertTrue((snap.flip?.flipProb ?: 1.0) < FlipCheck.MIN_FLIP_PROB)
    }
}
