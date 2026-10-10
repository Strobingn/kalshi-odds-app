package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-strategy entry/exit rules for MOMENTUM_SNIPER and EXTREME_REVERSAL on
 * synthetic feature snapshots. DIP_HUNT rules are covered by
 * [ScalpDecisionTest].
 */
class ScalpStrategyTest {

    private val momentum = ScalpDecision(
        strategy = ScalpStrategy.MOMENTUM_SNIPER,
        takeProfitPp = 3.0,
        stopLossPp = 5.0,
        maxHoldMs = 180_000L
    )

    private val reversal = ScalpDecision(
        strategy = ScalpStrategy.EXTREME_REVERSAL,
        takeProfitPp = 5.0,
        stopLossPp = 3.0,
        maxHoldMs = 480_000L
    )

    /** Live accelerating up-move. */
    private fun breakingOut(): ScalpMath.ScalpFeatures = ScalpMath.ScalpFeatures(
        lastMidPp = 55.0,
        shortEmaPp = 53.0,
        longEmaPp = 50.0,
        velocityPpPerSec = 0.8,
        accelerationPpPerSec2 = 0.4,
        windowMinPp = 48.0,
        windowMaxPp = 55.0,
        dropFromShortEmaPp = -2.0,
        maxDropFromShortEmaPp = 0.0,
        dropFromWindowMaxPp = 0.0,
        sampleCount = 20,
        windowSpanMs = 19_000L
    )

    /** Crushed longshot snapping back up off the floor. */
    private fun snappingBack(): ScalpMath.ScalpFeatures = ScalpMath.ScalpFeatures(
        lastMidPp = 6.0,
        shortEmaPp = 5.2,
        longEmaPp = 4.0,
        velocityPpPerSec = 0.5,
        accelerationPpPerSec2 = 0.2,
        windowMinPp = 2.0,
        windowMaxPp = 6.5,
        dropFromShortEmaPp = -0.8,
        maxDropFromShortEmaPp = 0.0,
        dropFromWindowMaxPp = 0.5,
        sampleCount = 20,
        windowSpanMs = 19_000L
    )

    // ---- MOMENTUM_SNIPER ----------------------------------------------------

    @Test
    fun momentumEntersOnAcceleratingUpMove() {
        val d = momentum.decide(
            features = breakingOut(),
            bestBidCents = 54,
            bestAskCents = 55,
            imbalance = 0.2,
            position = null,
            nowMs = 100_000L,
            closeTimeEpochMs = 100_000L + 900_000L // 15 min of room
        )
        assertTrue(d is ScalpDecision.ScalpDecision.Enter)
        assertEquals(55, (d as ScalpDecision.ScalpDecision.Enter).entryPriceCents)
    }

    @Test
    fun momentumEntersInTheFinalSecondsOfTheWindow() {
        // Full-window experiment: the old >60s-room gate is gone — momentum
        // may enter anywhere, the WINDOW_CLOSE force-exit is the backstop.
        val d = momentum.decide(
            features = breakingOut(),
            bestBidCents = 54,
            bestAskCents = 55,
            imbalance = 0.2,
            position = null,
            nowMs = 100_000L,
            closeTimeEpochMs = 100_000L + 30_000L // only 30s of room
        )
        assertTrue(d is ScalpDecision.ScalpDecision.Enter)
    }

    @Test
    fun momentumRefusesEntryWithoutAcceleration() {
        val flatAccel = breakingOut().copy(accelerationPpPerSec2 = 0.0)
        val d = momentum.decide(flatAccel, 54, 55, 0.2, null, 100_000L, 900_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, d)
    }

    @Test
    fun momentumRefusesEntryBelowVelocityThreshold() {
        val slow = breakingOut().copy(velocityPpPerSec = 0.1)
        val d = momentum.decide(slow, 54, 55, 0.2, null, 100_000L, 900_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, d)
    }

    @Test
    fun momentumExitsWhenVelocityFadesBelowZero() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 55, entryTimeMs = 0L)
        val fading = breakingOut().copy(velocityPpPerSec = -0.2)
        val d = momentum.decide(fading, bestBidCents = 56, bestAskCents = 57, position = pos, nowMs = 30_000L)
        val exit = d as ScalpDecision.ScalpDecision.Exit
        assertEquals(ExitReason.MOMENTUM_FADE, exit.reason)
        assertEquals(56, exit.exitPriceCents)
    }

    @Test
    fun momentumTargetTakesPriorityOverFade() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 55, entryTimeMs = 0L)
        val fading = breakingOut().copy(velocityPpPerSec = -0.2)
        // Bid +3 (target) on the same tick the move fades — target wins.
        val d = momentum.decide(fading, bestBidCents = 58, bestAskCents = 59, position = pos, nowMs = 30_000L)
        assertEquals(ExitReason.TARGET, (d as ScalpDecision.ScalpDecision.Exit).reason)
    }

    @Test
    fun momentumStopAndTimeout() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 55, entryTimeMs = 0L)
        val stopped = momentum.decide(
            breakingOut(), bestBidCents = 50, bestAskCents = 51, position = pos, nowMs = 30_000L
        )
        assertEquals(ExitReason.STOP, (stopped as ScalpDecision.ScalpDecision.Exit).reason)

        val pos2 = ScalpDecision.OpenPosition(entryPriceCents = 55, entryTimeMs = 0L)
        val timedOut = momentum.decide(
            breakingOut(), bestBidCents = 56, bestAskCents = 57, position = pos2, nowMs = 181_000L
        )
        assertEquals(ExitReason.TIMEOUT, (timedOut as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- EXTREME_REVERSAL ---------------------------------------------------

    @Test
    fun reversalEntersAtFloorExtremeWithUpwardFlip() {
        val d = reversal.decide(
            features = snappingBack(),
            bestBidCents = 5,
            bestAskCents = 6,
            imbalance = -0.2,
            position = null,
            nowMs = 100_000L
        )
        assertTrue(d is ScalpDecision.ScalpDecision.Enter)
        assertEquals(6, (d as ScalpDecision.ScalpDecision.Enter).entryPriceCents)
    }

    @Test
    fun reversalEntersAtCeilingExtremeAfterPullback() {
        val atCeiling = snappingBack().copy(
            lastMidPp = 93.0,
            shortEmaPp = 92.4,
            longEmaPp = 91.0,
            velocityPpPerSec = 0.4,
            accelerationPpPerSec2 = 0.1
        )
        val d = reversal.decide(atCeiling, 92, 93, 0.0, null, 100_000L)
        assertTrue(d is ScalpDecision.ScalpDecision.Enter)
    }

    @Test
    fun reversalDoesNotEnterBeforeTheFlip() {
        val stillFalling = snappingBack().copy(velocityPpPerSec = -0.4, accelerationPpPerSec2 = -0.1)
        val d = reversal.decide(stillFalling, 5, 6, null, null, 100_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, d)
    }

    @Test
    fun reversalIgnoresMidRangePrices() {
        val midRange = snappingBack().copy(lastMidPp = 45.0, velocityPpPerSec = 0.5)
        val d = reversal.decide(midRange, 44, 45, null, null, 100_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, d)
    }

    @Test
    fun reversalExitsAtFiveCentBounce() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 6, entryTimeMs = 0L)
        val bounced = snappingBack().copy(lastMidPp = 11.0)
        val d = reversal.decide(bounced, bestBidCents = 11, bestAskCents = 12, position = pos, nowMs = 60_000L)
        assertEquals(ExitReason.TARGET, (d as ScalpDecision.ScalpDecision.Exit).reason)
        assertEquals(11, d.exitPriceCents)
    }

    @Test
    fun reversalExitsWhenMidIsBackAtFifty() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 6, entryTimeMs = 0L)
        // Bid only +2 (below the +5 target) but mid is back at 50±3.
        val home = snappingBack().copy(lastMidPp = 49.0)
        val d = reversal.decide(home, bestBidCents = 8, bestAskCents = 9, position = pos, nowMs = 300_000L)
        assertEquals(ExitReason.TARGET, (d as ScalpDecision.ScalpDecision.Exit).reason)
    }

    @Test
    fun reversalStopAndTimeout() {
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 6, entryTimeMs = 0L)
        val stopped = reversal.decide(snappingBack(), bestBidCents = 3, bestAskCents = 4, position = pos, nowMs = 60_000L)
        assertEquals(ExitReason.STOP, (stopped as ScalpDecision.ScalpDecision.Exit).reason)

        val pos2 = ScalpDecision.OpenPosition(entryPriceCents = 6, entryTimeMs = 0L)
        val timedOut = reversal.decide(
            snappingBack(), bestBidCents = 7, bestAskCents = 8, position = pos2, nowMs = 481_000L
        )
        assertEquals(ExitReason.TIMEOUT, (timedOut as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- swarm strategies (bitcoin-swarm roster) ----------------------------

    /** Neutral feature block — per-test copies tweak the fields that matter. */
    private fun base() = ScalpMath.ScalpFeatures(
        lastMidPp = 50.0,
        prevMidPp = 50.0,
        shortEmaPp = 50.0,
        longEmaPp = 50.0,
        velocityPpPerSec = 0.0,
        accelerationPpPerSec2 = 0.0,
        windowMinPp = 47.0,
        windowMaxPp = 53.0,
        vwapPp = 50.0,
        midStdPp = 1.0,
        windowVelocityPpPerSec = 0.0,
        dropFromShortEmaPp = 0.0,
        maxDropFromShortEmaPp = 0.0,
        dropFromWindowMaxPp = 0.0,
        sampleCount = 30,
        windowSpanMs = 29_000L
    )

    private fun decider(s: ScalpStrategy) = ScalpDecision(
        strategy = s,
        takeProfitPp = s.defaultTakeProfitPp,
        stopLossPp = s.defaultStopLossPp,
        maxHoldMs = s.defaultMaxHoldMs
    )

    // ---- VWAP_REVERT --------------------------------------------------------

    @Test
    fun vwapRevertEntersBelowVwapAndExitsOnTouch() {
        val d = decider(ScalpStrategy.VWAP_REVERT)
        val stretched = base().copy(lastMidPp = 45.0, velocityPpPerSec = -0.1, accelerationPpPerSec2 = 0.2)
        val enter = d.decide(stretched, 44, 46, 0.0, null, 100_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(46, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // Only 2¢ below the VWAP — not stretched enough.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(stretched.copy(lastMidPp = 48.0), 47, 49, 0.0, null, 100_000L)
        )

        // Exit on VWAP touch even below the +3 TP: entry 47.5, bid 50 = VWAP.
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 47, entryTimeMs = 0L)
        val touch = d.decide(base().copy(lastMidPp = 50.0), 50, 51, 0.0, pos, 60_000L)
        assertEquals(ExitReason.TARGET, (touch as ScalpDecision.ScalpDecision.Exit).reason)
        assertEquals(50, touch.exitPriceCents)
    }

    // ---- FIFTY_FLIP ---------------------------------------------------------

    @Test
    fun fiftyFlipEntersOnSupportedUpwardCross() {
        val d = decider(ScalpStrategy.FIFTY_FLIP)
        val crossing = base().copy(lastMidPp = 50.5, prevMidPp = 48.5, velocityPpPerSec = 0.6)
        val enter = d.decide(crossing, 50, 52, 0.3, null, 100_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(52, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // No cross (already above 50).
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(crossing.copy(prevMidPp = 51.0), 51, 53, 0.3, null, 100_000L)
        )
        // Cross without book support.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(crossing, 50, 52, -0.1, null, 100_000L)
        )
        // Cross too slow.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(crossing.copy(velocityPpPerSec = 0.1), 50, 52, 0.3, null, 100_000L)
        )

        // Tight TP / SL / 90s hold.
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 52, entryTimeMs = 0L)
        val tp = d.decide(base(), 55, 56, 0.3, pos, 30_000L)
        assertEquals(ExitReason.TARGET, (tp as ScalpDecision.ScalpDecision.Exit).reason)
        val sl = d.decide(base(), 49, 50, 0.3, pos, 30_000L)
        assertEquals(ExitReason.STOP, (sl as ScalpDecision.ScalpDecision.Exit).reason)
        val to = d.decide(base(), 53, 54, 0.3, pos, 91_000L)
        assertEquals(ExitReason.TIMEOUT, (to as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- BOOK_IMBALANCE -----------------------------------------------------

    @Test
    fun bookImbalanceEntersWithDepthAndExitsOnFlip() {
        val d = decider(ScalpStrategy.BOOK_IMBALANCE)
        val calm = base().copy(velocityPpPerSec = 0.2)
        val enter = d.decide(calm, 49, 51, 0.7, null, 100_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(51, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // Not bid-heavy enough.
        assertEquals(ScalpDecision.ScalpDecision.Hold, d.decide(calm, 49, 51, 0.3, null, 100_000L))
        // Runaway tape overrides depth.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(calm.copy(velocityPpPerSec = 1.5), 49, 51, 0.7, null, 100_000L)
        )

        // Imbalance flips → signal fade exit (before any TP/SL).
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 51, entryTimeMs = 0L)
        val flip = d.decide(calm, 50, 52, -0.1, pos, 30_000L)
        assertEquals(ExitReason.MOMENTUM_FADE, (flip as ScalpDecision.ScalpDecision.Exit).reason)
        // Depth persists, TP/SL unchanged.
        val tp = d.decide(calm, 54, 55, 0.7, pos, 30_000L)
        assertEquals(ExitReason.TARGET, (tp as ScalpDecision.ScalpDecision.Exit).reason)
        val sl = d.decide(calm, 47, 48, 0.7, pos, 30_000L)
        assertEquals(ExitReason.STOP, (sl as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- PULSE_SNIPE --------------------------------------------------------

    @Test
    fun pulseSnipeFadesCancelBurstWithQuoteResupport() {
        val d = decider(ScalpStrategy.PULSE_SNIPE)
        val burst = com.dirk.kalshiodds.signal.engine.LocalOrderBook.Pulse(
            cancelSpike = -0.8, quotePull = 0.4
        )
        val dip = base().copy(velocityPpPerSec = -0.3)
        val enter = d.decide(dip, 49, 51, 0.0, null, 100_000L, pulse = burst)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(51, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // No pulse data, not extreme, or no re-support → hold.
        assertEquals(ScalpDecision.ScalpDecision.Hold, d.decide(dip, 49, 51, 0.0, null, 100_000L))
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(dip, 49, 51, 0.0, null, 100_000L, pulse = burst.copy(cancelSpike = -0.4))
        )
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(dip, 49, 51, 0.0, null, 100_000L, pulse = burst.copy(quotePull = -0.1))
        )
        // Free-fall overrides the fade.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(dip.copy(velocityPpPerSec = -1.0), 49, 51, 0.0, null, 100_000L, pulse = burst)
        )

        // Cancel EMA recovers → exit fast.
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 51, entryTimeMs = 0L)
        val recovered = d.decide(base(), 50, 52, 0.0, pos, 20_000L, pulse = burst.copy(cancelSpike = 0.0))
        assertEquals(ExitReason.MOMENTUM_FADE, (recovered as ScalpDecision.ScalpDecision.Exit).reason)
        val tp = d.decide(base(), 53, 54, 0.0, pos, 20_000L, pulse = burst)
        assertEquals(ExitReason.TARGET, (tp as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- SPOT_LEAD ----------------------------------------------------------

    @Test
    fun spotLeadFollowsSpotImpulseAndExitsOnDecay() {
        val d = decider(ScalpStrategy.SPOT_LEAD)
        val hot = ScalpSpot(return15s = 0.001)
        val enter = d.decide(base(), 49, 51, 0.0, null, 100_000L, spot = hot)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(51, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // No spot data / impulse too small / Kalshi already dumping.
        assertEquals(ScalpDecision.ScalpDecision.Hold, d.decide(base(), 49, 51, 0.0, null, 100_000L))
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(base(), 49, 51, 0.0, null, 100_000L, spot = ScalpSpot(return15s = 0.0001))
        )
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(base().copy(velocityPpPerSec = -0.5), 49, 51, 0.0, null, 100_000L, spot = hot)
        )

        // Impulse decays below half the entry threshold → fade exit.
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 51, entryTimeMs = 0L)
        val decayed = d.decide(base(), 50, 52, 0.0, pos, 30_000L, spot = ScalpSpot(return15s = 0.0001))
        assertEquals(ExitReason.MOMENTUM_FADE, (decayed as ScalpDecision.ScalpDecision.Exit).reason)
        val stillHot = d.decide(base(), 50, 52, 0.0, pos, 30_000L, spot = hot)
        assertTrue(stillHot is ScalpDecision.ScalpDecision.Hold)
    }

    // ---- RANGE_FADE ---------------------------------------------------------

    @Test
    fun rangeFadeFadesExtensionsOnlyInQuietRegimes() {
        val d = decider(ScalpStrategy.RANGE_FADE)
        // Quiet: std 1.0, range 47..53 (mid 50), last 46.5 = 3.5 extension.
        val quiet = base().copy(lastMidPp = 46.5)
        val enter = d.decide(quiet, 46, 48, 0.0, null, 100_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(48, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // Loud regime: never fade a trend.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(quiet.copy(midStdPp = 3.0), 46, 48, 0.0, null, 100_000L)
        )
        // Extension too small.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(quiet.copy(lastMidPp = 48.0), 47, 49, 0.0, null, 100_000L)
        )

        // Exit when the bid reaches the range midpoint.
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 48, entryTimeMs = 0L)
        val home = d.decide(base().copy(lastMidPp = 50.0), 50, 51, 0.0, pos, 60_000L)
        assertEquals(ExitReason.TARGET, (home as ScalpDecision.ScalpDecision.Exit).reason)
        assertEquals(50, home.exitPriceCents)
        // Tight 3¢ stop.
        val sl = d.decide(quiet, 45, 46, 0.0, pos, 60_000L)
        assertEquals(ExitReason.STOP, (sl as ScalpDecision.ScalpDecision.Exit).reason)
    }

    // ---- LATE_DRIFT ---------------------------------------------------------

    @Test
    fun lateDriftTradesFinalNinetySecondsWithTheTrend() {
        val d = decider(ScalpStrategy.LATE_DRIFT)
        val now = 100_000L
        // 60s of window left, mid 60, whole-window velocity positive.
        val setup = base().copy(lastMidPp = 60.0, windowVelocityPpPerSec = 0.1)
        val enter = d.decide(setup, 59, 61, 0.0, null, now, 160_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(61, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // Too early (>90s out).
        assertEquals(ScalpDecision.ScalpDecision.Hold, d.decide(setup, 59, 61, 0.0, null, now, 300_000L))
        // NO side is not actionable (mid < 58).
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(setup.copy(lastMidPp = 55.0), 54, 56, 0.0, null, now, 160_000L)
        )
        // Against the trend — never.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(setup.copy(windowVelocityPpPerSec = -0.1), 59, 61, 0.0, null, now, 160_000L)
        )
        // Inside the window-close buffer — entries would be instant force-exits.
        assertEquals(ScalpDecision.ScalpDecision.Hold, d.decide(setup, 59, 61, 0.0, null, now, 104_000L))
    }

    // ---- OPEN_DRIVE ---------------------------------------------------------

    @Test
    fun openDriveRidesTheOpeningMoveWithSpotAgreement() {
        val d = decider(ScalpStrategy.OPEN_DRIVE)
        // 15s into the window, sustained +0.5¢/s drive, flat accel.
        val drive = base().copy(windowSpanMs = 15_000L, windowVelocityPpPerSec = 0.5)
        val enter = d.decide(drive, 54, 56, 0.0, null, 100_000L)
        assertTrue(enter is ScalpDecision.ScalpDecision.Enter)
        assertEquals(56, (enter as ScalpDecision.ScalpDecision.Enter).entryPriceCents)

        // Drive not established yet (<10s).
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(drive.copy(windowSpanMs = 5_000L), 54, 56, 0.0, null, 100_000L)
        )
        // Past the first minute.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(drive.copy(windowSpanMs = 70_000L), 54, 56, 0.0, null, 100_000L)
        )
        // Too slow to be a drive.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(drive.copy(windowVelocityPpPerSec = 0.1), 54, 56, 0.0, null, 100_000L)
        )
        // Spot disagrees.
        assertEquals(
            ScalpDecision.ScalpDecision.Hold,
            d.decide(drive, 54, 56, 0.0, null, 100_000L, spot = ScalpSpot(return15s = -0.001))
        )
    }
}
