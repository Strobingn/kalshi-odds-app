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
    /** 1.0.14 decision loop. Chart stacks use these names. */
    const val LIVE_TICK = "live tick"
    const val WINDOW = "window"
    const val BIG_MOVE = "big move"
    val ALL = listOf(WATCHER, CHAT, STRATEGY, LIVE_TICK, WINDOW, BIG_MOVE)
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

/** One hour (ET) of real recorded calls. Empty hours are not invented. */
data class UsageHour(
    val day: String,
    val hour: Int,
    val source: String,
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
 * Editable $/1M prices. Defaults are public short-context standard rates (Oct 2026).
 * OpenAI rows cover the chat default (gpt-4.1) and the other chat models the picker is likely to use.
 * Unknown models estimate $0 until Dirk adds a row. Cached-token discounts are not applied.
 * A saved table keeps Dirk's prices and still fills any missing default, so an older Grok-only file prices OpenAI.
 */
object LlmPrices {
    val ET: ZoneId = ZoneId.of("America/New_York")
    val DEFAULTS = listOf(
        ModelPrice("grok-4.7", 2.0, 6.0),
        ModelPrice("gpt-4.1", 2.0, 8.0),
        ModelPrice("gpt-4.1-mini", 0.40, 1.60),
        ModelPrice("gpt-4.1-nano", 0.10, 0.40),
        ModelPrice("gpt-4o", 2.5, 10.0),
        ModelPrice("gpt-4o-2024-05-13", 5.0, 15.0),
        ModelPrice("gpt-4o-mini", 0.15, 0.60),
        ModelPrice("gpt-5", 1.25, 10.0),
        ModelPrice("gpt-5-mini", 0.25, 2.0),
        ModelPrice("gpt-5-nano", 0.05, 0.40),
        ModelPrice("gpt-5.4", 2.50, 15.0),
        ModelPrice("gpt-5.4-mini", 0.75, 4.50),
        ModelPrice("mistral-large-latest", 2.0, 6.0),
        ModelPrice("openai/gpt-4o-mini", 0.15, 0.60),
        ModelPrice(LlmProviders.GROQ_120, 0.0, 0.0),
        ModelPrice(LlmProviders.GROQ_20, 0.0, 0.0),
        ModelPrice(LlmProviders.GEMINI_DEFAULT, 0.0, 0.0),
        ModelPrice(LlmProviders.GEMINI_FALLBACK, 0.0, 0.0),
        ModelPrice(LlmProviders.OPENROUTER_FREE_MODEL, 0.0, 0.0)
    )

    /** Saved rows win. Defaults fill models the file never listed, including after a provider switch. */
    fun withDefaults(saved: List<ModelPrice>?): List<ModelPrice> {
        if (saved.isNullOrEmpty()) return DEFAULTS
        val extra = DEFAULTS.filter { def -> priceFor(def.model, saved) == null }
        return saved + extra
    }

    fun dayKey(ms: Long, zone: ZoneId = ET): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
    fun hourOf(ms: Long, zone: ZoneId = ET): Int = Instant.ofEpochMilli(ms).atZone(zone).hour
    fun minuteOf(ms: Long, zone: ZoneId = ET): Int = Instant.ofEpochMilli(ms).atZone(zone).minute

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
            val c = if (LlmProviders.freeFamily(b.provider, b.model)) 0.0
            else costUsd(b.promptTokens, b.completionTokens, priceFor(b.model, prices))
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
