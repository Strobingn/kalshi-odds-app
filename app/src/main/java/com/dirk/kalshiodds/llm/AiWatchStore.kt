package com.dirk.kalshiodds.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 1.0.12 watcher prefs: follow toggle (default on), call interval, optional daily budget, prices, usage, adjustment log. */
data class AiWatchConfig(
    val followsWindow: Boolean = true,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val budgetUsd: Double? = null,
    /** 1.0.14: AI posts and re-evaluates from the live feed. Default on. The card still shows Kalshi ticks when this is off. */
    val liveFeed: Boolean = true,
    /**
     * 1.0.17: external LLM calls on its own. Default off.
     * A 1.0.16 file has no key, and that upgrade stays off even when follow was on.
     */
    val autoCalls: Boolean = false
) {
    /** Blank / 0 / negative means the budget is off. A positive amount pauses the watcher once today's estimate reaches it. */
    fun budgetOrNull(): Double? = budgetUsd?.takeIf { it.isFinite() && it > 0.0 }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
        /** Chat LLM follow interval cannot be shorter than the 60s automatic cap. */
        const val MIN_INTERVAL_MS = 60_000L
        const val MAX_INTERVAL_MS = 600_000L
    }
}

/**
 * Local AI-follow state. Not a secret store — API keys stay in [LlmSettings].
 * Cost is recomputed from stored token counts whenever the price table changes.
 */
class AiWatchStore(private val file: File? = null) {
    private val lock = Any()
    private var follows = true
    private var liveFeed = true
    private var autoCalls = false
    private var intervalMs = AiWatchConfig.DEFAULT_INTERVAL_MS
    private var budgetUsd: Double? = null
    /** Explicit free-tier choice per provider. Missing means the free-family default (on). */
    private val freeTier = HashMap<String, Boolean>()
    /** Optional daily auto-call budget. Missing means [LlmProviders.defaultDailyCap]. */
    private val dailyCaps = HashMap<String, Int>()
    /** Provider reported billing or a paid tier. Automatic calls stay off until Dirk marks the key free again. */
    private val billingHold = HashMap<String, String>()
    private var priceTable: List<ModelPrice>? = null
    private val usage = ArrayList<UsageBucket>()
    private val hours = ArrayList<UsageHour>()
    private val log = ArrayList<AiAdjustment>()
    private val manualUntil = HashMap<String, Long>()

    private val _config = MutableStateFlow(AiWatchConfig())
    val config: StateFlow<AiWatchConfig> = _config
    private val _summary = MutableStateFlow(UsageSummary())
    val summary: StateFlow<UsageSummary> = _summary
    private val _adjustments = MutableStateFlow<List<AiAdjustment>>(emptyList())
    val adjustments: StateFlow<List<AiAdjustment>> = _adjustments
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice
    private val _live = MutableStateFlow<AiLiveSnapshot?>(null)
    val live: StateFlow<AiLiveSnapshot?> = _live
    private val _safety = MutableStateFlow(0)
    val safety: StateFlow<Int> = _safety

    init { load() }

    fun prices(): List<ModelPrice> = synchronized(lock) { LlmPrices.withDefaults(priceTable) }

    fun setFollows(on: Boolean) = synchronized(lock) { follows = on; publishConfig(); persist() }

    fun setAutoCalls(on: Boolean) = synchronized(lock) { autoCalls = on; publishConfig(); persist(); bump() }

    fun setLiveFeed(on: Boolean) = synchronized(lock) { liveFeed = on; publishConfig(); persist() }

    /** True when this provider may place an automatic LLM call. Manual chat does not ask. */
    fun autoAllowed(provider: String, model: String): Boolean = synchronized(lock) {
        if (!autoCalls || provider.isBlank()) return@synchronized false
        if (billingHold.containsKey(provider)) return@synchronized false
        if (LlmProviders.freeFamily(provider, model)) return@synchronized freeTier[provider] ?: true
        true
    }

    fun freeTierOn(provider: String, model: String): Boolean = synchronized(lock) {
        if (billingHold.containsKey(provider)) return@synchronized false
        freeTier[provider] ?: LlmProviders.freeFamily(provider, model)
    }

    fun setFreeTier(provider: String, on: Boolean) = synchronized(lock) {
        val id = provider.trim()
        if (id.isEmpty()) return@synchronized
        freeTier[id] = on
        if (on) billingHold.remove(id)
        persist(); bump()
    }

    fun dailyCap(provider: String, model: String = ""): Int = synchronized(lock) {
        dailyCaps[provider] ?: LlmProviders.defaultDailyCap(provider, model)
    }

    /** Null or a non-positive number restores the provider default. */
    fun setDailyCap(provider: String, cap: Int?) = synchronized(lock) {
        val id = provider.trim()
        if (id.isEmpty()) return@synchronized
        if (cap == null || cap <= 0) dailyCaps.remove(id) else dailyCaps[id] = cap.coerceAtMost(100_000)
        persist(); bump()
    }

    fun markBilled(provider: String, detail: String) = synchronized(lock) {
        val id = provider.trim().ifBlank { return@synchronized }
        val label = LlmProviders.spec(id).label
        billingHold[id] = "$label is manual-only: the provider reported billing or a paid tier. ${detail.take(140)}".trim()
        freeTier[id] = false
        persist(); bump()
    }

    fun blockNotice(provider: String, model: String): String = synchronized(lock) {
        billingHold[provider]?.let { return@synchronized it }
        if (LlmProviders.freeFamily(provider, model) && freeTier[provider] == false) {
            return@synchronized "${LlmProviders.spec(provider).label} is manual-only: this key is not marked free tier."
        }
        ""
    }

    fun callsFor(provider: String, model: String, nowMs: Long): Int = synchronized(lock) {
        val day = LlmPrices.dayKey(nowMs)
        usage.filter { it.day == day && it.provider == provider && (model.isEmpty() || it.model == model) }.sumOf { it.calls }
    }

    private fun bump() { _safety.value = _safety.value + 1 }

    fun publishLive(s: AiLiveSnapshot) { _live.value = s }

    fun hourBuckets(): List<UsageHour> = synchronized(lock) { hours.toList() }

    fun setIntervalMs(ms: Long) = synchronized(lock) {
        intervalMs = ms.coerceIn(AiWatchConfig.MIN_INTERVAL_MS, AiWatchConfig.MAX_INTERVAL_MS)
        publishConfig(); persist()
    }

    fun setBudgetUsd(usd: Double?) = synchronized(lock) {
        budgetUsd = usd?.takeIf { it.isFinite() && it > 0.0 }
        publishConfig(); persist()
    }

    fun setPrices(table: List<ModelPrice>) = synchronized(lock) {
        priceTable = table.mapNotNull { row ->
            val name = row.model.trim()
            if (name.isEmpty()) return@mapNotNull null
            val inn = row.inputPerMillion.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val out = row.outputPerMillion.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            ModelPrice(name, inn, out)
        }
        publishSummary(); persist()
    }

    fun setNotice(text: String) { _notice.value = text }

    fun noteManual(key: String, untilMs: Long) = synchronized(lock) {
        val k = key.trim()
        if (k.isEmpty()) return@synchronized
        manualUntil[k] = maxOf(manualUntil[k] ?: 0L, untilMs)
        persist()
    }

    fun manualBlocks(key: String, nowMs: Long): Boolean = synchronized(lock) { nowMs < (manualUntil[key] ?: Long.MIN_VALUE) }

    fun budgetPaused(nowMs: Long): Boolean = synchronized(lock) {
        val cap = AiWatchConfig(follows, intervalMs, budgetUsd).budgetOrNull() ?: return@synchronized false
        todaySpendLocked(nowMs) + 1e-9 >= cap
    }

    fun todaySpend(nowMs: Long): Double = synchronized(lock) { todaySpendLocked(nowMs) }

    fun summaryAt(nowMs: Long): UsageSummary = synchronized(lock) {
        LlmPrices.summarize(usage.toList(), LlmPrices.withDefaults(priceTable), nowMs)
    }

    fun record(source: String, provider: String, model: String, promptTokens: Long, completionTokens: Long, atMs: Long) = synchronized(lock) {
        val day = LlmPrices.dayKey(atMs)
        val src = source.ifBlank { LlmSources.WATCHER }
        val prov = provider.ifBlank { LlmProviders.XAI }
        val id = model.ifBlank { "unknown" }
        val i = usage.indexOfFirst { it.day == day && it.source == src && it.provider == prov && it.model == id }
        val addCalls = 1
        val p = promptTokens.coerceAtLeast(0)
        val c = completionTokens.coerceAtLeast(0)
        if (i < 0) usage += UsageBucket(day, src, prov, id, addCalls, p, c)
        else {
            val b = usage[i]
            usage[i] = b.copy(calls = b.calls + addCalls, promptTokens = b.promptTokens + p, completionTokens = b.completionTokens + c)
        }
        val hour = LlmPrices.hourOf(atMs)
        val h = hours.indexOfFirst { it.day == day && it.hour == hour && it.source == src && it.model == id }
        if (h < 0) hours += UsageHour(day, hour, src, id, addCalls, p, c)
        else {
            val b = hours[h]
            hours[h] = b.copy(calls = b.calls + addCalls, promptTokens = b.promptTokens + p, completionTokens = b.completionTokens + c)
        }
        publishSummary(); persist()
    }

    fun addAdjustment(a: AiAdjustment) = synchronized(lock) {
        log += a
        while (log.size > AiAdjustmentLog.MAX) log.removeAt(0)
        _adjustments.value = log.asReversed()
        persist()
    }

    private fun todaySpendLocked(nowMs: Long): Double {
        val day = LlmPrices.dayKey(nowMs)
        val prices = LlmPrices.withDefaults(priceTable)
        return usage.filter { it.day == day }.sumOf { LlmPrices.costUsd(it.promptTokens, it.completionTokens, LlmPrices.priceFor(it.model, prices)) }
    }

    private fun publishConfig() { _config.value = AiWatchConfig(follows, intervalMs, budgetUsd, liveFeed, autoCalls) }

    private fun publishSummary() {
        _summary.value = LlmPrices.summarize(usage.toList(), LlmPrices.withDefaults(priceTable), System.currentTimeMillis())
        _adjustments.value = log.asReversed()
    }

    private fun load() {
        val f = file ?: return
        val text = runCatching { if (f.isFile) f.readText() else return }.getOrNull() ?: return
        val js = runCatching { JSONObject(text) }.getOrNull() ?: return
        synchronized(lock) {
            follows = js.optBoolean("followsWindow", true)
            liveFeed = js.optBoolean("liveFeed", true)
            autoCalls = if (js.has("autoCalls")) js.optBoolean("autoCalls") else false
            freeTier.clear()
            js.optJSONObject("freeTier")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    freeTier[k] = obj.optBoolean(k)
                }
            }
            dailyCaps.clear()
            js.optJSONObject("dailyCap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    dailyCaps[k] = obj.optInt(k)
                }
            }
            billingHold.clear()
            js.optJSONObject("billingHold")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val msg = obj.optString(k)
                    if (msg.isNotBlank()) billingHold[k] = msg
                }
            }
            intervalMs = js.optLong("intervalMs", AiWatchConfig.DEFAULT_INTERVAL_MS)
                .coerceIn(AiWatchConfig.MIN_INTERVAL_MS, AiWatchConfig.MAX_INTERVAL_MS)
            budgetUsd = if (js.has("budgetUsd") && !js.isNull("budgetUsd")) js.optDouble("budgetUsd").takeIf { it.isFinite() && it > 0.0 } else null
            priceTable = if (!js.has("prices") || js.isNull("prices")) null else readPrices(js.optJSONArray("prices"))
            usage.clear()
            js.optJSONArray("usage")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    usage += UsageBucket(o.optString("day"), o.optString("source"), o.optString("provider"), o.optString("model"),
                        o.optInt("calls"), o.optLong("prompt"), o.optLong("completion"))
                }
            }
            hours.clear()
            js.optJSONArray("hours")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    hours += UsageHour(o.optString("day"), o.optInt("hour"), o.optString("source"), o.optString("model"),
                        o.optInt("calls"), o.optLong("prompt"), o.optLong("completion"))
                }
            }
            log.clear()
            js.optJSONArray("adjustments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    log += AiAdjustment(o.optLong("atMs"), o.optString("scalper"), o.optString("setting"), o.optString("old"), o.optString("new"), o.optString("reason"))
                }
            }
            while (log.size > AiAdjustmentLog.MAX) log.removeAt(0)
            manualUntil.clear()
            js.optJSONArray("manual")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("key")
                    if (k.isNotBlank()) manualUntil[k] = o.optLong("untilMs")
                }
            }
            publishConfig(); publishSummary()
        }
    }

    private fun persist() {
        val f = file ?: return
        val js = JSONObject()
            .put("followsWindow", follows)
            .put("liveFeed", liveFeed)
            .put("autoCalls", autoCalls)
            .put("intervalMs", intervalMs)
            .put("budgetUsd", budgetUsd ?: JSONObject.NULL)
            .put("freeTier", JSONObject().apply { freeTier.forEach { (k, v) -> put(k, v) } })
            .put("dailyCap", JSONObject().apply { dailyCaps.forEach { (k, v) -> put(k, v) } })
            .put("billingHold", JSONObject().apply { billingHold.forEach { (k, v) -> put(k, v) } })
        if (priceTable == null) js.put("prices", JSONObject.NULL) else js.put("prices", JSONArray().apply {
            priceTable.orEmpty().forEach { put(JSONObject().put("model", it.model).put("input", it.inputPerMillion).put("output", it.outputPerMillion)) }
        })
        js.put("usage", JSONArray().apply {
            usage.forEach { put(JSONObject().put("day", it.day).put("source", it.source).put("provider", it.provider).put("model", it.model)
                .put("calls", it.calls).put("prompt", it.promptTokens).put("completion", it.completionTokens)) }
        })
        js.put("hours", JSONArray().apply {
            hours.forEach { put(JSONObject().put("day", it.day).put("hour", it.hour).put("source", it.source).put("model", it.model)
                .put("calls", it.calls).put("prompt", it.promptTokens).put("completion", it.completionTokens)) }
        })
        js.put("adjustments", JSONArray().apply {
            log.forEach { put(JSONObject().put("atMs", it.atMs).put("scalper", it.scalper).put("setting", it.setting).put("old", it.oldValue).put("new", it.newValue).put("reason", it.reason)) }
        })
        js.put("manual", JSONArray().apply {
            manualUntil.forEach { (k, u) -> put(JSONObject().put("key", k).put("untilMs", u)) }
        })
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(js.toString())
        }
    }

    private fun readPrices(arr: JSONArray?): List<ModelPrice> {
        if (arr == null) return LlmPrices.DEFAULTS
        val out = ArrayList<ModelPrice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("model").trim()
            if (name.isEmpty()) continue
            out += ModelPrice(name, o.optDouble("input").takeIf { it.isFinite() && it >= 0 } ?: 0.0, o.optDouble("output").takeIf { it.isFinite() && it >= 0 } ?: 0.0)
        }
        return out
    }
}
