package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.GbmModel
import com.dirk.kalshiodds.decision.ScalpControlsRepository
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.domain.MarketLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 1.0.4 AI tab state: persistent history, a busy flag, and the confirm-gated set_setting tool.
 * Errors (missing key, HTTP, model mismatch) are added to the chat as "error" messages — never swallowed.
 * The only write this class can do is [ScalpControlsRepository.save] of a PAPER setting after [applyProposal].
 */
class AiChatController(
    private val store: ChatHistoryStore,
    private val controls: ScalpControlsRepository,
    private val ask: (List<Pair<String, String>>) -> Pair<String, String>,
    private val hasKey: () -> Boolean,
    private val modelName: () -> String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Read at the moment each message is built. Empty for tests that pass their own context string. */
    private val liveContext: () -> String = { "" },
    /** Fired after a user taps Apply. The window watcher treats that key as a manual edit for the rest of the window. */
    private val onApplied: (String) -> Unit = {}
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var nextId = 1L
    private val lock = Any()
    /** Bumped by [clear]; a reply that started under an older generation is dropped (never lands in a cleared chat). */
    @Volatile private var generation = 0L
    private var job: kotlinx.coroutines.Job? = null

    private var loaded = false

    /** Restores history exactly once, under [lock], before any add/replace/clear touches the list or the file. */
    private fun ensureLoadedLocked() {
        if (loaded) return
        val h = runCatching { store.load() }.getOrDefault(emptyList()); _messages.value = h; nextId = (h.maxOfOrNull { it.id } ?: 0L) + 1; loaded = true
    }

    init { scope.launch { synchronized(lock) { ensureLoadedLocked() } } }

    private fun add(role: String, text: String, key: String? = null, old: String? = null, new: String? = null, status: String? = null, gen: Long = generation): ChatMessage? =
        synchronized(lock) {
            ensureLoadedLocked()
            if (gen != generation) return null
            // 1.0.5: show the bubble even if saving to disk fails (a persistence error must never hide a reply).
            ChatMessage(nextId++, role, text, now(), key, old, new, status).also { _messages.value = _messages.value + it; runCatching { store.saveAll(_messages.value) } }
        }

    private fun replace(m: ChatMessage, gen: Long = generation) = synchronized(lock) { ensureLoadedLocked(); if (gen != generation) return@synchronized; _messages.value = _messages.value.map { if (it.id == m.id) m else it }; runCatching { store.saveAll(_messages.value) }; Unit }

    /** OpenAI-compatible APIs want user/assistant alternation: errors and change logs become assistant turns, runs are merged. */
    internal fun llmHistory(msgs: List<ChatMessage>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in msgs) {
            val turn = when (m.role) {
                ChatMessage.USER -> "user" to m.text
                ChatMessage.AI -> {
                    val cleaned = ChatPrompt.withoutSnapshotClaims(m.text.replace(Regex("^\\[model [^\\]]*\\]\\s*"), ""))
                    if (cleaned.isBlank()) continue
                    "assistant" to cleaned
                }
                ChatMessage.ERROR -> "assistant" to "(request failed: ${m.text})"
                ChatMessage.CHANGE -> "assistant" to "(settings log: ${m.text})"
                ChatMessage.PROPOSAL -> "assistant" to "(proposed ${m.key}: ${m.oldValue} → ${m.newValue}, ${m.status})"
                else -> continue
            }
            if (out.isNotEmpty() && out.last().first == turn.first) out[out.lastIndex] = turn.first to (out.last().second + "\n" + turn.second) else out += turn
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    /** [context] is a short leaderboard / strategy status block from the UI. */
    fun send(question: String, context: String) {
        val q = question.trim(); if (q.isEmpty()) return
        if (_busy.value) { scope.launch { add(ChatMessage.ERROR, "Still waiting for the previous answer — try again when it arrives.") }; return }
        _busy.value = true
        val gen = generation
        job = scope.launch {
            try {
                add(ChatMessage.USER, q, gen = gen) ?: return@launch
                if (!hasKey()) { add(ChatMessage.ERROR, "No LLM API key saved. Enter it in the key settings above the chat (stored encrypted on this phone).", gen = gen); return@launch }
                // History before this question, alternating roles; the question itself goes last as the user turn.
                val history = llmHistory(_messages.value.dropLast(1).takeLast(24)).let { h -> if (h.lastOrNull()?.first == "user") h.dropLast(1) else h }.takeLast(16)
                    .let { h -> h.dropWhile { it.first != "user" } }
                // Live feed is read on every request, including the first message of a new chat.
                val live = runCatching { liveContext() }.getOrElse { ChatPrompt.LIVE_HEADER + "\nunavailable (" + rootCauseMessage(it) + ")" }
                val ctx = listOf(live, context).filter { it.isNotBlank() }.joinToString("\n")
                val msgs = ChatPrompt.build(q, history, SettingTools.describe(controls.state.value), ctx)
                val (model, text) = runCatching { ask(msgs) }
                    .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e
                        add(ChatMessage.ERROR, "Error (${modelName()}, no fallback model): ${e.message ?: e.javaClass.simpleName}", gen = gen); return@launch }
                if (text.isBlank()) { add(ChatMessage.ERROR, "Error ($model): the model returned an empty answer — try again or ask a shorter question.", gen = gen); return@launch }
                var raw = text
                var body = SettingTools.stripCalls(raw)
                if (ChatPrompt.hasSnapshotClaim(body)) {
                    val correction = "Correction: you are continuously following the live 15-minute window on the app feed. Answer again. Do not tell the user to ping or message again for fresh data. Do not say you cannot watch continuously."
                    val retry = runCatching { ask(msgs + ("assistant" to raw) + ("user" to correction)) }.getOrNull()
                    if (retry != null && retry.second.isNotBlank()) raw = retry.second
                    body = SettingTools.stripCalls(raw)
                    if (ChatPrompt.hasSnapshotClaim(body)) body = ChatPrompt.withoutSnapshotClaims(body)
                }
                val shown = body.ifBlank {
                    if (SettingTools.parseCalls(raw).isNotEmpty()) "(proposed a settings change)" else "The live window is on the feed."
                }
                add(ChatMessage.AI, "[model $model] $shown", gen = gen) ?: return@launch
                SettingTools.parseCalls(raw).take(5).forEach { (k, v) -> propose(k, v, gen) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { // never a silent failure: anything unexpected becomes an error bubble
                add(ChatMessage.ERROR, "Error: ${rootCauseMessage(e)}", gen = gen)
            } finally { if (gen == generation) _busy.value = false }
        }
    }

    /** Public so tests (and a future manual entry) can drive the tool directly. */
    fun propose(key: String, value: String, gen: Long = generation): ChatMessage? = when (val r = SettingTools.propose(controls.state.value, key, value)) {
        is SettingTools.Result.Proposal -> add(ChatMessage.PROPOSAL, "Change ${r.key}?", r.key, r.oldValue, r.newValue, ChatMessage.PENDING, gen)
        is SettingTools.Result.Rejected -> add(ChatMessage.CHANGE, (if (r.realMoneyBlocked) "BLOCKED " else "Rejected ") + "set_setting ${r.key} = $value: ${r.reason}", r.key, null, value, "rejected", gen)
    }

    fun applyProposal(id: Long) { val g = generation; scope.launch { resolve(id, apply = true, gen = g) } }
    fun cancelProposal(id: Long) { val g = generation; scope.launch { resolve(id, apply = false, gen = g) } }

    fun resolve(id: Long, apply: Boolean, gen: Long = generation) {
        // Atomically claim the card (pending → applying) so double taps / Apply+Cancel resolve it exactly once.
        val m = synchronized(lock) {
            ensureLoadedLocked()
            if (gen != generation) return // history was cleared after the tap
            val p = _messages.value.firstOrNull { it.id == id && it.role == ChatMessage.PROPOSAL && it.status == ChatMessage.PENDING } ?: return
            _messages.value = _messages.value.map { if (it.id == id) it.copy(status = "applying") else it }
            p
        }
        val key = m.key ?: return; val new = m.newValue ?: return
        if (!apply) {
            val nowValue = runCatching { SettingTools.get(controls.state.value, key) }.getOrDefault(m.oldValue.orEmpty())
            replace(m.copy(status = ChatMessage.CANCELLED), gen); add(ChatMessage.CHANGE, "Cancelled: $key stays $nowValue", key, nowValue, new, ChatMessage.CANCELLED, gen); return
        }
        var before = m.oldValue.orEmpty()
        runCatching { controls.update { cur -> before = SettingTools.get(cur, key); SettingTools.apply(cur, SettingTools.Result.Proposal(key, before, new)) } }
            .onSuccess { saved ->
                replace(m.copy(status = ChatMessage.APPLIED), gen)
                add(ChatMessage.CHANGE, "Applied: $key $before → ${SettingTools.get(saved, key)}", key, before, new, ChatMessage.APPLIED, gen)
                runCatching { onApplied(key) }
            }
            .onFailure { replace(m.copy(status = "rejected"), gen); add(ChatMessage.ERROR, "Could not apply $key = $new: ${it.message}", gen = gen) }
    }

    /** A live-feed post. Does not wait on the user and does not place an order. */
    fun postLive(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        scope.launch { add(ChatMessage.AI, line) }
    }

    fun clear() {
        synchronized(lock) { loaded = true; generation++; job?.cancel(); job = null; _messages.value = emptyList(); _busy.value = false }
        scope.launch { synchronized(lock) { if (_messages.value.isEmpty()) store.clear() else store.saveAll(_messages.value) } }
    }
}

/** 1.0.6: unwrap ExceptionInInitializerError / wrapper exceptions so the chat bubble shows the real cause. */
internal fun rootCauseMessage(e: Throwable): String {
    var t: Throwable = e
    val seen = HashSet<Throwable>()
    while (seen.add(t)) { t = t.cause ?: break }
    val name = t.javaClass.simpleName
    return t.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
}

/**
 * 1.0.8: the AI tab's conversational prompt. User chat messages ALWAYS go through this (a general assistant that
 * answers any question), never through [LlmPaperStrategy]'s one-line YES/NO/SKIP trade prompt.
 * 1.0.11: [liveWindow] is the current KXBTC15M window, rebuilt from live quotes at each send.
 */
object ChatPrompt {
    const val MARKER = "You are a helpful general-purpose assistant"
    const val LIVE_HEADER = "Live KXBTC15M window (kept current by the live feed):"
    /** Continuous feed. Must not use per-message or one-shot wording. */
    const val LIVE_FEED = "You are continuously following the live 15-minute Bitcoin window through the app's live feed, which updates on every Kalshi tick, and through the window watcher. The live block below is that feed, including the tick series since the window opened, feed events, your recent live posts, and paper tweaks. Never tell the user to ping or message again for fresh data. Never say you cannot watch continuously."
    private val ET = ZoneId.of("America/New_York")
    private val ET_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a 'ET'", Locale.US).withZone(ET)

    /** Quotes, open paper positions, and the BTC print at the window open. Read at send time. */
    data class LiveWindow(
        val nowMs: Long,
        val quotes: List<ScalpRule.Quote>,
        val positions: List<ScalpTrade> = emptyList(),
        val btcAtOpen: Double? = null,
        /** On-phone model P(UP), 0–1. Null when the model has no call. */
        val modelUp: Double? = null
    )

    fun system(settingsDesc: String, context: String): String =
        "$MARKER inside Dirk's Kalshi Scalper app. Answer EVERY question directly in plain language, on any topic " +
        "(general knowledge, math, coding, markets, small talk) — never refuse for being off-topic. If a message is unclear (e.g. 'Huh'), " +
        "briefly say what you meant last or ask what they'd like to know. Use the trading context below only when relevant. " +
        "For BTC price questions at a strike/time with no matching 15-minute market, give your best estimate from the current BTC price, " +
        "the on-phone model forecast and time remaining, and say clearly that it is an estimate. " +
        "Never reply with a trade decision line like YES/NO/SKIP or 'No prediction'; the paper strategy runs separately and its status is not your answer. " +
        "You cannot place orders and can never touch real money; settings changes are paper-only and need the user's Apply tap. " +
        LIVE_FEED + "\n" +
        settingsDesc + "\nTrading context (live, may be partial):\n" + context

    fun build(question: String, history: List<Pair<String, String>>, settingsDesc: String, context: String): List<Pair<String, String>> =
        listOf("system" to system(settingsDesc, context)) + history + ("user" to question)

    fun isBtc15m(ticker: String) = ticker.uppercase().startsWith("KXBTC15M-")

    /** Soonest-closing open KXBTC15M strikes. The next window (later close) is not included. */
    fun currentStrikes(quotes: List<ScalpRule.Quote>, nowMs: Long): List<ScalpRule.Quote> {
        val open = quotes.filter { isBtc15m(it.ticker) && it.closeMs > nowMs }
        val close = open.minOfOrNull { it.closeMs } ?: return emptyList()
        return open.filter { it.closeMs == close }.sortedWith(compareBy({ it.strike ?: Double.MAX_VALUE }, { it.ticker }))
    }

    fun liveSpot(quotes: List<ScalpRule.Quote>): Double? {
        var best: ScalpRule.Quote? = null
        for (q in quotes) {
            val s = q.spot
            if (s == null || !s.isFinite() || s <= 0.0) continue
            if (best == null || q.bookAtMs >= best.bookAtMs) best = q
        }
        return best?.spot
    }

    /** At-the-money strike in this window (nearest the live spot). */
    fun atm(strikes: List<ScalpRule.Quote>, spot: Double?): ScalpRule.Quote? =
        strikes.minWithOrNull(compareBy<ScalpRule.Quote> { q ->
            val k = q.strike
            if (spot != null && k != null && k.isFinite()) kotlin.math.abs(spot - k) else Double.MAX_VALUE
        }.thenBy { it.ticker })

    /** GBM P(YES) when the asset is loaded, otherwise the spot-implied fair. */
    fun modelUp(q: ScalpRule.Quote?, dm60: Double): Double? {
        if (q == null) return null
        return GbmModel.pSide(q, "YES", dm60) ?: q.fair("YES")
    }

    fun liveWindow(w: LiveWindow): String {
        val strikes = currentStrikes(w.quotes, w.nowMs)
        if (strikes.isEmpty()) return "$LIVE_HEADER\n(no open KXBTC15M window)"
        val closeMs = strikes.first().closeMs
        val openMs = closeMs - MarketLifecycle.WINDOW_MS
        val key = MarketLifecycle.windowKey(strikes.first().ticker) ?: strikes.first().ticker.substringAfter('-').substringBefore('-')
        val spot = liveSpot(strikes)
        val atm = atm(strikes, spot)
        val leftS = ((closeMs - w.nowMs).coerceAtLeast(0L)) / 1000L
        val move = moveSinceOpen(spot, w.btcAtOpen)
        val up = w.modelUp?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it.coerceIn(0.0, 1.0)) } ?: "unavailable"
        val strikeLines = strikes.joinToString("\n") { q -> strikeLine(q, q.ticker == atm?.ticker) }
        val tickers = strikes.map { it.ticker.uppercase() }.toSet()
        val positions = w.positions.filter { p ->
            p.state != ScalpState.CLOSED && p.state != ScalpState.NO_FILL && isBtc15m(p.ticker) &&
                (p.ticker.uppercase() in tickers || p.windowKey.equals(key, true))
        }
        val posLines = if (positions.isEmpty()) "(none)" else positions.joinToString("\n") { p ->
            val px = p.entryPrice ?: p.signalAsk
            "${p.state} ${p.side} ${p.contracts} @ ${String.format(Locale.US, "%.2f", px)} ${p.ticker}"
        }
        return buildString {
            appendLine(LIVE_HEADER)
            appendLine("ticker: KXBTC15M-$key")
            appendLine("at the money: ${atm?.ticker ?: "unknown"}")
            appendLine("open: ${ET_FMT.format(Instant.ofEpochMilli(openMs))}")
            appendLine("close: ${ET_FMT.format(Instant.ofEpochMilli(closeMs))}")
            appendLine(String.format(Locale.US, "time remaining: %d:%02d (%ds)", leftS / 60, leftS % 60, leftS))
            appendLine("BTC price: ${spot?.let { money(it) } ?: "unknown"}")
            appendLine("move since window opened: $move")
            appendLine("model P(UP): $up")
            appendLine("open strikes:")
            appendLine(strikeLines)
            appendLine("open scalper positions in this window:")
            append(posLines)
        }
    }

    private fun strikeLine(q: ScalpRule.Quote, atm: Boolean): String {
        val strike = q.strike?.takeIf { it.isFinite() && it > 0.0 }?.let { money(it) } ?: "unknown"
        val mark = if (atm) " (at the money)" else ""
        return "${q.ticker}$mark strike $strike YES bid/ask ${px(q.yesBid)}/${px(q.yesAsk)} NO bid/ask ${px(q.bid("NO"))}/${px(q.ask("NO"))}"
    }

    private fun moveSinceOpen(spot: Double?, atOpen: Double?): String {
        if (spot == null || atOpen == null || !atOpen.isFinite() || atOpen <= 0.0 || !spot.isFinite()) return "unknown"
        val d = spot - atOpen
        val pct = d / atOpen * 100.0
        return String.format(Locale.US, "%s (%+.2f%%) from %s at the open", signedMoney(d), pct, money(atOpen))
    }

    private fun money(v: Double) = "$" + String.format(Locale.US, "%,.2f", v)
    private fun signedMoney(v: Double) = (if (v < 0) "-" else "+") + money(kotlin.math.abs(v))
    private fun px(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

    private val SNAPSHOT_CLAIM = Regex(
        """(?i)only\s+get\s+a\s+fresh\s+snapshot|fresh\s+snapshot|when\s+you\s+message|ping\s+again|can(?:not|'t|\s+not)\s+watch\s+continuously|as\s+of\s+this\s+message|next\s+message\s+has\s+the\s+new\s+window|only\s+see(?:s)?\s+(?:the\s+|a\s+)?snapshot"""
    )

    fun hasSnapshotClaim(text: String): Boolean = SNAPSHOT_CLAIM.containsMatchIn(text)

    /** Drop sentences that tell Dirk the model only sees a one-shot copy of the window. */
    fun withoutSnapshotClaims(text: String): String {
        if (text.isBlank() || !hasSnapshotClaim(text)) return text.trim()
        val kept = text.split(Regex("(?<=[.!?])\\s+")).filter { !hasSnapshotClaim(it) }
        return kept.joinToString(" ").trim()
    }

    /** About one row every 15s from window open, plus the latest tick. */
    fun sampledTicks(points: List<AiLivePoint>, openMs: Long, stepMs: Long = 15_000L): List<AiLivePoint> {
        val sorted = points.filter { it.atMs > 0L }.sortedBy { it.atMs }
        if (sorted.isEmpty() || stepMs <= 0L) return sorted
        val start = if (openMs > 0L) openMs else sorted.first().atMs
        val end = sorted.last().atMs
        if (end < start) return listOf(sorted.last())
        val out = ArrayList<AiLivePoint>()
        var idx = 0
        var t = start
        while (t <= end) {
            while (idx + 1 < sorted.size && sorted[idx + 1].atMs <= t) idx++
            val pick = if (sorted[idx].atMs <= t) sorted[idx] else sorted.first()
            if (out.isEmpty() || out.last().atMs != pick.atMs) out += pick
            val next = t + stepMs
            if (next <= t) break
            t = next
        }
        if (out.last().atMs != sorted.last().atMs) out += sorted.last()
        return out
    }

    fun tickHistory(live: AiLiveSnapshot?, tweaks: List<AiAdjustment> = emptyList(), posts: List<String> = emptyList()): String {
        if (live == null || live.ticker.isBlank()) return "Live tick series: no ticks yet."
        val openMs = if (live.closeMs > 0L) live.closeMs - com.dirk.kalshiodds.domain.MarketLifecycle.WINDOW_MS else live.points.firstOrNull()?.atMs ?: live.atMs
        val rows = sampledTicks(live.points, openMs)
        val series = if (rows.isEmpty()) "(no ticks yet)" else rows.joinToString("\n") { tickRow(it) }
        val events = feedEvents(live, openMs)
        val tweakLines = tweaks.takeLast(8).joinToString("\n") { t ->
            "${ET_FMT.format(Instant.ofEpochMilli(t.atMs))} ${t.scalper} ${t.setting} ${t.oldValue} → ${t.newValue}"
        }.ifBlank { "(none)" }
        val postLines = posts.map { withoutSnapshotClaims(it) }.filter { it.isNotBlank() }.takeLast(6).joinToString("\n").ifBlank { "(none)" }
        return buildString {
            appendLine("Live tick series since this window opened (about every 15s):")
            appendLine(series)
            appendLine("Feed events:")
            appendLine(events)
            appendLine("Recent live posts:")
            appendLine(postLines)
            appendLine("Recent paper tweaks:")
            append(tweakLines)
        }
    }

    private fun tickRow(p: AiLivePoint): String {
        val dist = if (p.btc != null && p.strike != null && p.btc.isFinite() && p.strike.isFinite()) p.btc - p.strike else null
        return listOf(
            ET_FMT.format(Instant.ofEpochMilli(p.atMs)),
            "BTC ${p.btc?.let { money(it) } ?: "—"}",
            "dist ${dist?.let { signedMoney(it) } ?: "—"}",
            "UP ${AiLiveFeed.centsLabel(p.up)}/${AiLiveFeed.centsLabel(p.upAsk)}",
            "DOWN ${AiLiveFeed.centsLabel(p.down)}/${AiLiveFeed.centsLabel(p.downAsk)}",
            "AI ${AiLiveFeed.upChanceLabel(p.modelUp)}"
        ).joinToString(" ")
    }

    private fun feedEvents(live: AiLiveSnapshot, openMs: Long): String {
        val pts = live.points.sortedBy { it.atMs }
        if (pts.isEmpty()) return "(none)"
        val lines = ArrayList<String>()
        lines += "window open ${ET_FMT.format(Instant.ofEpochMilli(openMs))} ${tickRow(pts.first())}"
        var lastEvent = pts.first().atMs
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (b.atMs - lastEvent < 15_000L) continue
            val btcJump = a.btc != null && b.btc != null && kotlin.math.abs(b.btc - a.btc) >= 10.0
            val oddsJump = a.up != null && b.up != null && kotlin.math.abs(b.up - a.up) >= 0.10
            if (btcJump || oddsJump) {
                lines += "big move ${tickRow(b)}"
                lastEvent = b.atMs
            }
        }
        if (live.closeMs > 0L && live.atMs >= live.closeMs) lines += "window close ${ET_FMT.format(Instant.ofEpochMilli(live.closeMs))}"
        return lines.joinToString("\n")
    }
}
