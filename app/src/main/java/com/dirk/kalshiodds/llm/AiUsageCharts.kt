package com.dirk.kalshiodds.llm

import java.time.LocalDate

/** Pure aggregations of recorded LLM calls. Hours and days with no calls stay at zero — nothing is invented. */
object AiUsageCharts {
    val TRIGGERS = listOf(LlmSources.LIVE_TICK, LlmSources.WINDOW, LlmSources.BIG_MOVE, LlmSources.CHAT, LlmSources.WATCHER)

    data class HourDollar(val hour: Int, val dollars: Double, val cumulative: Double)
    data class HourCalls(val hour: Int, val bySource: Map<String, Int>)
    data class DayTokens(val day: String, val prompt: Long, val completion: Long)
    data class Projection(val spentUsd: Double, val projectedUsd: Double, val budgetUsd: Double?, val overPace: Boolean)

    fun dollarsToday(rows: List<UsageHour>, prices: List<ModelPrice>, nowMs: Long): List<HourDollar> {
        val day = LlmPrices.dayKey(nowMs)
        val through = LlmPrices.hourOf(nowMs)
        val cost = DoubleArray(24)
        for (row in rows) {
            if (row.day != day || row.hour !in 0..23) continue
            cost[row.hour] += LlmPrices.costUsd(row.promptTokens, row.completionTokens, LlmPrices.priceFor(row.model, prices))
        }
        var run = 0.0
        return (0..through).map { h ->
            run += cost[h]
            HourDollar(h, cost[h], run)
        }
    }

    fun callsToday(rows: List<UsageHour>, nowMs: Long): List<HourCalls> {
        val day = LlmPrices.dayKey(nowMs)
        val through = LlmPrices.hourOf(nowMs)
        val counts = Array(24) { HashMap<String, Int>() }
        for (row in rows) {
            if (row.day != day || row.hour !in 0..23) continue
            if (row.source !in TRIGGERS) continue
            val m = counts[row.hour]
            m[row.source] = (m[row.source] ?: 0) + row.calls
        }
        return (0..through).map { h ->
            HourCalls(h, TRIGGERS.associateWith { counts[h][it] ?: 0 })
        }
    }

    fun tokensLast7(rows: List<UsageHour>, nowMs: Long): List<DayTokens> {
        val today = LocalDate.parse(LlmPrices.dayKey(nowMs))
        val days = (0..6).map { today.minusDays((6 - it).toLong()).toString() }
        val prompt = HashMap<String, Long>()
        val completion = HashMap<String, Long>()
        for (row in rows) {
            if (row.day !in days) continue
            prompt[row.day] = (prompt[row.day] ?: 0L) + row.promptTokens
            completion[row.day] = (completion[row.day] ?: 0L) + row.completionTokens
        }
        return days.map { DayTokens(it, prompt[it] ?: 0L, completion[it] ?: 0L) }
    }

    /** Project today's spend from the pace so far. Warns only when a budget is set and the pace is over it. */
    fun projection(spentUsd: Double, nowMs: Long, budgetUsd: Double?): Projection {
        val hour = LlmPrices.hourOf(nowMs)
        val minute = LlmPrices.minuteOf(nowMs)
        val elapsedMin = (hour * 60 + minute).coerceAtLeast(1)
        val projected = spentUsd.coerceAtLeast(0.0) * (24.0 * 60.0) / elapsedMin
        val budget = budgetUsd?.takeIf { it.isFinite() && it > 0.0 }
        return Projection(spentUsd.coerceAtLeast(0.0), projected, budget, budget != null && projected > budget + 1e-9)
    }
}
