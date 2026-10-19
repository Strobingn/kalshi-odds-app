package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpModels
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class ChartSeriesTest {
    @Test
    fun dailyNet_groupsAndFilters() {
        val m0 = ScalpModels.ALL[0]
        val m1 = ScalpModels.ALL[1]
        val models = setOf(m0, m1)
        val day1 = java.time.ZonedDateTime.of(2026, 10, 17, 10, 0, 0, 0, ChartSeries.ET).toInstant().toEpochMilli()
        val day2 = java.time.ZonedDateTime.of(2026, 10, 18, 10, 0, 0, 0, ChartSeries.ET).toInstant().toEpochMilli()
        val nowMs = day2 + 1000
        val w1 = ScalpModels.WindowRow("w1", day1, mapOf(m0 to 1.0, m1 to 2.0), emptyMap(), null)
        val w2 = ScalpModels.WindowRow("w2", day1, mapOf(m0 to 3.0, m1 to 4.0), emptyMap(), null)
        val w3 = ScalpModels.WindowRow("w3", day2, mapOf(m0 to 5.0), emptyMap(), null)
        val w4 = ScalpModels.WindowRow("w4", nowMs + 1, mapOf(m0 to 10.0), emptyMap(), null)
        val w5 = ScalpModels.WindowRow("w5", null, mapOf(m0 to 10.0), emptyMap(), null)
        val w6 = ScalpModels.WindowRow("w6", day1, mapOf(ScalpModels.ALL[2] to 100.0), emptyMap(), null)

        val result = ChartSeries.dailyNet(listOf(w1, w2, w3, w4, w5, w6), nowMs, models)

        assertEquals(2, result.size)
        assertEquals("10/17", result[0].label)
        assertEquals(10.0, result[0].net, 0.001)
        assertEquals("10/18", result[1].label)
        assertEquals(5.0, result[1].net, 0.001)
    }

    @Test
    fun dailyNet_empty() {
        val result = ChartSeries.dailyNet(emptyList(), 1000L, setOf(ScalpModels.ALL[0]))
        assertEquals(emptyList<DayNet>(), result)
    }
}
