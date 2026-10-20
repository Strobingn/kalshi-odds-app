package com.dirk.kalshiodds.llm

import java.time.Instant
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

    data class HourStack(val hourStartMs: Long, val label: String, val byProvider: Map<String, Int>)
    data class LimitUse(val provider: String, val calls: Int, val limit: Int)
    data class TokenBin(val label: String, val count: Int)
    data class AutoSplit(val auto: Int, val manual: Int)
    data class LatencySeries(val provider: String, val points: List<Pair<Long, Double>>)

    /**
     * Last 24 Eastern hours, oldest first. Hours with no calls stay at zero.
     * Provider comes from the row, or from the model id on older rows that never stored one.
     */
    fun callsPerHourByProvider(rows: List<UsageHour>, nowMs: Long): List<HourStack> {
        val end = Instant.ofEpochMilli(nowMs).atZone(LlmPrices.ET).withMinute(0).withSecond(0).withNano(0)
        return (0 until 24).map { i ->
            val slot = end.minusHours((23 - i).toLong())
            val day = slot.toLocalDate().toString()
            val hour = slot.hour
            val counts = LinkedHashMap<String, Int>()
            for (row in rows) {
                if (row.day != day || row.hour != hour) continue
                val provider = LlmProviders.chartProvider(row.provider, row.model)
                counts[provider] = (counts[provider] ?: 0) + row.calls
            }
            HourStack(slot.toInstant().toEpochMilli(), hourLabel(hour), counts)
        }
    }

    /** Today's calls against each free provider's daily auto-call budget. A limit of 0 means no daily cap. */
    fun callsVsDailyLimit(buckets: List<UsageBucket>, limits: Map<String, Int>, nowMs: Long): List<LimitUse> {
        val day = LlmPrices.dayKey(nowMs)
        val ids = (LlmProviders.FREE_IDS + limits.keys).distinct()
        return ids.map { id ->
            val calls = buckets.filter { it.day == day && LlmProviders.chartProvider(it.provider, it.model) == id }.sumOf { it.calls }
            LimitUse(id, calls, limits[id] ?: 0)
        }
    }

    fun latencyByProvider(samples: List<UsageSample>): List<LatencySeries> =
        samples.filter { it.latencyMs >= 0L }
            .groupBy { LlmProviders.chartProvider(it.provider, it.model) }
            .map { (provider, rows) ->
                LatencySeries(provider, rows.sortedBy { it.atMs }.map { it.atMs to it.latencyMs.toDouble() })
            }
            .sortedBy { LlmProviders.freeFallbackRank(it.provider) }

    fun tokenHistogram(samples: List<UsageSample>): List<TokenBin> {
        if (samples.isEmpty()) return emptyList()
        val edges = longArrayOf(100, 250, 500, 1_000, 2_000, 4_000)
        val labels = listOf("<100", "100–250", "250–500", "500–1k", "1k–2k", "2k–4k", "≥4k")
        val counts = IntArray(labels.size)
        for (sample in samples) {
            val tokens = sample.promptTokens + sample.completionTokens
            val index = edges.indexOfFirst { tokens < it }.let { if (it < 0) edges.size else it }
            counts[index]++
        }
        return labels.mapIndexed { i, label -> TokenBin(label, counts[i]) }
    }

    fun autoVsManual(samples: List<UsageSample>): AutoSplit {
        var auto = 0
        var manual = 0
        for (sample in samples) if (sample.automatic) auto++ else manual++
        return AutoSplit(auto, manual)
    }

    fun hourLabel(hour: Int): String = com.dirk.kalshiodds.domain.EtTime.hourTick(hour)

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
