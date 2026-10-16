package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import org.junit.Assert.*
import org.junit.Test

class ExecutionWindowDataTest {
    private val end = 1_000_000L
    private val now = end - 100_000
    private fun marker(side: String, at: Long, px: Double) = ExecutionWindowData.Execution(
        "$side-$at", "trade", side, at, px, 2, ExecutionWindowData.Kind.SELL)

    @Test fun chartsOnlyShowRecordedPricesInsideTheCurrentWindowAndSelectedSide() {
        val data = ExecutionWindowData("BTC", end, now, points = listOf(
            BidPoint(end + 1, 90f, 9f), BidPoint(now, 50f, 49f),
            BidPoint(now - 1_000, Float.NaN, null), BidPoint(end - 900_001, 80f, 19f),
            BidPoint(now - 2_000, null, 38f), BidPoint(now - 3_000, 101f, -1f)))
        assertEquals(listOf(now to .50), data.quotes("YES"))
        assertEquals(listOf((now - 2_000) to .38, now to .49), data.quotes("NO"))
        assertEquals(now - 2_000, data.nearestQuote("NO", now - 1_800)!!.first)
        assertNull(data.copy(points = emptyList()).bid("YES"))
    }

    @Test fun futureOrInvalidExecutionsNeverGetAChartMarker() {
        val data = ExecutionWindowData("BTC", end, now, executions = listOf(
            marker("YES", now, .50), marker("NO", now, .30), marker("YES", now + 1, .60),
            marker("YES", end - 900_001, .40), marker("YES", now - 1, Double.NaN)))
        assertEquals(listOf(.50), data.markers("YES").map { it.price })
        assertEquals(listOf(.30), data.markers("NO").map { it.price })
    }
}
