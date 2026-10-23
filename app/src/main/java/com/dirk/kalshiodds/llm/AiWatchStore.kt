package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.domain.EtTime
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
     * 1.0.36: the 15-minute check runs on its own. Default on.
     * Cloud providers wait 120 seconds. The built-in model is not paced.
     */
    val autoCalls: Boolean = true
) {
    /** Blank / 0 / negative means the budget is off. A positive amount pauses the watcher once today's estimate reaches it. */
    fun budgetOrNull(): Double? = budgetUsd?.takeIf { it.isFinite() && it > 0.0 }

    companion object {
        const val DEFAULT_INTERVAL_MS = 120_000L
        /** Cloud follow interval cannot be shorter than the 120s automatic cap. */
        const val MIN_INTERVAL_MS = 120_000L
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
    private var autoCalls = true
    private var migratedPace1036 = false
    private var intervalMs = AiWatchConfig.DEFAULT_INTERVAL_MS
    private var budgetUsd: Double? = null
    /** Explicit free-tier choice per provider. Missing means the free-family default (on). */
    private val freeTier = HashMap<String, Boolean>()
    /** Optional daily auto-call budget. Missing means [LlmProviders.defaultDailyCap]. */
    private val dailyCaps = HashMap<String, Int>()
    /** Optional monthly token cap per provider. Missing means no cap. */
    private val monthlyCaps = HashMap<String, Long>()
    /**
     * Temporary automatic-call pause after an unambiguous billing response.
     * Not written to disk, and it does not change the free-tier switch.
     */
    private val billingUntil = HashMap<String, Long>()
    private val billingNote = HashMap<String, String>()
    /** Why the last automatic call left the selected free provider. Display only. */
    private var lastSwitch: String? = null
    private val BILLING_PAUSE_MS = 15 * 60_000L
    private var priceTable: List<ModelPrice>? = null
    private val usage = ArrayList<UsageBucket>()
    private val hours = ArrayList<UsageHour>()
    private val samples = ArrayList<UsageSample>()
    private val switches = ArrayList<ModelSwitchEvent>()
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

    /**
     * True when this provider may place an automatic call.
     * Built-in is unlimited. Free and paid cloud providers are allowed when auto calls are on
     * and the provider is not in a billing pause. An explicit free-tier off stays manual.
     */
    fun autoAllowed(provider: String, model: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!autoCalls || provider.isBlank()) return@synchronized false
        if (billingPaused(provider, nowMs)) return@synchronized false
        if (provider == LlmProviders.BUILTIN) return@synchronized true
        if (freeTier[provider] == false) return@synchronized false
        true
    }

    /** The saved free-tier switch. A temporary billing pause does not turn this off. */
    fun freeTierOn(provider: String, model: String): Boolean = synchronized(lock) {
        freeTier[provider] ?: LlmProviders.freeFamily(provider, model)
    }

    fun lastSwitchLine(): String? = synchronized(lock) { lastSwitch }

    fun setFreeTier(provider: String, on: Boolean) = synchronized(lock) {
        val id = provider.trim()
        if (id.isEmpty()) return@synchronized
        freeTier[id] = on
        if (on) { billingUntil.remove(id); billingNote.remove(id) }
        persist(); bump()
    }

    /** Automatic call left [from] for [to]. Does not change the saved provider, model, or switches. */
    fun noteFallback(from: String, to: String, reason: String, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        lastSwitch = "Fell through from ${LlmProviders.shortLabel(from)} to ${LlmProviders.shortLabel(to)} · $reason · ${EtTime.clock(atMs)}"
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

    /** Blank or a non-positive number clears the monthly token cap. Automatic calls stop once usage reaches it. */
    fun setMonthlyTokenCap(provider: String, cap: Long?) = synchronized(lock) {
        val id = provider.trim()
        if (id.isEmpty()) return@synchronized
        if (cap == null || cap <= 0L) monthlyCaps.remove(id) else monthlyCaps[id] = cap.coerceAtMost(10_000_000_000L)
        publishMonthlyLocked(id, System.currentTimeMillis())
        persist(); bump()
    }

    fun monthlyTokenCap(provider: String): Long? = synchronized(lock) { monthlyCaps[provider]?.takeIf { it > 0L } }

    fun monthTokens(provider: String, nowMs: Long): Long = synchronized(lock) { monthTokensLocked(provider, nowMs) }

    /** True when this provider may still spend automatic tokens. No cap means always true. Chat does not ask. */
    fun monthlyRoom(provider: String, nowMs: Long): Boolean = synchronized(lock) {
        val cap = monthlyCaps[provider] ?: return@synchronized true
        monthTokensLocked(provider, nowMs) < cap
    }

    /**
     * Unambiguous billing. Pauses automatic calls for this provider only, for 15 minutes.
     * Does not persist, and does not change the free-tier flag or the automatic-calls switch.
     */
    fun markBilled(provider: String, detail: String, atMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val id = provider.trim().ifBlank { return@synchronized }
        val until = atMs + BILLING_PAUSE_MS
        val label = LlmProviders.shortLabel(id)
        billingUntil[id] = until
        billingNote[id] = "$label automatic calls paused until ${EtTime.clock(until)} · billing required. ${detail.take(120)}".trim()
        lastSwitch = "Paused $label · billing required · ${EtTime.clock(atMs)}"
        persist(); bump()
    }

    private fun billingPaused(provider: String, nowMs: Long): Boolean {
        val until = billingUntil[provider] ?: return false
        if (nowMs >= until) {
            billingUntil.remove(provider)
            billingNote.remove(provider)
            return false
        }
        return true
    }

    fun blockNotice(provider: String, model: String, nowMs: Long = System.currentTimeMillis()): String = synchronized(lock) {
        if (billingPaused(provider, nowMs)) return@synchronized billingNote[provider].orEmpty()
        if (LlmProviders.freeFamily(provider, model) && freeTier[provider] == false) {
            return@synchronized "${LlmProviders.spec(provider).label} is manual-only: this key is not marked free tier."
        }
        ""
    }

    fun callsFor(provider: String, model: String, nowMs: Long): Int = synchronized(lock) {
        val day = LlmPrices.dayKey(nowMs)
        val want = LlmProviders.chartProvider(provider, model)
        usage.filter { bucket ->
            bucket.day == day &&
                LlmProviders.chartProvider(bucket.provider, bucket.model) == want &&
                (model.isEmpty() || bucket.model == model)
        }.sumOf { it.calls }
    }

    private fun bump() { _safety.value = _safety.value + 1 }

    fun publishLive(s: AiLiveSnapshot) { _live.value = s }

    fun hourBuckets(): List<UsageHour> = synchronized(lock) { hours.toList() }

    fun buckets(): List<UsageBucket> = synchronized(lock) { usage.toList() }

    fun samples(): List<UsageSample> = synchronized(lock) { samples.toList() }

    fun modelSwitches(): List<ModelSwitchEvent> = synchronized(lock) {
        val before = switches.size
        ingestSwitchesLocked()
        if (switches.size != before) persist()
        switches.toList()
    }

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

    fun record(
        source: String,
        provider: String,
        model: String,
        promptTokens: Long,
        completionTokens: Long,
        atMs: Long,
        latencyMs: Long = -1,
        automatic: Boolean? = null
    ) = synchronized(lock) {
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
        val h = hours.indexOfFirst { it.day == day && it.hour == hour && it.source == src && it.model == id && it.provider == prov }
        if (h < 0) hours += UsageHour(day, hour, src, id, addCalls, p, c, prov)
        else {
            val b = hours[h]
            hours[h] = b.copy(calls = b.calls + addCalls, promptTokens = b.promptTokens + p, completionTokens = b.completionTokens + c, provider = prov)
        }
        val stamped = LlmCallClock.claim(prov, id, atMs)
        val latency = if (latencyMs >= 0) latencyMs else stamped?.latencyMs ?: -1L
        val auto = automatic ?: stamped?.automatic ?: (src != LlmSources.CHAT)
        samples += UsageSample(atMs, prov, id, src, p, c, latency, auto)
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        publishMonthlyLocked(prov, atMs)
        ingestSwitchesLocked()
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

    private fun monthTokensLocked(provider: String, nowMs: Long): Long {
        val month = LlmPrices.monthKey(nowMs)
        return usage.filter { it.provider == provider && it.day.startsWith(month) }
            .sumOf { it.promptTokens + it.completionTokens }
    }

    private fun publishMonthlyLocked(provider: String, nowMs: Long) {
        if (provider.isBlank()) return
        MonthlyTokenCaps.sync(provider, LlmPrices.monthKey(nowMs), monthTokensLocked(provider, nowMs), monthlyCaps[provider])
    }

    private fun ingestSwitchesLocked() {
        val extra = LlmEvents.drain()
        if (extra.isEmpty()) return
        switches.addAll(extra)
        while (switches.size > MAX_SWITCHES) switches.removeAt(0)
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
            val paceUpgraded = js.optBoolean("migratedPace1036", false)
            autoCalls = if (paceUpgraded) {
                if (js.has("autoCalls")) js.optBoolean("autoCalls") else true
            } else true
            migratedPace1036 = true
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
            monthlyCaps.clear()
            js.optJSONObject("monthlyTokenCap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val n = obj.optLong(k)
                    if (n > 0L) monthlyCaps[k] = n
                }
            }
            // A quota error used to be saved as billingHold plus freeTier=false. Drop that hold
            // and put the free-tier switch back. An explicit off with no hold stays off.
            var repairedHold = false
            js.optJSONObject("billingHold")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (obj.optString(k).isNotBlank() && freeTier[k] == false) {
                        freeTier.remove(k)
                        repairedHold = true
                    }
                }
            }
            lastSwitch = js.optString("lastSwitch").takeIf { it.isNotBlank() }
            intervalMs = if (!paceUpgraded) AiWatchConfig.DEFAULT_INTERVAL_MS
            else js.optLong("intervalMs", AiWatchConfig.DEFAULT_INTERVAL_MS)
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
                        o.optInt("calls"), o.optLong("prompt"), o.optLong("completion"), o.optString("provider"))
                }
            }
            samples.clear()
            js.optJSONArray("samples")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    samples += UsageSample(
                        o.optLong("atMs"), o.optString("provider"), o.optString("model"), o.optString("source"),
                        o.optLong("prompt"), o.optLong("completion"), o.optLong("latencyMs", -1), o.optBoolean("automatic", true)
                    )
                }
            }
            switches.clear()
            js.optJSONArray("switches")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    switches += ModelSwitchEvent(o.optLong("atMs"), o.optString("provider"), o.optString("from"), o.optString("to"), o.optString("reason"))
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
            if (repairedHold || !paceUpgraded) persist()
            val now = System.currentTimeMillis()
            (monthlyCaps.keys + usage.map { it.provider }).forEach { publishMonthlyLocked(it, now) }
        }
    }

    private fun persist() {
        val f = file ?: return
        val js = JSONObject()
            .put("followsWindow", follows)
            .put("liveFeed", liveFeed)
            .put("autoCalls", autoCalls)
            .put("migratedPace1036", migratedPace1036)
            .put("intervalMs", intervalMs)
            .put("budgetUsd", budgetUsd ?: JSONObject.NULL)
            .put("freeTier", JSONObject().apply { freeTier.forEach { (k, v) -> put(k, v) } })
            .put("dailyCap", JSONObject().apply { dailyCaps.forEach { (k, v) -> put(k, v) } })
            .put("monthlyTokenCap", JSONObject().apply { monthlyCaps.forEach { (k, v) -> put(k, v) } })
            .put("billingHold", JSONObject())
            .put("lastSwitch", lastSwitch ?: "")
        if (priceTable == null) js.put("prices", JSONObject.NULL) else js.put("prices", JSONArray().apply {
            priceTable.orEmpty().forEach { put(JSONObject().put("model", it.model).put("input", it.inputPerMillion).put("output", it.outputPerMillion)) }
        })
        js.put("usage", JSONArray().apply {
            usage.forEach { put(JSONObject().put("day", it.day).put("source", it.source).put("provider", it.provider).put("model", it.model)
                .put("calls", it.calls).put("prompt", it.promptTokens).put("completion", it.completionTokens)) }
        })
        js.put("hours", JSONArray().apply {
            hours.forEach { put(JSONObject().put("day", it.day).put("hour", it.hour).put("source", it.source).put("model", it.model)
                .put("provider", it.provider).put("calls", it.calls).put("prompt", it.promptTokens).put("completion", it.completionTokens)) }
        })
        js.put("samples", JSONArray().apply {
            samples.forEach { put(JSONObject().put("atMs", it.atMs).put("provider", it.provider).put("model", it.model).put("source", it.source)
                .put("prompt", it.promptTokens).put("completion", it.completionTokens).put("latencyMs", it.latencyMs).put("automatic", it.automatic)) }
        })
        js.put("switches", JSONArray().apply {
            switches.forEach { put(JSONObject().put("atMs", it.atMs).put("provider", it.provider).put("from", it.fromModel).put("to", it.toModel).put("reason", it.reason)) }
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

    private companion object {
        const val MAX_SAMPLES = 4_000
        const val MAX_SWITCHES = 100
    }
}

/**
 * Last published monthly usage, so the paper loop can honor a cap without holding the watch store.
 * [AiWatchStore] is the writer. No cap means the provider is allowed.
 */
object MonthlyTokenCaps {
    private val lock = Any()
    private val cap = HashMap<String, Long>()
    private val used = HashMap<String, Long>()

    fun sync(provider: String, month: String, tokens: Long, capOrNull: Long?) = synchronized(lock) {
        used["$provider|$month"] = tokens.coerceAtLeast(0L)
        if (capOrNull == null || capOrNull <= 0L) cap.remove(provider) else cap[provider] = capOrNull
    }

    fun room(provider: String, month: String): Boolean = synchronized(lock) {
        val c = cap[provider] ?: return true
        return (used["$provider|$month"] ?: 0L) < c
    }

    fun reset() = synchronized(lock) {
        cap.clear()
        used.clear()
    }
}
