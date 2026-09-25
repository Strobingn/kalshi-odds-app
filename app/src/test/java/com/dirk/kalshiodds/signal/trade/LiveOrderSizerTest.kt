package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dirk's $5 all-in rule: largest integer count with
 * count×P + ceil_cent(0.07×count×P×(1−P)) ≤ $5.
 */
class LiveOrderSizerTest {

    @Test
    fun sizesAtOneFiveTenTwentyFiveThirtyOneFiftySixtyThreeCents() {
        assertClip(0.01, 467, 0.33, 5.00, 462.00)
        assertClip(0.05, 93, 0.31, 4.96, 88.04)
        assertClip(0.10, 47, 0.30, 5.00, 42.00)
        assertClip(0.25, 19, 0.25, 5.00, 14.00)
        assertClip(0.31, 15, 0.23, 4.88, 10.12)
        assertClip(0.50, 9, 0.16, 4.66, 4.34)
        assertClip(0.63, 7, 0.12, 4.53, 2.47)
    }

    @Test
    fun sixtyThreeCentsIsBelowTenDollarMinProfit() {
        val clip = LiveOrderSizer.size(0.63)
        assertTrue(clip.ok)
        assertTrue(LiveOrderSizer.belowMinProfit(clip.profitIfWinUsd, 10.0))
        assertEquals("0.6300", clip.priceWire)
        assertEquals("7.00", clip.countWire)
    }

    @Test
    fun twentyFiveCentsClearsTenDollarMinProfit() {
        val clip = LiveOrderSizer.size(0.25)
        assertTrue(clip.ok)
        assertFalse(LiveOrderSizer.belowMinProfit(clip.profitIfWinUsd, 10.0))
        assertEquals("0.2500", clip.priceWire)
        assertEquals("19.00", clip.countWire)
    }

    @Test
    fun neverExceedsFiveDollarAllIn() {
        for (cents in 1..99) {
            val clip = LiveOrderSizer.size(cents / 100.0)
            if (clip.ok) {
                assertTrue("all-in ${clip.allInUsd} at ${cents}c", clip.allInUsd <= 5.0 + 1e-9)
            }
        }
    }

    private fun assertClip(
        price: Double,
        count: Int,
        fee: Double,
        allIn: Double,
        profit: Double
    ) {
        val clip = LiveOrderSizer.size(price)
        assertTrue("usable $price", clip.ok)
        assertEquals("count @$price", count, clip.count)
        assertEquals("fee @$price", fee, clip.feeUsd, 1e-9)
        assertEquals("all-in @$price", allIn, clip.allInUsd, 1e-9)
        assertEquals("profit @$price", profit, clip.profitIfWinUsd, 1e-9)
    }
}
