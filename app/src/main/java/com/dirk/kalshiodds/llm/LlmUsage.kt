package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpModels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 1.0.12: who placed an LLM call. Chat is never paused by the watcher budget. */
object LlmSources {
    const val WATCHER = "watcher"
    const val CHAT = "chat"
    const val STRATEGY = "strategy"
    val ALL = listOf(WATCHER, CHAT, STRATEGY)
}

data class ModelPrice(val model: String, val inputPerMillion: Double, val outputPerMillion: Double)

data class UsageSlice(
    val calls: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val costUsd: Double = 0.0,
    val bySource: Map<String, UsageSlice> = emptyMap()
)

data class UsageSummary(
    val today: UsageSlice = UsageSlice(),
    val last7: UsageSlice = UsageSlice(),
    val allTime: UsageSlice = UsageSlice()
)

/** One aggregated bucket: day (ET) × source × provider × model. Cost is computed from the live price table. */
data class UsageBucket(
    val day: String,
    val source: String,
    val provider: String,
    val model: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long
)

data class AiAdjustment(
    val atMs: Long,
    val scalper: String,
    val setting: String,
    val oldValue: String,
    val newValue: String,
    val reason: String
)

/**
 * Editable $/1M prices. Defaults are the public short-context rates (Oct 2026 for grok-4.7: $2 in / $6 out).
 * Unknown models estimate $0 until Dirk adds a row. Cached-token discounts are not applied.
 */
object LlmPrices {
    val ET: ZoneId = ZoneId.of("America/New_York")
    val DEFAULTS = listOf(
        ModelPrice("grok-4.7", 2.0, 6.0),
        ModelPrice("gpt-4.1", 2.0, 8.0),
        ModelPrice("gpt-4o", 2.5, 10.0),
        ModelPrice("gpt-4o-mini", 0.15, 0.60),
        ModelPrice("mistral-large-latest", 2.0, 6.0),
        ModelPrice("openai/gpt-4o-mini", 0.15, 0.60)
    )

    fun dayKey(ms: Long, zone: ZoneId = ET): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    /** Longest price-table key that is the same model or a dated snapshot of it. */
    fun priceFor(model: String, table: List<ModelPrice>): ModelPrice? {
        table.firstOrNull { it.model.equals(model, true) }?.let { return it }
        return table.filter { LlmClient.sameModel(it.model, model) }.maxByOrNull { it.model.length }
    }

    fun costUsd(promptTokens: Long, completionTokens: Long, price: ModelPrice?): Double {
        if (price == null) return 0.0
        val inn = price.inputPerMillion.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val out = price.outputPerMillion.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return promptTokens.coerceAtLeast(0) / 1_000_000.0 * inn + completionTokens.coerceAtLeast(0) / 1_000_000.0 * out
    }

    fun summarize(buckets: List<UsageBucket>, prices: List<ModelPrice>, nowMs: Long, zone: ZoneId = ET): UsageSummary {
        val today = dayKey(nowMs, zone)
        val start = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(6).toString()
        return UsageSummary(
            today = slice(buckets.filter { it.day == today }, prices),
            last7 = slice(buckets.filter { it.day in start..today }, prices),
            allTime = slice(buckets, prices)
        )
    }

    private fun slice(buckets: List<UsageBucket>, prices: List<ModelPrice>): UsageSlice {
        val by = LinkedHashMap<String, UsageSlice>()
        var calls = 0
        var prompt = 0L
        var completion = 0L
        var cost = 0.0
        for (src in LlmSources.ALL) by[src] = UsageSlice()
        for (b in buckets) {
            val c = costUsd(b.promptTokens, b.completionTokens, priceFor(b.model, prices))
            calls += b.calls
            prompt += b.promptTokens
            completion += b.completionTokens
            cost += c
            val prev = by[b.source] ?: UsageSlice()
            by[b.source] = prev.copy(
                calls = prev.calls + b.calls,
                promptTokens = prev.promptTokens + b.promptTokens,
                completionTokens = prev.completionTokens + b.completionTokens,
                costUsd = prev.costUsd + c
            )
        }
        return UsageSlice(calls, prompt, completion, cost, by)
    }
}

object LlmUsageText {
    fun money(v: Double): String {
        if (!v.isFinite()) return "$0.00"
        val fmt = if (abs(v) >= 1.0) "%.2f" else "%.4f"
        return "$" + String.format(Locale.US, fmt, v)
    }

    fun tokens(n: Long): String = String.format(Locale.US, "%,d", n.coerceAtLeast(0))

    fun period(label: String, s: UsageSlice): String =
        "$label: ${s.calls} calls · ${tokens(s.promptTokens + s.completionTokens)} tokens · ${money(s.costUsd)}"

    fun sources(s: UsageSlice): String = LlmSources.ALL.joinToString(" · ") { src ->
        val b = s.bySource[src]
        "$src ${b?.calls ?: 0} calls · ${money(b?.costUsd ?: 0.0)}"
    }

    fun todayLine(s: UsageSummary): String =
        "AI usage today: ${s.today.calls} calls · ${tokens(s.today.promptTokens + s.today.completionTokens)} tokens · ${money(s.today.costUsd)}"

    fun budgetNotice(spent: Double, budget: Double): String =
        "AI watcher paused: today's estimated spend ${money(spent)} hit the ${money(budget)} budget. Chat still works."
}

object AiAdjustmentLog {
    private val ET_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a 'ET'", Locale.US).withZone(LlmPrices.ET)
    const val MAX = 50

    fun line(a: AiAdjustment): String {
        val label = ScalpModels.modelByCode(a.scalper)?.label ?: a.scalper
        val why = a.reason.replace('\n', ' ').trim().ifBlank { "window follow" }
        return "${ET_FMT.format(Instant.ofEpochMilli(a.atMs))} · $label · ${a.setting} ${a.oldValue} → ${a.newValue} · $why"
    }
}
