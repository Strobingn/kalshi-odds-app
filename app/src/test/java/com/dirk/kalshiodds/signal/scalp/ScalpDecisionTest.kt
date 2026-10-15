package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state-machine tests with hand-built feature snapshots — the same
 * shape [ScalpMath] produces on a stabilizing V vs a falling knife.
 */
class ScalpDecisionTest {

    private fun decider(
        dipMinDropPp: Double = 2.0,
        takeProfitPp: Double = 3.0,
        stopLossPp: Double = 4.0,
        maxHoldMs: Long = 600_000L,
        maxSpreadCents: Int = 3
    ) = ScalpDecision(
        dipMinDropPp = dipMinDropPp,
        takeProfitPp = takeProfitPp,
        stopLossPp = stopLossPp,
        maxHoldMs = maxHoldMs,
        maxSpreadCents = maxSpreadCents
    )

    /** Stabilized dip: ran 3.5¢ below the fast EMA, still 4¢ off the high. */
    private fun stabilizedDip(): ScalpMath.ScalpFeatures = ScalpMath.ScalpFeatures(
        lastMidPp = 44.0,
        shortEmaPp = 44.3,
        longEmaPp = 48.5,
        velocityPpPerSec = -0.01,
        accelerationPpPerSec2 = 0.15,
        windowMinPp = 43.6,
        windowMaxPp = 48.0,
        dropFromShortEmaPp = 0.3,
        maxDropFromShortEmaPp = 3.5,
        dropFromWindowMaxPp = 4.0,
        sampleCount = 30,
        windowSpanMs = 25_000L
    )

    /** Falling knife: same dip depth but still accelerating down hard. */
    private fun fallingKnife(): ScalpMath.ScalpFeatures = stabilizedDip().copy(
        velocityPpPerSec = -1.2,
        accelerationPpPerSec2 = -0.5
    )

    @Test
    fun entersOnDipThatStabilizes() {
        val d = decider()
        val decision = d.decide(
            features = stabilizedDip(),
            bestBidCents = 44,
            bestAskCents = 45,
            imbalance = 0.1,
            position = null,
            nowMs = 30_000L
        )
        assertTrue(decision is ScalpDecision.ScalpDecision.Enter)
        assertEquals(45, (decision as ScalpDecision.ScalpDecision.Enter).entryPriceCents)
    }

    @Test
    fun doesNotEnterOnFallingKnife() {
        val d = decider()
        val decision = d.decide(
            features = fallingKnife(),
            bestBidCents = 44,
            bestAskCents = 45,
            imbalance = 0.1,
            position = null,
            nowMs = 30_000L
        )
        assertEquals(ScalpDecision.ScalpDecision.Hold, decision)
    }

    @Test
    fun doesNotEnterWhenDipTooShallow() {
        val d = decider(dipMinDropPp = 2.0)
        val shallow = stabilizedDip().copy(
            maxDropFromShortEmaPp = 1.5,
            dropFromWindowMaxPp = 1.8
        )
        val decision = d.decide(shallow, 44, 45, 0.1, null, 30_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, decision)
    }

    @Test
    fun doesNotEnterWhenSpreadTooWide() {
        val d = decider(maxSpreadCents = 3)
        val decision = d.decide(stabilizedDip(), 42, 47, 0.1, null, 30_000L) // 5¢ spread
        assertEquals(ScalpDecision.ScalpDecision.Hold, decision)
    }

    @Test
    fun doesNotEnterWhenImbalanceStronglyAdverse() {
        val d = decider()
        val decision = d.decide(stabilizedDip(), 44, 45, -0.9, null, 30_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, decision)
    }

    @Test
    fun entersWhenImbalanceMildlyAdverseOrUnknown() {
        val d = decider()
        val mild = d.decide(stabilizedDip(), 44, 45, -0.3, null, 30_000L)
        assertTrue(mild is ScalpDecision.ScalpDecision.Enter)
        val unknown = d.decide(stabilizedDip(), 44, 45, null, null, 30_000L)
        assertTrue(unknown is ScalpDecision.ScalpDecision.Enter)
    }

    @Test
    fun exitsOnTargetWhenBidBouncesEnough() {
        val d = decider(takeProfitPp = 3.0)
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 45, entryTimeMs = 0L)
        val decision = d.decide(stabilizedDip(), bestBidCents = 48, bestAskCents = 49, position = pos, nowMs = 5_000L)
        val exit = decision as ScalpDecision.ScalpDecision.Exit
        assertEquals(ExitReason.TARGET, exit.reason)
        assertEquals(48, exit.exitPriceCents)
    }

    @Test
    fun exitsOnStopWhenBidKeepsFalling() {
        val d = decider(stopLossPp = 4.0)
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 45, entryTimeMs = 0L)
        val decision = d.decide(stabilizedDip(), bestBidCents = 41, bestAskCents = 42, position = pos, nowMs = 5_000L)
        val exit = decision as ScalpDecision.ScalpDecision.Exit
        assertEquals(ExitReason.STOP, exit.reason)
        assertEquals(41, exit.exitPriceCents)
    }

    @Test
    fun exitsOnTimeoutWhenHeldTooLong() {
        val d = decider(maxHoldMs = 600_000L)
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 45, entryTimeMs = 0L)
        // Bid at 46: above stop, below target — only timeout can fire.
        val decision = d.decide(stabilizedDip(), bestBidCents = 46, bestAskCents = 47, position = pos, nowMs = 601_000L)
        val exit = decision as ScalpDecision.ScalpDecision.Exit
        assertEquals(ExitReason.TIMEOUT, exit.reason)
        assertEquals(46, exit.exitPriceCents)
    }

    @Test
    fun holdsInPositionWhenNothingHit() {
        val d = decider()
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 45, entryTimeMs = 0L)
        val decision = d.decide(stabilizedDip(), bestBidCents = 46, bestAskCents = 47, position = pos, nowMs = 100_000L)
        assertEquals(ScalpDecision.ScalpDecision.Hold, decision)
    }

    @Test
    fun targetTakesPriorityOverStopAndTimeout() {
        val d = decider(takeProfitPp = 3.0, stopLossPp = 4.0, maxHoldMs = 1L)
        val pos = ScalpDecision.OpenPosition(entryPriceCents = 45, entryTimeMs = 0L)
        // Bid +4 (target) would also be past stop for a long-held position —
        // target must win because it is checked first.
        val decision = d.decide(stabilizedDip(), bestBidCents = 50, bestAskCents = 51, position = pos, nowMs = 5_000L)
        assertEquals(ExitReason.TARGET, (decision as ScalpDecision.ScalpDecision.Exit).reason)
    }
}
