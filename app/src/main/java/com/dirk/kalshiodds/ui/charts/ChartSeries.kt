package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpModels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure chart-data shaping from already-recorded scalp windows. Reads only; never writes results. */
object ChartSeries {
    val ET: ZoneId = ZoneId.of("America/New_York")
    private val DAY = DateTimeFormatter.ofPattern("M/d", Locale.US)

    /** Net $ per ET day for [models] over closed windows, oldest first, latest [maxDays] days. */
    fun dailyNet(
        windows: List<ScalpModels.WindowRow>, nowMs: Long, models: Set<ScalpModels.Model>,
        zone: ZoneId = ET, maxDays: Int = 7
    ): List<DayNet> = windows
        .filter { w -> w.closeMs != null && w.closeMs <= nowMs && w.netByModel.keys.any(models::contains) }
        .groupBy { Instant.ofEpochMilli(it.closeMs!!).atZone(zone).toLocalDate() }
        .toSortedMap()
        .map { (day, rows) ->
            DayNet(DAY.format(day), rows.sumOf { w -> w.netByModel.filter { it.key in models && it.value.isFinite() }.values.sum() })
        }
        .takeLast(maxDays)
}
