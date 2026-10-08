package com.dirk.kalshiodds.signal.fair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalMinuteSettlementTest {
    @Test
    fun computesTheArithmeticAverageTheRemainingSamplesMustReach() {
        val estimate = FinalMinuteSettlement.estimate(
            observedAverageUsd = 101.0,
            observedSamples = 45,
            currentIndexUsd = 100.0,
            strikeUsd = 100.0,
            sigmaAnnual = 0.60
        )!!

        // (60 * 100 - 45 * 101) / 15 = 97. The window is already strongly
        // locked to YES even though the latest index is only 100.
        assertEquals(97.0, estimate.requiredRemainingAverageUsd!!, 1e-9)
        assertEquals(15, estimate.remainingSamples)
        assertTrue(estimate.yesProbability > 0.99)
    }

    @Test
    fun finalSampleUsesKalshisTiePaysYesRule() {
        val settled = FinalMinuteSettlement.estimate(
            observedAverageUsd = 100.0,
            observedSamples = 60,
            currentIndexUsd = 100.0,
            strikeUsd = 100.0,
            sigmaAnnual = 0.60
        )

        assertNotNull(settled)
        assertTrue(settled!!.settled)
        assertEquals(1.0, settled.yesProbability, 0.0)
    }

    @Test
    fun needsAnOfficialPartialAverageBeforeProducingResearchProbability() {
        val missing = FinalMinuteSettlement.estimate(
            observedAverageUsd = null,
            observedSamples = 12,
            currentIndexUsd = 100.0,
            strikeUsd = 100.0,
            sigmaAnnual = 0.60
        )

        assertEquals(null, missing)
    }
}
