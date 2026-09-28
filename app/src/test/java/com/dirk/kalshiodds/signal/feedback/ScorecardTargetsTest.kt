package com.dirk.kalshiodds.signal.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardTargetsTest {

    @Test
    fun bitcoinOnlyFloorsAreFiftyAndFive() {
        assertEquals(50, ScorecardTargets.MIN_SETTLED_BTC_SIGNALS)
        assertEquals(5, ScorecardTargets.MIN_PER_SLOT_SAMPLES)
        assertEquals(50, ScorecardTargets.settledTarget())
        assertEquals(5, ScorecardTargets.perSlotMinimum())
        assertEquals(ScorecardTargets.MIN_SETTLED_BTC_SIGNALS, ScorecardMetrics.MIN_HONEST_SAMPLES)
        assertEquals(ScorecardTargets.MIN_PER_SLOT_SAMPLES, ScorecardMetrics.MIN_BUCKET_SAMPLES)
        assertEquals(listOf("BTC"), ScorecardMetrics.COIN_ORDER)
        assertFalse(ScorecardMetrics.COIN_ORDER.contains("SOL"))
        assertFalse(ScorecardMetrics.COIN_ORDER.contains("ETH"))
        assertTrue(ScorecardLedger.isScorecardTicker("KXBTC15M-26SEP251200-00"))
        assertFalse(ScorecardLedger.isScorecardTicker("KXETH15M-26SEP251200-00"))
        assertFalse(ScorecardLedger.isScorecardTicker("KXSOL15M-26SEP251200-00"))
    }
}
