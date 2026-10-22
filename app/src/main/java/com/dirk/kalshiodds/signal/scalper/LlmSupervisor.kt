package com.dirk.kalshiodds.signal.scalper

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the supervisor LLM may change on the paper scalper, and how far.
 *
 * The model never edits code. It sets [ScalpKnobs] fields (the same settings as Arena → strategy →
 * Settings, which the engine reads on every decision) through [apply], which clamps every value to a
 * hard range and to [STEP_FRACTION] of that range around the current value. There is no cooldown:
 * a knob may move again on the very next tick. Paper only; nothing here can reach an order.
 */
object SupervisorBounds {
    /** One change moves a knob at most this share of its range. */
    const val STEP_FRACTION = 0.6

    /** Cap on changes taken from one answer (not a cooldown: the next tick may change more). */
    const val MAX_CHANGES_PER_TICK = 20

    data class Range(val lo: Double, val hi: Double, val integer: Boolean)

    private val band = Range(PrintGrid.MIN_PRICE * 100.0, 98.0, false)

    /** Knobs with one fixed range for every strategy. The entry band stays inside 10–98¢ (never a long shot; the late favourites buy up to 98¢). */
    private val fixed: Map<String, Range> = linkedMapOf(
        "contracts" to Range(1.0, ScalpTuning.MAX_CONTRACTS.toDouble(), true),
        "targetCents" to Range(1.0, 30.0, false),
        "stopCents" to Range(1.0, 30.0, false),
        "timeoutSeconds" to Range(10.0, 900.0, true),
        "maxPerSide" to Range(1.0, 50.0, true),
        "entryMinCents" to band,
        "entryMaxCents" to band,
        "cadenceSeconds" to Range(1.0, 60.0, true),
        "maxSpreadCents" to Range(1.0, 15.0, false),
        "bidWaitSeconds" to Range(5.0, 120.0, true),
        "fromMinute" to Range(0.0, 14.0, true),
        "untilMinute" to Range(1.0, 15.0, true),
        "maxOrdersPerWindow" to Range(0.0, 1000.0, true),
        "cooldownSeconds" to Range(0.0, 300.0, true),
        "dailyLossUsd" to Range(1.0, 200.0, false),
        "secondsLeftMin" to Range(5.0, 899.0, true),
        "secondsLeftMax" to Range(5.0, 899.0, true),
        "edgeCents" to Range(0.0, 10.0, false)
    )

    /** Settings only the late-favourite strategies ([ScalpStrategy.fav]) have. */
    private val favOnly = setOf("secondsLeftMin", "secondsLeftMax", "edgeCents")

    /** Every name the model may pass as `param`. */
    val params: List<String> = fixed.keys.toList() + listOf("trigger", "enabled")

    fun rangeOf(st: ScalpStrategy, param: String): Range? =
        if (param == "trigger") ScalpTriggers.of(st)?.let { Range(it.min, it.max, false) } else fixed[param]

    /** The value the engine trades on now, or null when the knob is unset and has no default (no step limit then). */
    fun current(st: ScalpStrategy, param: String, tn: Tuned, k: ScalpKnobs): Double? = when (param) {
        "contracts" -> tn.contracts.toDouble()
        "targetCents" -> tn.targetDollars * 100.0
        "stopCents" -> tn.stopDollars * 100.0
        "timeoutSeconds" -> tn.timeoutMs / 1000.0
        "maxPerSide" -> tn.maxPerSide.toDouble()
        "entryMinCents" -> tn.entryMin * 100.0
        "entryMaxCents" -> tn.entryMax * 100.0
        "cadenceSeconds" -> tn.cadenceSeconds.toDouble()
        "maxSpreadCents" -> k.maxSpreadCents
        "bidWaitSeconds" -> k.bidWaitSeconds?.toDouble()
        "trigger" -> k.trigger ?: ScalpTriggers.of(st)?.default?.takeIf { it.isFinite() }
        "fromMinute" -> tn.fromSecond / 60.0
        "untilMinute" -> tn.untilSecond / 60.0
        "maxOrdersPerWindow" -> k.maxOrdersPerWindow?.toDouble()
        "cooldownSeconds" -> tn.cooldownMs / 1000.0
        "dailyLossUsd" -> k.dailyLossUsd
        "secondsLeftMin" -> tn.secondsLeft?.first?.toDouble()
        "secondsLeftMax" -> tn.secondsLeft?.last?.toDouble()
        "edgeCents" -> tn.favGap?.times(100.0)
        else -> null
    }

    private fun with(k: ScalpKnobs, param: String, v: Double): ScalpKnobs = when (param) {
        "contracts" -> k.copy(contracts = v.roundToInt())
        "targetCents" -> k.copy(targetCents = v)
        "stopCents" -> k.copy(stopCents = v)
        "timeoutSeconds" -> k.copy(timeoutSeconds = v.roundToInt())
        "maxPerSide" -> k.copy(maxPerSide = v.roundToInt())
        "entryMinCents" -> k.copy(entryMinCents = v)
        "entryMaxCents" -> k.copy(entryMaxCents = v)
        "cadenceSeconds" -> k.copy(cadenceSeconds = v.roundToInt())
        "maxSpreadCents" -> k.copy(maxSpreadCents = v)
        "bidWaitSeconds" -> k.copy(bidWaitSeconds = v.roundToInt())
        "trigger" -> k.copy(trigger = v)
        "fromMinute" -> k.copy(fromMinute = v.roundToInt())
        "untilMinute" -> k.copy(untilMinute = v.roundToInt())
        "maxOrdersPerWindow" -> k.copy(maxOrdersPerWindow = v.roundToInt())
        "cooldownSeconds" -> k.copy(cooldownSeconds = v.roundToInt())
        "dailyLossUsd" -> k.copy(dailyLossUsd = v)
        "secondsLeftMin" -> k.copy(secondsLeftMin = v.roundToInt())
        "secondsLeftMax" -> k.copy(secondsLeftMax = v.roundToInt())
        "edgeCents" -> k.copy(edgeCents = v)
        else -> k
    }

    sealed class Change {
        abstract val strategy: String
        abstract val param: String

        data class Applied(
            override val strategy: String,
            override val param: String,
            val old: Double?,
            val new: Double,
            val asked: Double
        ) : Change()

        data class Rejected(
            override val strategy: String,
            override val param: String,
            val why: String
        ) : Change()
    }

    /** Validate, clamp and apply one change to [tuning]. Pure apart from the [ScalpTuning.set] it ends with. */
    fun apply(tuning: ScalpTuning, strategy: String, param: String, value: Double): Change {
        fun no(why: String) = Change.Rejected(strategy, param, why)
        val st = ScalpStrategy.ACTIVE.firstOrNull { it.name == strategy } ?: return no("unknown or retired strategy")
        if (!value.isFinite()) return no("value is not a number")
        val k = tuning.knobs(st)

        if (param == "enabled") {
            val on = value >= 0.5
            if (on == k.enabled) return no("no change")
            tuning.set(st, k.copy(enabled = on))
            return Change.Applied(strategy, param, if (k.enabled) 1.0 else 0.0, if (on) 1.0 else 0.0, value)
        }

        val range = rangeOf(st, param) ?: return no("unknown param")
        if (param in favOnly && st.fav == null) return no("only the late-favourite strategies have this")
        val cur = current(st, param, tuning.tuned(st), k)
        var v = value
        if (cur != null) {
            val step = (range.hi - range.lo) * STEP_FRACTION
            v = v.coerceIn(cur - step, cur + step)
        }
        v = snap(st, param, v.coerceIn(range.lo, range.hi), range).coerceIn(range.lo, range.hi)
        if (cur != null && abs(v - cur) < 1e-9) return no("no change")

        val next = with(k, param, v)
        val after = Tuned(st, next, tuning.defaultContracts)
        if ((param == "entryMinCents" || param == "entryMaxCents") && after.entryMin >= after.entryMax) {
            return no("entry band would be empty")
        }
        if ((param == "secondsLeftMin" || param == "secondsLeftMax") && after.secondsLeft?.isEmpty() == true) {
            return no("seconds-left window would be empty")
        }
        if ((param == "fromMinute" || param == "untilMinute") && after.noMinutes) {
            return no("minute window would be empty")
        }
        tuning.set(st, next)
        return Change.Applied(strategy, param, cur, v, value)
    }

    private fun snap(st: ScalpStrategy, param: String, v: Double, r: Range): Double = when {
        r.integer -> v.roundToInt().toDouble()
        param == "trigger" -> {
            val step = ScalpTriggers.of(st)?.step?.takeIf { it > 0.0 } ?: 0.1
            Math.round(v / step) * step
        }
        else -> (v * 10.0).roundToInt() / 10.0
    }
}

/** One closed paper scalp together with the settings it ran under: what the model learns from. */
@Serializable
data class LearnedTrade(
    val closedAtMs: Long,
    val strategy: String,
    val ticker: String,
    val side: String,
    val kind: String,
    val contracts: Int,
    val entry: Double,
    val exit: Double,
    val pnlUsd: Double,
    val heldSeconds: Int,
    /** Settings in force when the order was posted (null fields = the strategy's default). */
    val knobs: ScalpKnobs
)

/** A past change and how the strategy did before and after it. */
@Serializable
data class ScoredChange(
    val strategy: String,
    val param: String,
    val old: Double? = null,
    val new: Double,
    val reason: String = "",
    val avgNetBeforeUsd: Double,
    val avgNetAfterUsd: Double,
    val verdict: String
)

@Serializable
data class SupervisorMemory(
    val lessons: List<String> = emptyList(),
    val scored: List<ScoredChange> = emptyList()
)

/** Sends one prompt with one forced tool and returns the tool call's argument JSON, or null when no provider answered. */
fun interface LlmChat {
    fun ask(system: String, user: String, tools: JsonArray): String?
}

/** An OpenAI-compatible provider. Groq, Mistral and Gemini all speak this. */
class LlmProvider(
    val name: String,
    val baseUrl: String,
    val model: String,
    /** "required" forces the tool call; Gemini's compatibility layer is only documented with "auto". */
    val toolChoice: String = "required",
    val key: () -> String
) {
    companion object {
        const val GROQ_URL = "https://api.groq.com/openai/v1"
        const val MISTRAL_URL = "https://api.mistral.ai/v1"
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        const val GROQ_MODEL = "llama-3.3-70b-versatile"
        const val MISTRAL_MODEL = "mistral-large-latest"
        const val GEMINI_MODEL = "gemini-2.5-flash"
    }
}

/** Tries each provider in order; the next one is used when one is down, rate limited or returns junk. */
class HttpLlmChat(
    private val providers: List<LlmProvider>,
    private val onError: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
) : LlmChat {
    override fun ask(system: String, user: String, tools: JsonArray): String? {
        for (p in providers) {
            val key = p.key().trim()
            if (key.isEmpty()) continue
            try {
                val body = buildJsonObject {
                    put("model", p.model)
                    put("temperature", 0.5)
                    put("max_tokens", 1024)
                    put("tool_choice", p.toolChoice)
                    put("tools", tools)
                    putJsonArray("messages") {
                        add(buildJsonObject { put("role", "system"); put("content", system) })
                        add(buildJsonObject { put("role", "user"); put("content", user) })
                    }
                }
                val req = Request.Builder()
                    .url(p.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val args = http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        onError("${p.name} HTTP ${r.code}")
                        null
                    } else {
                        parseArguments(text)
                    }
                }
                if (args != null) return args
                onError("${p.name}: no tool call in the answer")
            } catch (e: Exception) {
                onError("${p.name}: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    /**
     * A plain chat turn for the chat screen: [messages] are (role, text) pairs, system first. Tries each provider in order
     * and returns (provider name, answer text), or null when none answered. [onError] gets each failure.
     */
    fun chat(messages: List<Pair<String, String>>): Pair<String, String>? {
        for (p in providers) {
            val key = p.key().trim()
            if (key.isEmpty()) continue
            try {
                val body = buildJsonObject {
                    put("model", p.model)
                    put("temperature", 0.4)
                    put("max_tokens", 900)
                    putJsonArray("messages") {
                        for ((role, text) in messages) add(buildJsonObject { put("role", role); put("content", text) })
                    }
                }
                val req = Request.Builder()
                    .url(p.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val text = http.newCall(req).execute().use { r ->
                    val raw = r.body?.string().orEmpty()
                    if (!r.isSuccessful) { onError("${p.name} HTTP ${r.code}"); null } else parseContent(raw)
                }
                if (!text.isNullOrBlank()) return p.name to text
                onError("${p.name}: empty answer")
            } catch (e: Exception) {
                onError("${p.name}: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    companion object {
        /** `choices[0].message.content`, or null. */
        fun parseContent(responseText: String): String? = runCatching {
            Json.parseToJsonElement(responseText).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull()

        /** `choices[0].message.tool_calls[0].function.arguments`, or null. */
        fun parseArguments(responseText: String): String? = runCatching {
            Json.parseToJsonElement(responseText).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!
                .jsonPrimitive.content
        }.getOrNull()
    }
}

/**
 * A chat LLM that watches the live 15-minute feed every minute and tunes the paper scalper on the fly.
 *
 * Every minute [tick] sends the feed, each strategy's settings and results, the last trades with the
 * settings each ran under, the past changes with their measured outcome and the model's own notes.
 * It answers with `apply_changes`; each change goes through [SupervisorBounds.apply]. Every closed
 * trade is also recorded the moment it closes ([onClosed]), so what it learns from is never a minute
 * stale. Changes are scored after [EVAL_TRADES] more trades on that strategy, so a bad change shows up
 * as "worse" in the next prompt and gets undone.
 */
class LlmSupervisor(
    private val tuning: ScalpTuning,
    private val chat: LlmChat,
    private val feed: () -> JsonObject,
    private val stats: (String) -> ScalpStats,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Receives ("journal" | "trades", one JSON line) to be appended to disk. Must be quick; it runs on the trade-print path. */
    private val sink: (String, String) -> Unit = { _, _ -> },
    private val persistMemory: (String) -> Unit = {},
    initialMemory: SupervisorMemory = SupervisorMemory(),
    private val strategies: List<ScalpStrategy>? = null
) {
    private data class Pending(
        val strategy: String,
        val param: String,
        val old: Double?,
        val new: Double,
        val reason: String,
        val atMs: Long,
        val avgBefore: Double
    )

    private val lock = Any()
    private val history = ArrayList<Pair<Long, Map<String, ScalpKnobs>>>().apply { add(0L to tuning.all()) }
    private val trades = ArrayDeque<LearnedTrade>()
    private val pending = ArrayList<Pending>()
    private var memory = initialMemory
    private var tickNo = 0
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private val _status = MutableStateFlow("AI supervisor: waiting for the first minute")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Newest-first journal lines, for the settings screen. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Show why nothing is running (no key saved, say) without calling the LLM. */
    fun idle(reason: String) {
        _status.value = "AI supervisor · $reason"
    }

    /** Record one closed paper scalp with the settings it ran under. Call it from the scalper's close hook. */
    fun onClosed(c: ClosedScalp) {
        val o = c.order
        val trade = synchronized(lock) {
            val t = LearnedTrade(
                closedAtMs = c.closedAtMs,
                strategy = o.strategy.name,
                ticker = o.ticker,
                side = o.side,
                kind = c.kind.name,
                contracts = o.contracts,
                entry = o.price,
                exit = c.exitPrice,
                pnlUsd = c.pnlUsd,
                heldSeconds = ((c.closedAtMs - c.filledAtMs) / 1000L).toInt().coerceAtLeast(0),
                knobs = knobsAt(o.postedAtMs, o.strategy.name)
            )
            trades.addLast(t)
            while (trades.size > KEEP_TRADES) trades.removeFirst()
            t
        }
        runCatching { sink("trades", json.encodeToString(LearnedTrade.serializer(), trade)) }
    }

    /** One supervision pass. Blocks on the network call: run it off the main thread. */
    fun tick(): String {
        val ctx = synchronized(lock) {
            tickNo += 1
            scorePending()
            context()
        }
        val answer = chat.ask(SYSTEM, ctx.toString(), TOOLS)
        if (answer == null) {
            return note("no answer from the LLM (check the Groq / Mistral keys)")
        }
        val obj = runCatching { Json.parseToJsonElement(answer).jsonObject }.getOrNull()
            ?: return note("answer was not JSON")
        val asked = obj["changes"] as? JsonArray ?: JsonArray(emptyList())
        var applied = 0
        for (el in asked.take(SupervisorBounds.MAX_CHANGES_PER_TICK)) {
            val c = el as? JsonObject ?: continue
            val strategy = (c["strategy"] as? JsonPrimitive)?.contentOrNull ?: continue
            val param = (c["param"] as? JsonPrimitive)?.contentOrNull ?: continue
            val value = (c["value"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val reason = (c["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(300)
            val r = synchronized(lock) {
                val res = SupervisorBounds.apply(tuning, strategy, param, value)
                if (res is SupervisorBounds.Change.Applied) {
                    history.add(nowMs() to tuning.all())
                    pending.add(Pending(strategy, param, res.old, res.new, reason, nowMs(), avgNet(strategy, before = nowMs())))
                }
                res
            }
            when (r) {
                is SupervisorBounds.Change.Applied -> {
                    applied += 1
                    journal(
                        "applied",
                        buildJsonObject {
                            put("strategy", r.strategy); put("param", r.param)
                            r.old?.let { put("old", it) }
                            put("new", r.new); put("asked", r.asked); put("reason", reason)
                        }
                    )
                }
                is SupervisorBounds.Change.Rejected -> journal(
                    "rejected",
                    buildJsonObject {
                        put("strategy", r.strategy); put("param", r.param); put("why", r.why)
                    }
                )
            }
        }
        (obj["lesson"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { l ->
            synchronized(lock) { memory = memory.copy(lessons = (memory.lessons + l.take(300)).takeLast(KEEP_LESSONS)) }
        }
        savedMemory()
        return note(if (applied == 0) "no change" else "$applied change${if (applied == 1) "" else "s"} applied")
    }

    // ---- internals ----

    private fun note(text: String): String {
        val line = "tick $tickNo: $text"
        _status.value = "AI supervisor · $line"
        return line
    }

    private fun journal(event: String, body: JsonObject) {
        val line = buildJsonObject {
            put("ts", nowMs()); put("tick", tickNo); put("event", event)
            body.forEach { (k, v) -> put(k, v) }
        }.toString()
        _log.value = (listOf(line) + _log.value).take(12)
        runCatching { sink("journal", line) }
    }

    private fun savedMemory() {
        val m = synchronized(lock) { memory }
        runCatching { persistMemory(json.encodeToString(SupervisorMemory.serializer(), m)) }
    }

    private fun knobsAt(ms: Long, strategy: String): ScalpKnobs {
        var snap = history.first().second
        for ((t, s) in history) if (t <= ms) snap = s else break
        return snap[strategy] ?: ScalpKnobs()
    }

    private fun avgNet(strategy: String, before: Long): Double {
        val mine = trades.filter { it.strategy == strategy && it.closedAtMs <= before }.takeLast(EVAL_TRADES)
        return if (mine.isEmpty()) 0.0 else mine.sumOf { it.pnlUsd } / mine.size
    }

    /** Score changes that now have [EVAL_TRADES] closed trades after them. Caller holds [lock]. */
    private fun scorePending() {
        val it = pending.iterator()
        var changed = false
        while (it.hasNext()) {
            val p = it.next()
            val after = trades.filter { t -> t.strategy == p.strategy && t.closedAtMs > p.atMs }
            if (after.size < EVAL_TRADES) continue
            val avgAfter = after.sumOf { t -> t.pnlUsd } / after.size
            val s = ScoredChange(
                p.strategy, p.param, p.old, p.new, p.reason,
                round4(p.avgBefore), round4(avgAfter),
                if (avgAfter > p.avgBefore) "better" else "worse"
            )
            memory = memory.copy(scored = (memory.scored + s).takeLast(KEEP_SCORED))
            it.remove()
            changed = true
        }
        if (changed) runCatching { persistMemory(json.encodeToString(SupervisorMemory.serializer(), memory)) }
    }

    private fun round4(v: Double) = Math.round(v * 10_000.0) / 10_000.0

    /** The prompt's data. Caller holds [lock]. */
    private fun context(): JsonObject = buildJsonObject {
        put("tick", tickNo)
        put("feed", feed())
        val idle = ArrayList<String>()
        putJsonArray("strategies") {
            for (st in strategies ?: ScalpStrategy.ACTIVE) {
                val k = tuning.knobs(st)
                val s = stats(st.name)
                if (k.isDefault && s.closed == 0) {
                    idle.add(st.name)
                    continue
                }
                val tn = tuning.tuned(st)
                add(
                    buildJsonObject {
                        put("name", st.name)
                        put("enabled", tn.enabled)
                        put("contracts", tn.contracts)
                        put("targetCents", rounded(tn.targetDollars * 100.0))
                        put("stopCents", rounded(tn.stopDollars * 100.0))
                        put("timeoutSeconds", tn.timeoutMs / 1000L)
                        put("holdsToSettlement", st.hold)
                        put("entryBandCents", "${rounded(tn.entryMin * 100.0)}-${rounded(tn.entryMax * 100.0)}")
                        put("minutes", "${tn.fromSecond / 60}-${tn.untilSecond / 60}")
                        tn.secondsLeft?.let { put("secondsLeft", "${it.first}-${it.last}") }
                        tn.favGap?.let { put("edgeCents", rounded(it * 100.0)) }
                        SupervisorBounds.current(st, "trigger", tn, k)?.let { put("trigger", rounded(it)) }
                        SupervisorBounds.rangeOf(st, "trigger")?.let {
                            put("triggerRange", "${rounded(it.lo)}-${rounded(it.hi)}")
                        }
                        put("changedByYou", !k.isDefault)
                        if (s.closed > 0) {
                            put("closed", s.closed); put("wins", s.wins)
                            put("netUsd", rounded(s.pnlUsd)); put("feesUsd", rounded(s.feesUsd))
                        }
                    }
                )
            }
        }
        putJsonArray("untouchedAndSilent") { idle.forEach { add(it) } }
        put("allowedParams", JsonArray(SupervisorBounds.params.map { JsonPrimitive(it) }))
        putJsonArray("lastTradesWithSettings") {
            trades.toList().takeLast(CONTEXT_TRADES).forEach { add(json.encodeToJsonElement(LearnedTrade.serializer(), it)) }
        }
        putJsonArray("yourChangesAwaitingScore") {
            pending.forEach {
                add(
                    buildJsonObject {
                        put("strategy", it.strategy); put("param", it.param)
                        it.old?.let { o -> put("old", o) }
                        put("new", it.new)
                        put("tradesSince", trades.count { t -> t.strategy == it.strategy && t.closedAtMs > it.atMs })
                    }
                )
            }
        }
        put("yourScoredChanges", json.encodeToJsonElement(memory.scored.takeLast(CONTEXT_SCORED)))
        put("yourNotes", JsonArray(memory.lessons.takeLast(CONTEXT_LESSONS).map { JsonPrimitive(it) }))
    }

    private fun rounded(v: Double) = Math.round(v * 100.0) / 100.0

    companion object {
        const val EVAL_TRADES = 4
        private const val KEEP_TRADES = 400
        private const val CONTEXT_TRADES = 25
        private const val CONTEXT_SCORED = 12
        private const val CONTEXT_LESSONS = 10
        private const val KEEP_SCORED = 40
        private const val KEEP_LESSONS = 30

        val SYSTEM = """
            You supervise a Bitcoin-only paper scalper on Kalshi's 15-minute BTC up/down markets. Prices are in
            cents. Many strategies run at once; each has settings you may change. Goal: the most profit after
            fees. This is paper trading and you are not limited: be decisive and aggressive.
            Every minute you get the live feed, each strategy's settings and results, the last closed trades
            together with the exact settings each ran under, your earlier changes with their measured result
            (better/worse), and your own notes.
            Two kinds of strategy run. Scalpers (holdsToSettlement=false) buy a move and sell at targetCents or
            stopCents, so target, stop, timeout, trigger and size matter most. Late buy-and-hold strategies
            (holdsToSettlement=true) ride to the settlement, so the entry band, minutes, secondsLeft, edgeCents,
            trigger and size matter most. The arena is in learning mode: all of them bet on paper as often as
            they can so you have data on every one. Tune them hard; do not just switch things off after a few
            losses, and re-enable a strategy you disabled if the market has changed.
            The strategies the arena had retired (dip-hunter, momentum-sniper, extreme reversion, resting and
            buy-now forms, flow, bounce, panic fade and the rest) lost money on every one of 11 recorded days,
            about 2 to 4 cents a contract after fees, because a 1-2 cent target cannot pay a 1.8 cent entry fee and
            a stop that costs 6 cents. They bet 50 contracts like the rest; shrink or switch off any that keep losing, and push hard on any that win; the late buy-and-hold strategies are the proven
            ones (late comeback, late edge, the Kashi favourites).
            Rules:
            - Learn from every trade: what won, what lost, and which settings were in force.
            - There is no cooldown. You may change anything on any tick, but a single trade is weak evidence;
              act harder on patterns across several trades.
            - Undo changes marked "worse". Push further on changes marked "better".
            - Never open long shots: keep entryBandCents inside 10-98 and never go below 10.
            - Turn off (enabled=0) strategies that keep losing after fees; turn winners up (contracts, maxPerSide).
            - Tighten stops and shrink size near the window close.
            - There is no limit on how many bets a strategy may place: raise maxPerSide (up to 50) and set maxOrdersPerWindow to 0 (no cap) on any strategy that is not clearly losing.
            - Be VERY aggressive: change many strategies every tick, in big steps (each change may move a knob up to 60% of its range). Test bold settings, judge them after only 4 trades, keep winners and undo losers fast. Doing nothing is rarely right.
            - Include a one-sentence lesson worth remembering.
            Answer only by calling apply_changes. An empty list is allowed.
        """.trimIndent()

        val TOOLS: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "apply_changes")
                        put(
                            "description",
                            "Change scalper settings. param is one of: ${SupervisorBounds.params.joinToString(", ")}. " +
                                "enabled takes 0 or 1. Prices are in cents."
                        )
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("changes") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            putJsonObject("strategy") { put("type", "string") }
                                            putJsonObject("param") { put("type", "string") }
                                            putJsonObject("value") { put("type", "number") }
                                            putJsonObject("reason") { put("type", "string") }
                                        }
                                        put("required", JsonArray(listOf("strategy", "param", "value", "reason").map { JsonPrimitive(it) }))
                                    }
                                }
                                putJsonObject("lesson") {
                                    put("type", "string")
                                    put("description", "One sentence to remember for later ticks.")
                                }
                            }
                            put("required", JsonArray(listOf(JsonPrimitive("changes"))))
                        }
                    }
                }
            )
        }
    }
}
