package com.dirk.kalshiodds.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSeriesBuilderTest {

    @Test
    fun gapsDoNotDrawZeroDips() {
        val raw = listOf(34f, 0f, 35f, null, 36f, Float.NaN, 0f, 37f, 100f, -1f)
        val series = ChartSeriesBuilder.sparklineMidsPp(raw)
        assertEquals(listOf(34f, 35f, 36f, 37f), series)
        assertTrue(series.none { it == 0f })
        assertTrue(series.none { !it.isFinite() })
    }

    @Test
    fun sparklineIsYesMidOnlyNeverMixedBidAsk() {
        val bids = listOf(33f, 0f, 34f)
        val asks = listOf(34f, 35f, 36f)
        val mids = bids.zip(asks).map { (b, a) -> ChartSeriesBuilder.yesMidCents(b, a) }
        val series = ChartSeriesBuilder.sparklineMidsPp(mids)
        assertEquals(3, series.size)
        assertEquals(33.5f, series[0], 0.01f)
        assertEquals(35f, series[1], 0.01f)
        assertEquals(35f, series[2], 0.01f)
        assertTrue("must not stitch bid then ask into one sawtooth", series.none { it == 0f })
        assertTrue(series.none { it == 33f && series.contains(36f) && series.size == 6 })
    }

    @Test
    fun cleanBidPointsSkipZeroAndKeepLastGood() {
        val pts = listOf(
            BidPoint(1_000, 34f, 66f),
            BidPoint(2_000, 0f, 0f),
            BidPoint(3_000, null, null, spotUsd = 84_144.0),
            BidPoint(4_000, 33f, 66f)
        )
        val clean = ChartSeriesBuilder.cleanBidPoints(pts)
        assertEquals(2, clean.count { it.hasQuote() })
        assertTrue(clean.none { it.upBidCents == 0f })
        assertEquals(34f, clean.first { it.hasQuote() }.upBidCents)
        assertEquals(33f, clean.last { it.hasQuote() }.upBidCents)
    }
}
