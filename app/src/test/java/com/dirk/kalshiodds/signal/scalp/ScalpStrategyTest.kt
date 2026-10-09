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
    fun momentumRefusesEntryInsideLastMinuteOfWindow() {
        val d = momentum.decide(
            features = breakingOut(),
            bestBidCents = 54,
            bestAskCents = 55,
            imbalance = 0.2,
            position = null,
            nowMs = 100_000L,
            closeTimeEpochMs = 100_000L + 30_000L // only 30s of room
        )
        assertEquals(ScalpDecision.ScalpDecision.Hold, d)
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
}
