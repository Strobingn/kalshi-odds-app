package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Item 2: the old tanh lock mapped every 0.2% gap to 87%. The digital
 * option (already in ScoringEngine) is time- and vol-aware.
 *
 * Table (spot 0.2% above strike), ±2 points:
 *   SOL 14m/7m/2m → 70 / 77 / 91
 *   BTC 14m/7m/2m → 83 / 91 / 99
 */
class SideLockDigitalFairTest {

    @Test
    fun tableSpotTwoTenthsPercentAboveStrike() {
        val cases = listOf(
            Triple("SOL", DigitalOptionFairValue.TYPICAL_SOL_SIGMA, listOf(70.0, 77.0, 91.0)),
            Triple("BTC", DigitalOptionFairValue.TYPICAL_BTC_SIGMA, listOf(83.0, 91.0, 99.0))
        )
        val ttes = listOf(14 * 60.0, 7 * 60.0, 2 * 60.0)
        for ((coin, sigma, expected) in cases) {
            val strike = if (coin == "BTC") 100_000.0 else 150.0
            val spot = strike * 1.002
            for (i in ttes.indices) {
                val pp = DirectionSanity.directionalFairPp(
                    signedUsd = spot - strike,
                    strike = strike,
                    tteSeconds = ttes[i],
                    sigmaAnnual = sigma
                )
                assertTrue(
                    "$coin ${ttes[i].toInt() / 60}m: got $pp expected ${expected[i]} ±2",
                    abs(pp - expected[i]) <= 2.0
                )
            }
        }
    }

    @Test
    fun lockIsSymmetricCanLowerAsWellAsRaise() {
        val strike = 100_000.0
        val spot = strike * 1.002
        val digital = DirectionSanity.directionalFairPp(
            signedUsd = spot - strike,
            strike = strike,
            tteSeconds = 14 * 60.0,
            sigmaAnnual = DigitalOptionFairValue.TYPICAL_BTC_SIGMA
        )
        // Incoming blend overstates the favorite (old 87% lock).
        val down = DirectionSanity.apply(
            spotUsd = spot,
            strikeUsd = strike,
            spotReturn = 0.001,
            fairPp = 92.0,
            predictedSide = "YES",
            digitalFairPp = digital,
            tteSeconds = 14 * 60.0,
            sigmaAnnual = DigitalOptionFairValue.TYPICAL_BTC_SIGMA
        )
        assertTrue(down.applied)
        assertEquals("YES", down.side)
        assertTrue("should lower 92 → ~83, got ${down.fairPp}", down.fairPp < 88.0)
        assertTrue(abs(down.fairPp - digital) < 0.01)

        val up = DirectionSanity.apply(
            spotUsd = spot,
            strikeUsd = strike,
            spotReturn = 0.001,
            fairPp = 40.0,
            predictedSide = "NO",
            digitalFairPp = digital,
            tteSeconds = 14 * 60.0,
            sigmaAnnual = DigitalOptionFairValue.TYPICAL_BTC_SIGMA
        )
        assertTrue(up.applied)
        assertEquals("YES", up.side)
        assertTrue("should raise 40 → ~83, got ${up.fairPp}", up.fairPp > 70.0)
    }

    @Test
    fun oldTanhEightySevenIsGone() {
        val strike = 100_000.0
        val spot = strike * 1.002
        val pp = DirectionSanity.directionalFairPp(spot - strike, strike, 14 * 60.0, 0.40)
        assertTrue("14m BTC must not invent 87%, got $pp", abs(pp - 87.0) > 2.0)
        assertTrue(abs(pp - 83.0) <= 2.0)
    }
}
