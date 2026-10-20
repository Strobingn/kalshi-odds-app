package com.dirk.kalshiodds.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardTrendTest {

    @Test
    fun emptySeriesStaysEmpty() {
        assertTrue(ScorecardTrend.rollingHitRate(emptyList()).isEmpty())
    }

    @Test
    fun windowSlidesAndDoesNotLookAhead() {
        val won = listOf(true, true, false, false, true)
        val rate = ScorecardTrend.rollingHitRate(won, window = 3)
        assertEquals(5, rate.size)
        assertEquals(1.0, rate[0], 1e-9)
        assertEquals(1.0, rate[1], 1e-9)
        assertEquals(2.0 / 3.0, rate[2], 1e-9)
        assertEquals(1.0 / 3.0, rate[3], 1e-9)
        assertEquals(1.0 / 3.0, rate[4], 1e-9)
    }

    @Test
    fun windowOfOneIsTheOutcomeItself() {
        val rate = ScorecardTrend.rollingHitRate(listOf(false, true, false), window = 1)
        assertEquals(listOf(0.0, 1.0, 0.0), rate)
    }
}
