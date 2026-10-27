package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arena buys 15–58¢, and since 1.25.0 every strategy does, whatever band it declares for itself
 * ([ScalpBand.ALL_STRATEGIES_USE_DEFAULT], asked for "for all models" on 2026-10-27).
 */
class ScalpBandTest {
    private fun band(st: ScalpStrategy) = Tuned(st, ScalpKnobs(), ScalpTuning.DEFAULT_CONTRACTS)
        .let { it.entryMin to it.entryMax }

    @Test
    fun theDefaultBandIs15To58Cents() {
        assertEquals(0.15, ScalpBand.DEFAULT_MIN, 1e-12)
        assertEquals(0.58, ScalpBand.DEFAULT_MAX, 1e-12)
    }

    @Test
    fun everyStrategyBuys15To58() {
        for (st in ScalpStrategy.values()) {
            assertEquals(st.name, 0.15 to 0.58, band(st))
            assertFalse("${st.name} trades the arena's band, not one of its own", Tuned(st, ScalpKnobs(), 1).ownBand)
        }
    }

    @Test
    fun thePricesAStrategyWasResearchedOnAreStillRecorded() {
        // Kept on the enum so the rule still reads right and the band can be given back in one flag.
        assertEquals(0.92, ScalpStrategy.HEAVY_FAVORITE.entryMin, 1e-12)
        assertEquals(0.97, ScalpStrategy.HEAVY_FAVORITE.entryMax, 1e-12)
        assertEquals(0.10, ScalpStrategy.LONG_SHOT.entryMin, 1e-12)
    }

    @Test
    fun yourOwnBandStillWins() {
        val k = ScalpKnobs(entryMinCents = 80.0, entryMaxCents = 95.0)
        val t = Tuned(ScalpStrategy.LATE_FAVORITE, k, 1)
        assertEquals(0.80, t.entryMin, 1e-12)
        assertEquals(0.95, t.entryMax, 1e-12)
        assertTrue(t.ownBand)
    }

    @Test
    fun theQuoteGateStillSeesEverySideTheArenaMightBeGivenBack() {
        // Narrowing PrintGrid's gate to the arena band would hide every dear side from the whole app, not just
        // stop it buying one.
        assertTrue(PrintGrid.MIN_PRICE <= ScalpBand.DEFAULT_MIN)
        assertTrue(PrintGrid.MAX_PRICE >= 0.90)
    }

    @Test
    fun everyStrategysBandIsTheRightWayRound() {
        for (st in ScalpStrategy.values()) {
            val (lo, hi) = band(st)
            assertTrue("${st.name}: $lo-$hi", hi > lo)
        }
    }
}
