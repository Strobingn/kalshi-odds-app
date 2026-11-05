package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.signal.model.SignalAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ThinScreenChartDataTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L - (1_800_000_000_000L % hour) + 30 * 60_000L // half past an hour

    private fun alert(atMs: Long, side: String = "YES", delta: Double = 4.0, conf: Double? = null, regime: String? = null) =
        SignalAlert(
            id = "a$atMs$side", ticker = "KXBTC15M-26NOV050030-30", series = "KXBTC15M",
            deltaPp = delta, fairValuePp = 50.0 + delta, marketMidPp = 50.0, reason = "", createdAtMs = atMs,
            receiveElapsedNanos = 0L, confidence = conf, regime = regime, predictedSide = side
        )

    @Test
    fun emptySignalsGiveZerosNotNaN() {
        val s = ThinScreenChartData.signalStats(emptyList(), now, ZoneOffset.UTC)
        assertEquals(0, s.total)
        assertEquals(0.0, s.avgEdgePp, 0.0)
        assertNull(s.avgConfidence)
        assertEquals(ThinScreenChartData.HOURS, s.hourly.size)
        assertTrue(s.hourly.all { it.up == 0 && it.down == 0 })
        assertTrue(s.regimes.isEmpty())
    }

    @Test
    fun signalsLandInTheirHourAndSide() {
        val alerts = listOf(
            alert(now, "YES"),
            alert(now - 10 * 60_000L, "NO", delta = -6.0),
            alert(now - 2 * hour, "YES", regime = "trend"),
            alert(now - 30 * hour, "YES") // older than the chart: counted in totals, not in a column
        )
        val s = ThinScreenChartData.signalStats(alerts, now, ZoneOffset.UTC)
        assertEquals(4, s.total)
        assertEquals(3, s.up)
        assertEquals(1, s.down)
        val last = s.hourly.last()
        assertEquals(1, last.up)
        assertEquals(1, last.down)
        assertEquals(1, s.hourly[ThinScreenChartData.HOURS - 3].up)
        assertEquals(4, s.hourly.sumOf { it.up + it.down } + 1)
        // |edge|, oldest first
        assertEquals(listOf(4.0, 4.0, 6.0, 4.0), s.edgeSeries)
        assertEquals(listOf("trend" to 1.0), s.regimes)
    }

    private fun row(i: Int, result: String) =
        SettledWindowRow(ticker = "KXBTC15M-$i", series = "KXBTC15M", result = result, closeMs = i * 15 * 60_000L)

    @Test
    fun settledStreaksAndStripAreOldestFirst() {
        // archive order: newest first
        val rows = listOf(row(6, "no"), row(5, "no"), row(4, "yes"), row(3, "yes"), row(2, "yes"), row(1, "no"), row(0, "void"))
        val s = ThinScreenChartData.settledStats(rows, ZoneOffset.UTC)
        assertEquals(listOf(false, true, true, true, false, false), s.strip)
        assertEquals(3, s.yes)
        assertEquals(3, s.no)
        assertEquals(-2, s.currentStreak)
        assertEquals(3, s.longestYes)
        assertEquals(2, s.longestNo)
        assertEquals(24, s.yesRateByHour.size)
        assertEquals(6, s.yesRateByHour.sumOf { it.second })
        // 00:15, 00:30, 00:45 close in hour 0 (no, yes, yes); 01:00, 01:15, 01:30 in hour 1 (yes, no, no)
        assertEquals(2.0 / 3, s.yesRateByHour[0].first, 1e-9)
        assertEquals(1.0 / 3, s.yesRateByHour[1].first, 1e-9)
    }

    @Test
    fun emptySettledIsSafe() {
        val s = ThinScreenChartData.settledStats(emptyList())
        assertTrue(s.strip.isEmpty())
        assertEquals(0, s.currentStreak)
        assertTrue(s.yesRateByHour.all { it.second == 0 && it.first == 0.0 })
    }

    @Test
    fun hourLabels() {
        assertEquals("12a", ThinScreenChartData.hourLabel(0))
        assertEquals("9a", ThinScreenChartData.hourLabel(9))
        assertEquals("12p", ThinScreenChartData.hourLabel(12))
        assertEquals("11p", ThinScreenChartData.hourLabel(23))
    }
}
