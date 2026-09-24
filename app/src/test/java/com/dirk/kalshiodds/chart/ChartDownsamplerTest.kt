package com.dirk.kalshiodds.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDownsamplerTest {
    @Test
    fun shortSeriesUnchanged() {
        val pts = listOf(
            BidPoint(1_000, 40f, 55f),
            BidPoint(2_000, 41f, 54f),
            BidPoint(3_000, 42f, 53f)
        )
        assertEquals(pts, ChartDownsampler.downsample(pts, 64))
    }

    @Test
    fun longSeriesCapsAndKeepsEnds() {
        val pts = (0 until 500).map { i ->
            BidPoint(1_700_000_000_000L + i * 1_000L, 30f + (i % 20), 60f - (i % 15))
        }
        val out = ChartDownsampler.downsample(pts, 64)
        assertTrue(out.size <= 64)
        assertTrue(out.size >= 8)
        assertEquals(pts.first().tMs, out.first().tMs)
        assertEquals(pts.last().tMs, out.last().tMs)
    }

    @Test
    fun spotAxisKeepsStrikeInRange() {
        val range = SpotAxis.range(listOf(84_311.58, 84_305.0), 84_278.84)
        assertTrue(range != null)
        assertTrue(range!!.first < 84_278.84)
        assertTrue(range.second > 84_311.58)
        val yStrike = SpotAxis.yFraction(84_278.84, range.first, range.second)
        val ySpot = SpotAxis.yFraction(84_311.58, range.first, range.second)
        assertTrue(ySpot < yStrike)
    }

    @Test
    fun windowFilters() {
        val pts = listOf(
            BidPoint(100, 10f, 90f),
            BidPoint(200, 20f, 80f),
            BidPoint(300, 30f, 70f)
        )
        assertEquals(1, ChartDownsampler.window(pts, 150, 250).size)
    }
}
