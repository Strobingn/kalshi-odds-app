package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arena buys 15–57¢ (asked for 2026-10-27). A strategy whose own band reaches past either end keeps it,
 * because for those the price is the signal; one that sat wholly inside the band was only filtering, so it opens up.
 */
class ScalpBandTest {
    private fun band(st: ScalpStrategy) = Tuned(st, ScalpKnobs(), ScalpTuning.DEFAULT_CONTRACTS)
        .let { it.entryMin to it.entryMax }

    @Test
    fun theDefaultBandIs15To57Cents() {
        assertEquals(0.15, ScalpBand.DEFAULT_MIN, 1e-12)
        assertEquals(0.57, ScalpBand.DEFAULT_MAX, 1e-12)
    }

    @Test
    fun aStrategyThatNamesNoBandBuys15To57() {
        // Was 10–90¢ (PrintGrid.MIN_PRICE/MAX_PRICE) before 1.23.0.
        for (st in listOf(
            ScalpStrategy.IMPROVE_1C, ScalpStrategy.FLOW_FADE, ScalpStrategy.STRETCH_FADE,
            ScalpStrategy.QUIET_MAKER, ScalpStrategy.PENNY_SCALPER, ScalpStrategy.PANIC_FADE
        )) {
            assertEquals(st.name, 0.15 to 0.57, band(st))
            assertFalse(st.name, Tuned(st, ScalpKnobs(), 1).ownBand)
        }
    }

    @Test
    fun aBandThatSatWhollyInsideTheDefaultIsOpenedUp() {
        // Late comeback filtered 20–40¢; its signal is the 4¢ move, not the price.
        assertEquals(0.15 to 0.57, band(ScalpStrategy.LATE_COMEBACK))
    }

    @Test
    fun theFavouritesKeepTheirOwnBandAndStillTrade() {
        assertEquals(0.80 to 0.95, band(ScalpStrategy.LATE_FAVORITE))
        assertEquals(0.65 to 0.85, band(ScalpStrategy.EARLY_FAVORITE))
        assertEquals(0.92 to 0.97, band(ScalpStrategy.HEAVY_FAVORITE))
        assertEquals(0.50 to 0.85, band(ScalpStrategy.MOMENTUM_HOLD))
        assertEquals(0.50 to 0.98, band(ScalpStrategy.LATE_EDGE))
        for (st in ScalpStrategy.values().filter { it.fav != null }) {
            assertTrue("${st.name} must still reach its favourites", band(st).second > ScalpBand.DEFAULT_MAX)
            assertTrue(st.name, Tuned(st, ScalpKnobs(), 1).ownBand)
        }
    }

    @Test
    fun theLongShotKeepsBuyingCheapSides() {
        assertEquals("its whole point is the 10–20¢ underdog", 0.10 to 0.20, band(ScalpStrategy.LONG_SHOT))
    }

    @Test
    fun yourOwnBandStillWins() {
        val k = ScalpKnobs(entryMinCents = 30.0, entryMaxCents = 44.0)
        val t = Tuned(ScalpStrategy.LATE_FAVORITE, k, 1)
        assertEquals(0.30, t.entryMin, 1e-12)
        assertEquals(0.44, t.entryMax, 1e-12)
        assertTrue(t.ownBand)
    }

    @Test
    fun theQuoteGateStillSeesEverySideTheFavouritesNeed() {
        // Narrowing PrintGrid's gate to the arena band would hide every favourite from the whole arena.
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
