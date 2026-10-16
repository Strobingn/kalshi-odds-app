package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.ScalpModels

/** Bounded, recorded results only. All-time totals come from the indexed aggregates. */
internal object KashiArenaData {
    data class Point(val atMs: Long, val net: Double)
    enum class Sort(val label: String) { NET("Net"), WIN("Win rate"), TRADES("Trades") }
    fun rows(st: ScalpTabState, v2: Boolean, sort: Sort = Sort.NET): List<ScalpModels.Row> =
        ScalpModels.ALL.filter { ScalpModels.isV2(it) == v2 }.map { model ->
            st.algoAllTime[model] ?: ScalpModels.rowFromStat(com.dirk.kalshiodds.decision.ScalpStat(model.code))
        }.sortedWith(compareByDescending<ScalpModels.Row> { it.roundTrips > 0 }.thenByDescending {
            when (sort) { Sort.NET -> it.netUsd; Sort.WIN -> it.winRate ?: -1.0; Sort.TRADES -> it.roundTrips.toDouble() }
        }.thenBy { it.model.code })
    fun curve(windows: List<ScalpModels.WindowRow>, now: Long, models: Set<ScalpModels.Model>): List<Point> {
        val recorded = windows.filter { it.closeMs != null && it.closeMs <= now && it.netByModel.keys.any(models::contains) }
            .sortedBy { it.closeMs }
        if (recorded.isEmpty()) return emptyList()
        var net = 0.0
        return listOf(Point(recorded.first().closeMs!! - 900_000, 0.0)) + recorded.map { w ->
            net += w.netByModel.filterKeys { it in models }.values.sum()
            Point(w.closeMs!!, net)
        }
    }
}
