package com.dirk.kalshiodds.signal.fair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementAverageTest {

    private fun state(n: Int, level: Double, strike: Double): SettlementAverage.State =
        SettlementAverage.State(
            lockedSum = level * n,
            lockedCount = n,
            strike = strike
        )

    @Test
    fun requiredMeanRisesAsMinuteLocks() {
        // 45 of 60 prints locked at 99, strike 100: the remaining 15 must
        // average 103 — a much bigger ask than the naive $1 gap.
        val s = state(45, 99.0, 100.0)
        assertEquals(103.0, SettlementAverage.requiredRemainingMean(s), 1e-9)
    }

    @Test
    fun flipDifficultyMatchesResearchIntuition() {
        // Spot sits $1 below strike with 45s locked $1 below: needed move
        // is $4 — the documented "~4× the gap" effect.
        val s = state(45, 99.0, 100.0)
        val mult = SettlementAverage.flipDifficultyMultiple(s, 99.0)!!
        assertEquals(4.0, mult, 1e-9)
    }

    @Test
    fun outcomeDeterministicWhenMinuteComplete() {
        val win = state(60, 100.5, 100.0)
        assertEquals(1.0, SettlementAverage.pFinishYes(win, 99.0, 0.6)!!, 0.0)
        val lose = state(60, 99.5, 100.0)
        assertEquals(0.0, SettlementAverage.pFinishYes(lose, 101.0, 0.6)!!, 0.0)
    }

    @Test
    fun tieResolvesYes() {
        val tie = state(60, 100.0, 100.0)
        assertEquals(1.0, SettlementAverage.pFinishYes(tie, 50.0, 0.6)!!, 0.0)
    }

    @Test
    fun spikeChaseIsOverpriced() {
        // 45 prints locked at 99 (strike 100). A late spike lifts spot to
        // 100.5; the crowd buys Yes at 90¢. The average says Yes needs the
        // remaining prints to average 103 — spot is far short.
        val s = state(45, 99.0, 100.0)
        val p = SettlementAverage.pFinishYes(s, 100.5, 0.6)!!
        assertTrue(p < 0.05)
        val dis = SettlementAverage.dislocationPp(s, 100.5, 0.6, 90.0)!!
        assertTrue(dis < -50.0)
    }

    @Test
    fun fairWhenNothingLocked() {
        // Second 0 of the final minute: reduces to spot vs strike, so a
        // spot above strike should price Yes above 50%.
        val s = state(0, 0.0, 100.0)
        val p = SettlementAverage.pFinishYes(s, 100.5, 0.6)!!
        assertTrue(p > 0.5)
    }

    @Test
    fun degenerateInputsAreNull() {
        val s = state(10, 99.0, 100.0)
        assertNull(SettlementAverage.pFinishYes(s, -1.0, 0.6))
        assertNull(SettlementAverage.pFinishYes(s, 100.0, 0.0))
        assertNull(SettlementAverage.dislocationPp(s, 100.0, 0.6, 250.0))
        assertNull(SettlementAverage.state(listOf(1.0), -5.0))
    }
}
