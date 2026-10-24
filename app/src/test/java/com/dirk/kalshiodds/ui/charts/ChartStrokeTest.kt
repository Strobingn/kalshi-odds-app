package com.dirk.kalshiodds.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartStrokeTest {
    @Test fun seriesStrokeSitsBetweenOneAndAQuarterAndOneAndAHalf() {
        assertEquals(1.35f, ChartStroke.SERIES_DP)
        assertTrue(ChartStroke.SERIES_DP in 1.25f..1.5f)
    }

    @Test fun lineSeriesDefaultsToTheThinStroke() {
        val series = LineSeries("UP", androidx.compose.ui.graphics.Color.Black, emptyList())
        assertEquals(ChartStroke.SERIES_DP, series.strokeDp)
    }
}
