package com.dirk.kalshiodds.signal.fair

import com.dirk.kalshiodds.signal.external.SpotTape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Kalshi 15m crypto settle rule (rules_primary, verified on 8,080 markets in
 * tools/research/settlement_study.py): YES when the 60 s index average
 * before close is at least the strike. Mirrored by pipeline.p_settle_at_least.
 */
class SettlementFairValueTest {

    private val sigma = 0.5
    private val s2 = sigma * sigma / DigitalOptionFairValue.SECONDS_PER_YEAR
    private val btcNoise = DigitalOptionFairValue.indexNoiseLog("BTC")

    @Test
    fun wholeWindowAheadUsesAveragedVariance() {
        val spot = 84_000.0
        val strike = 83_950.0
        val tte = 600.0
        val p = DigitalOptionFairValue.pSettleAtLeast(spot, strike, tte, sigma, indexNoise = btcNoise)!!
        val sd = sqrt(s2 * ((tte - 60.0) + 60.0 / 3.0) + btcNoise * btcNoise)
        val expected = DigitalOptionFairValue.normCdf(ln(spot / strike) / sd)
        assertEquals(expected, p, 1e-12)
        assertTrue(p > 0.5)
    }

    @Test
    fun atTheStrikeEarlyIsAboutHalf() {
        val p = DigitalOptionFairValue.pSettleAtLeast(84_000.0, 84_000.0, 300.0, sigma, indexNoise = btcNoise)!!
        assertEquals(0.5, p, 1e-9)
    }

    @Test
    fun insideTheWindowTheFixedPartOfTheAverageCounts() {
        val strike = 84_000.0
        val tte = 20.0
        // Spot is $10 above the strike now, but the first 40 s of the
        // average printed $10 below it.
        val obs = ln(83_990.0)
        val spot = 84_010.0
        val p = DigitalOptionFairValue.pSettleAtLeast(spot, strike, tte, sigma, obs, btcNoise)!!
        val mean = (40.0 * obs + 20.0 * ln(spot)) / 60.0
        val f = tte / 60.0
        val sd = sqrt(f * f * s2 * tte / 3.0 + btcNoise * btcNoise)
        assertEquals(DigitalOptionFairValue.normCdf((mean - ln(strike)) / sd), p, 1e-12)
        assertTrue("locked-in average below strike outweighs spot", p < 0.5)
        val naive = DigitalOptionFairValue.pSettleAtLeast(spot, strike, tte, sigma, null, btcNoise)!!
        assertTrue("without the observed part, spot alone looks like UP", naive > 0.5)
    }

    @Test
    fun tiesSettleYes() {
        val k = 84_000.0
        assertEquals(1.0, DigitalOptionFairValue.pSettleAtLeast(k, k, 0.0, sigma, ln(k), 0.0)!!, 0.0)
        assertEquals(0.0, DigitalOptionFairValue.pSettleAtLeast(k, k, 0.0, sigma, ln(k - 0.01), 0.0)!!, 0.0)
    }

    @Test
    fun monotoneInSpotAndNoiseWidensTowardHalf() {
        val k = 84_000.0
        var prev = 0.0
        for (spot in listOf(83_900.0, 83_980.0, 84_000.0, 84_020.0, 84_100.0)) {
            val p = DigitalOptionFairValue.pSettleAtLeast(spot, k, 90.0, sigma, indexNoise = btcNoise)!!
            assertTrue(p >= prev)
            prev = p
        }
        val tight = DigitalOptionFairValue.pSettleAtLeast(84_012.0, k, 5.0, sigma, ln(84_012.0), 0.5e-4)!!
        val loose = DigitalOptionFairValue.pSettleAtLeast(84_012.0, k, 5.0, sigma, ln(84_012.0), 2.0e-4)!!
        assertTrue(loose < tight)
    }

    @Test
    fun badInputDropsOut() {
        assertNull(DigitalOptionFairValue.pSettleAtLeast(0.0, 84_000.0, 60.0, sigma))
        assertNull(DigitalOptionFairValue.pSettleAtLeast(84_000.0, 84_000.0, 60.0, 0.0))
        assertNull(DigitalOptionFairValue.pSettleAtLeast(84_000.0, 84_000.0, Double.NaN, sigma))
    }

    @Test
    fun noiseFloorsFollowTheSettlementStudy() {
        assertEquals(0.5e-4, DigitalOptionFairValue.indexNoiseLog("btc"), 0.0)
        assertEquals(0.9e-4, DigitalOptionFairValue.indexNoiseLog("ETH"), 0.0)
        assertEquals(1.1e-4, DigitalOptionFairValue.indexNoiseLog("SOL"), 0.0)
        assertEquals(1.0e-4, DigitalOptionFairValue.indexNoiseLog(null), 0.0)
    }

    @Test
    fun tapeMeanLogIsTimeWeighted() {
        val tape = SpotTape()
        assertTrue(tape.add(1_000_000L, 100.0))
        assertTrue(tape.add(1_030_000L, 110.0))
        val m = tape.meanLogPrice(1_000_000L, 1_060_000L)
        assertNotNull(m)
        assertEquals((ln(100.0) + ln(110.0)) / 2.0, m!!, 1e-12)
        // Window before the tape starts (beyond the slack) is unknown.
        assertNull(tape.meanLogPrice(0L, 1_000_000L))
        assertNull(SpotTape().meanLogPrice(0L, 60_000L))
    }
}
