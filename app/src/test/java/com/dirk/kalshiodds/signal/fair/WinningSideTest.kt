package com.dirk.kalshiodds.signal.fair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinningSideTest {

    @Test
    fun marketFavoriteIsTheSideWhenNothingElseIsTrusted() {
        val fair = WinningSide.fairYesPp(marketPp = 80.0, settlePp = null, tteSeconds = 600.0, modelPp = null)
        assertEquals(80.0, fair, 1e-9)
        assertEquals("YES", WinningSide.side(fair))
        assertEquals("NO", WinningSide.side(40.0))
    }

    @Test
    fun untrustedModelCannotFadeAFavorite() {
        // Model says 60, market says 80. Without a holdout win the model is ignored.
        val fair = WinningSide.fairYesPp(marketPp = 80.0, settlePp = null, tteSeconds = 600.0, modelPp = null)
        assertEquals("YES", WinningSide.side(fair))
    }

    @Test
    fun provenModelPullsOnlyAQuarterOfTheWay() {
        val fair = WinningSide.fairYesPp(marketPp = 80.0, settlePp = null, tteSeconds = 600.0, modelPp = 40.0)
        assertEquals(70.0, fair, 1e-9)
        assertEquals("YES", WinningSide.side(fair))
    }

    @Test
    fun settlementFairTakesOverInsideFiveMinutes() {
        val early = WinningSide.fairYesPp(marketPp = 40.0, settlePp = 90.0, tteSeconds = 400.0, modelPp = null)
        assertEquals(40.0, early, 1e-9)
        val late = WinningSide.fairYesPp(marketPp = 40.0, settlePp = 90.0, tteSeconds = 20.0, modelPp = null)
        assertTrue(late > 80.0)
        assertEquals("YES", WinningSide.side(late))
    }
}
