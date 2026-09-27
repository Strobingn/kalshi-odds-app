package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotRescoreGateTest {

    private val ticker = "KXBTC15M-26SEP271215-15"

    @Test
    fun firstPrintRescoresThenIntervalThrottles() {
        val last = HashMap<String, Long>()
        assertTrue(SpotRescoreGate.shouldRescore(ticker, 65_000.0, 1_000L, last) { null })
        assertEquals(1_000L, last[ticker])
        // Big move, but inside the interval.
        assertFalse(SpotRescoreGate.shouldRescore(ticker, 66_000.0, 1_000L + SignalConstants.SPOT_RESCORE_MIN_INTERVAL_MS - 1, last) { 65_000.0 })
        assertTrue(SpotRescoreGate.shouldRescore(ticker, 66_000.0, 1_000L + SignalConstants.SPOT_RESCORE_MIN_INTERVAL_MS, last) { 65_000.0 })
    }

    @Test
    fun subBasisPointMoveIsSkippedAndDoesNotStampTheClock() {
        val last = HashMap<String, Long>()
        val ref = 65_000.0
        // 0.5 bp.
        assertFalse(SpotRescoreGate.shouldRescore(ticker, ref * 1.00005, 10_000L, last) { ref })
        assertTrue(last.isEmpty())
        // 1 bp down counts too.
        assertTrue(SpotRescoreGate.shouldRescore(ticker, ref * (1 - 0.000101), 10_100L, last) { ref })
    }

    @Test
    fun lastScoredSpotIsReadOnlyAfterTheTimeGate() {
        val last = hashMapOf(ticker to 5_000L)
        var reads = 0
        assertFalse(SpotRescoreGate.shouldRescore(ticker, 65_000.0, 5_100L, last) { reads++; 60_000.0 })
        assertEquals(0, reads)
    }

    @Test
    fun badSpotNeverRescores() {
        val last = HashMap<String, Long>()
        assertFalse(SpotRescoreGate.shouldRescore(ticker, Double.NaN, 1L, last) { null })
        assertFalse(SpotRescoreGate.shouldRescore(ticker, 0.0, 1L, last) { null })
        assertTrue(last.isEmpty())
    }

    @Test
    fun tickersArePerCoinAndIndependent() {
        val watched = setOf(
            "KXBTC15M-26SEP271215-15",
            "KXETH15M-26SEP271215-15",
            "KXSOL15M-26SEP271215-15",
            "KXBTC15M-26SEP271230-30"
        )
        assertEquals(
            setOf("KXBTC15M-26SEP271215-15", "KXBTC15M-26SEP271230-30"),
            SpotRescoreGate.tickersFor("BTC", watched).toSet()
        )
        assertEquals(listOf("KXSOL15M-26SEP271215-15"), SpotRescoreGate.tickersFor("sol", watched))
        assertTrue(SpotRescoreGate.tickersFor("XRP", watched).isEmpty())

        val last = HashMap<String, Long>()
        assertTrue(SpotRescoreGate.shouldRescore("KXBTC15M-26SEP271215-15", 65_000.0, 1_000L, last) { null })
        assertTrue(SpotRescoreGate.shouldRescore("KXBTC15M-26SEP271230-30", 65_000.0, 1_000L, last) { null })
    }
}
