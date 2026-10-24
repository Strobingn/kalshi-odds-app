package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.AiBench
import com.dirk.kalshiodds.decision.PaperTape
import com.dirk.kalshiodds.decision.Research
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpControlsRepository
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpStrategySettings
import com.dirk.kalshiodds.decision.ScalpV2
import com.dirk.kalshiodds.decision.StrategyAlgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * 1.0.12: background follower for the live KXBTC15M window. Runs on the scalping service
 * (screen off included). Reads the same live window the chat builds at send time, asks the
 * configured model for paper setting tweaks, and applies them with no confirm tap.
 * Never places orders and never writes a real-money setting.
 */
data class WindowSnapshot(val windowKey: String, val closeMs: Long, val context: String, val fingerprint: String)

data class AiWatchProposal(val scalper: String, val setting: String, val value: String, val reason: String)

object AiWatchBounds {
    const val MAX_PER_SCALPER = 3
    private const val ABOVE = 8
    private const val BELOW = 16

    fun capture(c: ScalpControls): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (m in ScalpModels.ALL) {
            for (f in SettingTools.fieldsFor(m.code)) {
                val spec = SettingTools.specFor("strategy.${m.code}.$f") ?: continue
                if (spec.kind != SettingTools.Kind.INT) continue
                out["${m.code}.$f"] = anchor(c, m.code, f)
            }
        }
        return out
    }

    /** User anchor for the AI-learns slider, else the value that is actually in force. */
    fun anchor(c: ScalpControls, code: String, field: String): Int {
        val s = c.settings(code)
        learned(code, field, s)?.let { return it }
        explicit(s, field)?.let { return it }
        return inherited(c, code, field)
    }

    /**
     * Same safe band as "AI learns": research edge never below the user baseline and at most +4¢;
     * everyone else's highest buy never above the user baseline and at most −10¢; every other
     * number stays inside that same −10..+4 band and the setting's own range. No side argument.
     */
    fun clamp(c: ScalpControls, code: String, field: String, raw: String, baseline: Int?): String? {
        val key = "strategy.$code.$field"
        if (SettingTools.isRealMoneyKey(key) || SettingTools.isRealMoneyKey(field) || SettingTools.isRealMoneyKey(raw)) return null
        if (ScalpModels.modelByCode(code) == null) return null
        if (field !in SettingTools.fieldsFor(code)) return null
        val spec = SettingTools.specFor(key) ?: return null
        when (spec.kind) {
            SettingTools.Kind.BOOL -> {
                val v = when (raw.trim().lowercase()) {
                    "true", "on", "yes", "1" -> true
                    "false", "off", "no", "0" -> false
                    else -> return null
                }
                return v.toString()
            }
            SettingTools.Kind.INT -> {
                val n = raw.trim().toDoubleOrNull() ?: return null
                if (!n.isFinite()) return null
                val base = baseline ?: anchor(c, code, field)
                val (lo, hi) = band(c, code, field, base)
                if (lo > hi) return null
                return n.roundToInt().coerceIn(lo, hi).toString()
            }
            SettingTools.Kind.DOUBLE -> return null
        }
    }

    fun band(c: ScalpControls, code: String, field: String, base: Int): Pair<Int, Int> {
        val spec = SettingTools.specFor("strategy.$code.$field") ?: return base to base
        var lo = spec.min.toInt()
        var hi = spec.max.toInt()
        lo = maxOf(lo, base - BELOW)
        hi = minOf(hi, base + ABOVE)
        if (field == "minEntryCents") lo = maxOf(lo, com.dirk.kalshiodds.decision.ScalpFloors.cents(code))
        val s = c.settings(code)
        if (Research.isResearch(code) && field == "edgeCents") {
            lo = maxOf(lo, base)
            hi = minOf(hi, base + ABOVE)
        }
        if (!Research.isResearch(code) && field == "maxEntryCents") {
            hi = minOf(hi, base)
            lo = maxOf(lo, base - BELOW, s.minEntryCents)
        }
        if (field == "maxEntryCents") lo = maxOf(lo, s.minEntryCents)
        if (field == "minEntryCents") hi = minOf(hi, s.maxEntryCents ?: hi)
        return lo to hi
    }

    private fun learned(code: String, field: String, s: ScalpStrategySettings): Int? {
        val anchor = s.aiBaseCents ?: return null
        val slider = if (Research.isResearch(code)) "edgeCents" else "maxEntryCents"
        return if (field == slider) anchor else null
    }

    private fun explicit(s: ScalpStrategySettings, field: String): Int? = when (field) {
        "contracts" -> s.contracts
        "minEntryCents" -> s.minEntryCents
        "maxEntryCents" -> s.maxEntryCents
        "stopLossPercent" -> s.stopLossPercent
        "stopCents" -> s.stopCents
        "takeProfitCents" -> s.takeProfitCents
        "maxHoldSeconds" -> s.maxHoldSeconds
        "untilMinute" -> s.untilMinute
        "cadenceSeconds" -> s.cadenceSeconds
        "maxOpenPerSide" -> s.maxOpenPerSide
        "edgeCents" -> s.edgeCents
        else -> null
    }

    private fun inherited(c: ScalpControls, code: String, field: String): Int = when (field) {
        "maxEntryCents" -> when {
            Research.isResearch(code) -> 99
            ScalpV2.isV2(code) -> Math.round(ScalpV2.spec(code).maxAsk * 100).toInt()
            else -> Math.round(ScalpRule.ENTRY_HI * 100).toInt()
        }
        "edgeCents" -> Research.defaultEdge(code)
        "stopCents" -> c.stopCents
        "takeProfitCents" -> c.takeProfitCents
        "maxHoldSeconds" -> c.maxHoldSeconds
        "contracts" -> c.contracts
        "stopLossPercent" -> c.stopLossPercent
        else -> 0
    }
}

object PaperSettingsDiff {
    fun changed(before: ScalpControls, after: ScalpControls): List<String> {
        val keys = ArrayList<String>()
        for (m in ScalpModels.ALL) {
            for (f in SettingTools.fieldsFor(m.code)) {
                val k = "strategy.${m.code}.$f"
                if (SettingTools.get(before, k) != SettingTools.get(after, k)) keys += k
            }
            val bAlgo = before.algo[m.code].orEmpty()
            val aAlgo = after.algo[m.code].orEmpty()
            for (k in bAlgo.keys + aAlgo.keys) {
                if (bAlgo[k] != aAlgo[k]) keys += "strategy.${m.code}.algo.$k"
            }
            if ((m.code in before.aiBench) != (m.code in after.aiBench)) keys += "strategy.${m.code}.bench"
            if (before.benchOverrides[m.code] != after.benchOverrides[m.code]) keys += "strategy.${m.code}.bench"
        }
        return keys
    }
}

/** Live-window fingerprint. Time remaining is intentionally not part of it, so a quiet minute does not call the model. */
object AiWindowContext {
    data class Market(
        val windowKey: String,
        val spot: Double?,
        val yesBid: Double?,
        val yesAsk: Double?,
        val noBid: Double?,
        val noAsk: Double?,
        val modelUp: Double?,
        val positions: String
    )

    data class Scalper(
        val code: String,
        val bench: String,
        val trades: Int,
        val netUsd: Double,
        val settings: String,
        val open: Int,
        val winRate: Double? = null,
        val last20: String = "",
        val algo: String = ""
    )

    fun line(label: String, s: Scalper): String {
        val wr = s.winRate?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100) } ?: "—"
        val net = if (s.netUsd.isFinite()) String.format(java.util.Locale.US, "%+.2f", s.netUsd) else "—"
        val last = s.last20.ifBlank { "—" }
        val algo = s.algo.ifBlank { "—" }
        return "$label [${s.code}] bench=${s.bench} trades=${s.trades} net=$net win=$wr last20=$last open=${s.open} settings=${s.settings} algo=$algo"
    }

    fun fingerprint(m: Market, scalpers: List<Scalper>): String {
        val spot = m.spot?.takeIf { it.isFinite() }?.let { kotlin.math.round(it / 10.0).toLong() }
        val up = m.modelUp?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() }
        val body = scalpers.sortedBy { it.code }.joinToString(";") {
            val net = if (it.netUsd.isFinite()) (it.netUsd * 100).roundToInt() else 0
            val wr = sWin(it.winRate)
            "${it.code}:${it.bench}:${it.trades}:$net:$wr:${it.last20}:${it.open}:${it.settings}:${it.algo}"
        }
        return listOf(m.windowKey, spot, cents(m.yesBid), cents(m.yesAsk), cents(m.noBid), cents(m.noAsk), up, m.positions, body).joinToString("|")
    }

    private fun sWin(rate: Double?): Int = rate?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() } ?: -1

    fun compose(liveWindow: String, lines: List<String>): String = buildString {
        append(liveWindow.trim())
        append("\n\nEach scalper (paper results, bench, current settings):\n")
        lines.forEach { appendLine(it) }
    }

    private fun cents(p: Double?): Int? = p?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt() }
}

object AiWatchPrompt {
    fun messages(context: String): List<Pair<String, String>> = listOf("system" to SYSTEM, "user" to context)

    val SYSTEM = """
You tune PAPER scalper settings for the live KXBTC15M window.
Reply with JSON only, no markdown:
{"adjustments":[{"scalper":"<code>","setting":"<field>","value":<number or true/false>,"reason":"<one short line>"}]}
Rules:
- Paper settings only. Never place orders. Never change real money, live arming, API keys, Approve, or anything that submits an order.
- Stay side-neutral. Do not prefer UP or YES over DOWN or NO. P(UP) is only a probability; DOWN is one minus that probability. Do not introduce an UP bias.
- At most 3 setting changes per scalper. If nothing should change, return {"adjustments":[]}.
- Numeric changes are clamped to the AI-learns band: research edgeCents stays at or above the user's baseline and at most 4 cents above it; other strategies' maxEntryCents stays at or below the user's baseline and at most 10 cents below it; every other number stays within 10 below and 4 above its current value, and inside the setting's allowed range.
- Only change fields that scalper already uses.
- Each scalper also lists its own algorithm knobs as algo key=current[min..max step N]. Those keys are separate from the settings above. Move an algorithm knob by at most one step, and stay inside its min and max. Do not jump.
- The context includes each scalper's paper net $, win rate, trade count, and last 20 trade P&L (oldest to newest). Loosen winners so they trade more (lower floor down to the strategy floor, higher ceiling, bigger clip, wider entry window). Tighten losers (higher floor, lower ceiling, smaller clip, tighter stop). Steps can be several cents, scaled to recent P&L. Do not leave a winner untouched when it is clearly making money.
- Never bench, freeze, pause, or auto-pause a scalper. Do not emit an order, a live arm, or a force-bench. Only Dirk's Force bench stops one. Force trade and Force bench, when Dirk set them, always win. A setting Dirk changed by hand wins over you.
""".trimIndent()
}

object AiWatchJson {
    fun parse(text: String): List<AiWatchProposal> {
        val raw = extract(text) ?: return emptyList()
        val js = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = js.optJSONArray("adjustments") ?: js.optJSONArray("changes") ?: return emptyList()
        val out = ArrayList<AiWatchProposal>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.isNull("value")) continue
            val scalper = o.optString("scalper").ifBlank { o.optString("code") }.trim()
            val setting = o.optString("setting").ifBlank { o.optString("field") }.trim()
            if (scalper.isEmpty() || setting.isEmpty()) continue
            val reason = o.optString("reason").replace('\n', ' ').trim().take(160)
            out += AiWatchProposal(scalper, setting, valueString(o.get("value")), reason)
        }
        return out.take(40)
    }

    private fun valueString(v: Any): String = when (v) {
        is Boolean -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d == kotlin.math.round(d)) d.toLong().toString() else d.toString()
        }
        else -> v.toString().trim()
    }

    private fun extract(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }
}

class AiWindowWatcher(
    private val controls: ScalpControlsRepository,
    private val store: AiWatchStore,
    private val snapshot: () -> WindowSnapshot,
    private val ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply,
    private val endpoint: () -> LlmEndpoint,
    private val scalpersRunning: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val paper: () -> List<PaperTape> = { emptyList() },
    private val onBenched: () -> Unit = {},
    private val alternates: () -> List<LlmEndpoint> = { emptyList() },
    private val pacer: FreeCallPacer = FreeCallPacer(),
) {
    var onLivePost: (String) -> Unit = {}
    var onWake: () -> Unit = {}
    private var liveSnap: AiLiveSnapshot? = null
    private var pending = LiveTrigger.NONE
    private val gate = java.util.concurrent.atomic.AtomicBoolean(false)
    private var windowKey = ""
    private var bases = emptyMap<String, Int>()
    private val applied = HashMap<String, Int>()
    private var lastCallAt = Long.MIN_VALUE
    private var lastCalledWindow = ""
    private var lastFingerprint = ""
    private var lastTuneSig = ""

    /** Service lifecycle: keep following until the coroutine is cancelled. */
    suspend fun followWindows() {
        while (coroutineContext.isActive) {
            runCatching { withContext(Dispatchers.IO) { tick() } }
            delay(POLL_MS)
        }
    }

    fun currentWindowCloseMs(): Long {
        val close = runCatching { snapshot().closeMs }.getOrDefault(0L)
        val t = now()
        return if (close > t) close else t + 900_000L
    }

    fun noteKey(key: String) { store.noteManual(key, currentWindowCloseMs()) }

    fun noteControls(before: ScalpControls, after: ScalpControls) {
        val until = currentWindowCloseMs()
        PaperSettingsDiff.changed(before, after).forEach { store.noteManual(it, until) }
    }

    fun tick() {
        if (!gate.compareAndSet(false, true)) return
        try { tickBody() } finally { gate.set(false) }
    }

    private fun tickBody() {
        if (!runCatching { scalpersRunning() }.getOrDefault(false)) return
        val cfg = store.config.value
        if (!cfg.followsWindow) { store.setNotice(""); return }
        // On-device freeze/bench runs every poll, before any chat-LLM wait.
        runCatching { autoBench() }
        runCatching { localTune() }
        val epNow = runCatching { endpoint() }.getOrNull() ?: return
        val ep = if (epNow.provider != LlmProviders.BUILTIN && !epNow.usable) LlmProviders.builtinEndpoint() else epNow
        if (!store.autoAllowed(ep.provider, ep.model)) {
            store.setNotice("")
            return
        }
        if (ep.provider != LlmProviders.BUILTIN && !llmDueNow()) return
        runCatching { freeFollow(ep) }
    }

    /** Every Kalshi odds/book tick and BTC price tick. Updates the card immediately. Wakes the chat LLM only when 60s spacing allows. */
    fun noteKalshi(incoming: AiLiveSnapshot) {
        val prev = liveSnap
        val next = AiLiveFeed.reduce(prev, incoming)
        liveSnap = next
        store.publishLive(next)
        val t = AiLiveFeed.trigger(prev, next)
        if (t == LiveTrigger.FILL || t == LiveTrigger.WINDOW) {
            val tweaks = runCatching { localTune() }.getOrDefault(emptyList())
            if (store.config.value.liveFeed) runCatching { onLivePost(AiLiveFeed.post(next, t, tweaks)) }
        }
        if (t != LiveTrigger.NONE) pending = AiLiveFeed.prefer(pending, t)
        val cfg = store.config.value
        val ep = runCatching { endpoint() }.getOrNull()
        if (ep == null || !store.autoAllowed(ep.provider, ep.model) || !cfg.liveFeed || !cfg.followsWindow || pending == LiveTrigger.NONE) return
        if (ep.provider == LlmProviders.BUILTIN) {
            runCatching { onWake() }
            return
        }
        if (!store.monthlyRoom(ep.provider, now())) return
        val quota = LlmProviders.effectiveQuota(ep.provider, ep.model, store.dailyCap(ep.provider, ep.model)) ?: return
        if (!llmDueNow()) return
        if (pacer.ready(ep.provider, LlmProviders.paceModels(ep), quota, now(), AiCompact.GROQ_CALL_TOKENS)) runCatching { onWake() }
    }

    /** Free-tier follow. Paced, compact, and able to move to the next free provider. No paid fallback. */
    private fun freeFollow(selected: LlmEndpoint) {
        val cap = store.config.value.budgetOrNull()
        if (cap != null && store.budgetPaused(now())) {
            store.setNotice(LlmUsageText.budgetNotice(store.todaySpend(now()), cap))
            return
        }
        val snap = runCatching { snapshot() }.getOrElse {
            store.setNotice("AI follow: window unavailable")
            return
        }
        if (snap.windowKey.isBlank()) { store.setNotice(""); return }
        if (LlmProviders.requiresApiKey(selected.provider) && !selected.usable) {
            store.setNotice("AI follow: no API key for ${LlmProviders.spec(selected.provider).label}")
            return
        }
        // Cloud providers keep the 120s floor. The on-device model is not paced and needs no key.
        if (LlmProviders.requiresApiKey(selected.provider) && !llmDueNow()) return
        val result = AiAutoCalls.callFree(
            selected = selected,
            slots = runCatching { alternates() }.getOrDefault(emptyList()),
            pacer = pacer,
            nowMs = now(),
            messages = AiCompact.watch(snap.context),
            reserveCompletion = AiCompact.RESERVE_COMPLETION,
            quotaOf = {
                if (!store.monthlyRoom(it.provider, now())) null
                else LlmProviders.effectiveQuota(it.provider, it.model, store.dailyCap(it.provider, it.model))
            },
            allowed = { store.autoAllowed(it.provider, it.model) },
            ask = ask,
            onBilling = { provider, detail -> store.markBilled(provider, detail, now()) },
            onFallback = { from, to, reason -> store.noteFallback(from.provider, to.provider, reason, now()) },
            callsToday = { store.callsFor(it.provider, "", now()) }
        )
        if (result == null) {
            if (!store.monthlyRoom(selected.provider, now())) {
                val cap = store.monthlyTokenCap(selected.provider)
                val amount = cap?.let { " (${LlmUsageText.tokens(it)} tokens)" }.orEmpty()
                store.setNotice("${LlmProviders.shortLabel(selected.provider)} monthly token cap$amount reached. Automatic calls skip this provider. Chat still works.")
            } else store.setNotice("")
            return
        }
        val (used, reply) = result
        lastCallAt = now()
        lastCalledWindow = snap.windowKey
        lastFingerprint = snap.fingerprint
        val trigger = pending
        pending = LiveTrigger.NONE
        store.record(AiLiveFeed.source(trigger), used.provider, reply.model.ifBlank { used.model }, reply.promptTokens, reply.completionTokens, now())
        val tweaks = apply(reply.text)
        if (tweaks.isNotEmpty() && store.config.value.liveFeed && trigger != LiveTrigger.FILL && trigger != LiveTrigger.WINDOW) {
            runCatching { onLivePost(AiLiveFeed.post(liveSnap, trigger, tweaks)) }
        }
        val capAfter = store.config.value.budgetOrNull()
        if (capAfter != null && store.budgetPaused(now())) store.setNotice(LlmUsageText.budgetNotice(store.todaySpend(now()), capAfter))
        else store.setNotice("")
    }

    /** 60s floor, then the saved interval. A Kalshi tick or the 5s poll cannot slip past this. */
    private fun llmDueNow(): Boolean {
        val elapsed = if (lastCallAt == Long.MIN_VALUE) Long.MAX_VALUE else now() - lastCallAt
        val intervalDue = elapsed >= store.config.value.intervalMs.coerceAtLeast(AiLiveFeed.MIN_SPACING_MS)
        return AiLiveFeed.llmDue(lastCallAt, now(), pending, intervalDue)
    }

    private fun apply(text: String): List<AiAdjustment> {
        val items = AiWatchJson.parse(text)
        if (items.isEmpty()) return emptyList()
        val at = now()
        val logged = ArrayList<AiAdjustment>()
        controls.update { start ->
            var cur = start
            for (item in items) {
                val key = "strategy.${item.scalper}.${item.setting}"
                if (SettingTools.isRealMoneyKey(key) || SettingTools.isRealMoneyKey(item.setting) || SettingTools.isRealMoneyKey(item.value)) continue
                if (store.manualBlocks(key, at) || store.manualBlocks("strategy.${item.scalper}.algo.${item.setting}", at)) continue
                if ((applied[item.scalper] ?: 0) >= AiWatchBounds.MAX_PER_SCALPER) continue
                // Existing paper settings keep the 1.0.12 band. Algorithm-only keys move one step inside their own min/max.
                val knob = if (item.setting in SettingTools.fieldsFor(item.scalper)) null else StrategyAlgo.knob(cur, item.scalper, item.setting)
                if (knob != null) {
                    val proposed = item.value.toDoubleOrNull() ?: continue
                    val nowVal = StrategyAlgo.current(cur, item.scalper, knob)
                    val next = StrategyAlgo.step(nowVal, proposed, knob) ?: continue
                    if (kotlin.math.abs(next - nowVal) < 1e-9) continue
                    val prev = cur.algo[item.scalper].orEmpty()
                    cur = cur.copy(algo = cur.algo + (item.scalper to prev + (knob.key to next)))
                    applied[item.scalper] = (applied[item.scalper] ?: 0) + 1
                    logged += AiAdjustment(at, item.scalper, knob.key, StrategyAlgo.fmt(nowVal), StrategyAlgo.fmt(next), item.reason.ifBlank { "algo step" })
                    continue
                }
                val clamped = AiWatchBounds.clamp(cur, item.scalper, item.setting, item.value, bases["${item.scalper}.${item.setting}"]) ?: continue
                val proposed = SettingTools.propose(cur, key, clamped)
                if (proposed !is SettingTools.Result.Proposal) continue
                cur = SettingTools.applyAutomatic(cur, proposed)
                applied[item.scalper] = (applied[item.scalper] ?: 0) + 1
                logged += AiAdjustment(at, item.scalper, item.setting, proposed.oldValue, proposed.newValue, item.reason.ifBlank { "window follow" })
            }
            cur
        }
        logged.forEach { store.addAdjustment(it) }
        return logged
    }

    /** Automatic bench and freeze are off. Force bench is the only stop, and it is not set here. */
    private fun autoBench() = Unit

    /** On-device tuner. No network. Never benches. Owner edits that are still locked are skipped. */
    private fun localTune(): List<AiAdjustment> {
        val tapes = runCatching { paper() }.getOrDefault(emptyList())
        if (tapes.isEmpty()) return emptyList()
        val wKey = liveSnap?.windowKey?.ifBlank { null } ?: runCatching { snapshot().windowKey }.getOrDefault("")
        val fillKey = liveSnap?.fillNote.orEmpty()
        val sig = "$wKey|$fillKey|" + tapes.sortedBy { it.code }.joinToString(";") {
            "${it.code}:${it.trades}:${it.wins}:${(it.netUsd * 100).roundToInt()}"
        }
        if (sig == lastTuneSig) return emptyList()
        lastTuneSig = sig
        val at = now()
        val proposals = com.dirk.kalshiodds.decision.PaperSettingTuner.propose(controls.state.value, tapes) { key ->
            store.manualBlocks(key, at)
        }
        if (proposals.isEmpty()) return emptyList()
        val logged = ArrayList<AiAdjustment>()
        controls.update { start ->
            var cur = start
            for (p in proposals) {
                val key = "strategy.${p.scalper}.${p.setting}"
                if (SettingTools.isRealMoneyKey(key) || store.manualBlocks(key, at)) continue
                val proposed = SettingTools.propose(cur, key, p.newValue)
                if (proposed !is SettingTools.Result.Proposal) continue
                cur = SettingTools.applyAutomatic(cur, proposed)
                logged += AiAdjustment(at, p.scalper, p.setting, proposed.oldValue, proposed.newValue, p.reason)
            }
            cur
        }
        logged.forEach { store.addAdjustment(it) }
        return logged
    }

    companion object {
        /** On-device bench/freeze cadence. The chat LLM cap is [AiLiveFeed.MIN_SPACING_MS], not this poll. */
        const val POLL_MS = 5_000L
    }
}
