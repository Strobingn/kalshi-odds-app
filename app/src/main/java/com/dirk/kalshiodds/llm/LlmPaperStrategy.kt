package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The single PAPER "LLM" strategy. It may decide again through the open BTC window, except the last 60 seconds.
 * Automatic calls run only when "AI calls on its own" is on, and only on a free provider
 * (Mistral ministral-8b-latest, Groq, Gemini), paced to that provider's quota. A paid model never calls itself.
 * The LLM only proposes YES/NO/SKIP + confidence; code enforces every hard rule around it.
 * Output only ever creates a paper ScalpTrade — there is no path from here to the real-order client.
 */
class LlmPaperStrategy(
    private val book: ScalpBook,
    private val settings: LlmSettings,
    private val client: LlmClient = LlmClient(settings),
    private val onUsage: (LlmEndpoint, LlmClient.Reply) -> Unit = { _, _ -> },
    private val autoCalls: () -> Boolean = { true },
    private val endpoint: () -> LlmEndpoint = { settings.chatEndpoint() },
    private val pacer: FreeCallPacer = FreeCallPacer(),
    private val alternates: () -> List<LlmEndpoint> = { emptyList() },
    private val quotaOf: (LlmEndpoint) -> FreeQuota? = { LlmProviders.baseQuota(it.provider, it.model) },
    private val onBilling: (String, String) -> Unit = { _, _ -> },
    private val onProviderSwitch: (String, String, String) -> Unit = { _, _, _ -> },
    private val allowed: (LlmEndpoint) -> Boolean = { autoCalls() && LlmProviders.freeFamily(it.provider, it.model) },
    private val callsToday: (LlmEndpoint) -> Int = { 0 }
) {
    private val _status = MutableStateFlow("needs key")
    val status: StateFlow<String> = _status
    /** 1.0.9 strong-AI-call alert hook: (callId, "UP"/"DOWN", confidence 0–1). Notification only. */
    @Volatile var onCall: ((String, String, Double) -> Unit)? = null
    private val loop = LlmPaperLoop(
        autoCalls = autoCalls,
        hasKey = { endpoint().usable },
        quote = {
            book.marks.value.values.filter { it.ticker.uppercase().contains("BTC") && it.closeMs > it.nowMs }.minByOrNull { it.closeMs }
        },
        call = { messages -> perform(messages) },
        place = { q, side, conf -> book.llmEntry(q, side, conf) },
        onUsage = onUsage,
        onCall = { id, dir, conf -> onCall?.invoke(id, dir, conf) },
        freeProvider = {
            val ep = endpoint()
            ep.provider == LlmProviders.BUILTIN || ep.usable
        },
        onPaceMiss = { paceStatus }
    )
    @Volatile private var sleepMs: Long = CYCLE_MS
    @Volatile private var paceStatus: String = "waiting for the free-tier pace"
    @Volatile private var lastAutoAt = Long.MIN_VALUE

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching { loop.tick()?.let { _status.value = it } }
                .onFailure { _status.value = "error (${settings.model}, no fallback model): ${it.message?.take(120)}" }
            // Kalshi conflator cycle. The pacer, not this delay, keeps the call inside the free quota.
            delay(sleepMs.coerceIn(CYCLE_MS, AiLiveFeed.MIN_SPACING_MS))
        }
    }

    private fun perform(messages: List<Pair<String, String>>): Pair<LlmEndpoint, LlmClient.Reply>? {
        val ep = endpoint()
        val builtin = ep.provider == LlmProviders.BUILTIN
        if (!builtin && !ep.usable) return null
        val nowMs = System.currentTimeMillis()
        if (!builtin && lastAutoAt != Long.MIN_VALUE && nowMs - lastAutoAt < AiLiveFeed.MIN_SPACING_MS) {
            sleepMs = (AiLiveFeed.MIN_SPACING_MS - (nowMs - lastAutoAt)).coerceIn(CYCLE_MS, AiLiveFeed.MIN_SPACING_MS)
            paceStatus = "waiting for the free-tier pace"
            return null
        }
        val messagesTokens = AiCompact.tokens(messages, 20)
        val slots = listOf(ep) + alternates()
        val hit = AiAutoCalls.callFree(
            selected = ep,
            slots = alternates(),
            pacer = pacer,
            nowMs = nowMs,
            messages = messages,
            reserveCompletion = 20,
            quotaOf = { ep ->
                if (!MonthlyTokenCaps.room(ep.provider, LlmPrices.monthKey(System.currentTimeMillis()))) null
                else quotaOf(ep)
            },
            allowed = { this.allowed(it) },
            ask = { use, msgs -> client.chatReply(msgs, 20, use, automatic = true) },
            onBilling = onBilling,
            onFallback = { from, to, reason -> onProviderSwitch(from.provider, to.provider, reason) },
            callsToday = callsToday
        )
        if (hit != null) {
            lastAutoAt = nowMs
            sleepMs = AiLiveFeed.MIN_SPACING_MS
            paceStatus = ""
            return hit
        }
        val wait = AiAutoCalls.explainWait(
            endpoints = slots,
            pacer = pacer,
            nowMs = nowMs,
            tokens = messagesTokens,
            quotaOf = { qep ->
                if (!MonthlyTokenCaps.room(qep.provider, LlmPrices.monthKey(nowMs))) null
                else quotaOf(qep)
            },
            allowed = { this.allowed(it) }
        )
        sleepMs = ((wait?.resumeAtMs ?: nowMs) - nowMs).coerceIn(CYCLE_MS, AiLiveFeed.MIN_SPACING_MS)
        paceStatus = AiAutoCalls.paceStatus(wait, nowMs).ifBlank { "" }
        return null
    }

    companion object {
        const val LLM_OPEN_MIN = 3.0
        /** Last minute of the window: no new paper decision. The live feed on the card is still running. */
        const val PAST_OPEN_STATUS = "watching this window; paper decisions pause in the last 60 seconds. Live feed still running."
        const val FREE_ONLY_STATUS = "automatic calls stay on free providers only (Mistral, Groq, Gemini)"
        /** Same gap as the Kalshi quote conflator. The free pacer decides whether a call actually goes out. */
        const val CYCLE_MS = 150L
        const val STRATEGY_PROMPT = "Kalshi BTC 15-minute market %s. Strike %.2f, BTC spot %.2f, minutes left %.1f. YES bid/ask %s/%s. Model fair YES %s. " +
            "Paper trade only. Rules: buy at most 55c, 3c trailing stop. Reply ONE line: 'YES <conf 0-1>' or 'NO <conf 0-1>' or 'SKIP'."
        fun parse(reply: String): Pair<String, Double>? {
            val m = Regex("\\b(YES|NO)\\b\\s*([01](?:\\.\\d+)?)?", RegexOption.IGNORE_CASE).find(reply.trim()) ?: return null
            return m.groupValues[1].uppercase() to (m.groupValues[2].toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        }
    }
}

/**
 * Repeated paper decisions through the window, except the last 60 seconds.
 * No provider call when automatic calls are off, and never an automatic paid call.
 * A free-tier pace miss does not consume the window. History of the last decision window is kept.
 */
internal class LlmPaperLoop(
    private val autoCalls: () -> Boolean,
    private val hasKey: () -> Boolean,
    private val quote: () -> ScalpRule.Quote?,
    private val call: (List<Pair<String, String>>) -> Pair<LlmEndpoint, LlmClient.Reply>?,
    private val place: (ScalpRule.Quote, String, Double) -> String?,
    private val onUsage: (LlmEndpoint, LlmClient.Reply) -> Unit,
    private val onCall: (String, String, Double) -> Unit,
    private val freeProvider: () -> Boolean = { true },
    private val onPaceMiss: () -> String = { "waiting for the free-tier pace" }
) {
    var lastWindow: String? = null

    /** Null means the visible status stays as it was (pace miss or overlap). */
    fun tick(): String? {
        if (!autoCalls()) return AiAutoCalls.PAUSED
        if (!hasKey()) return "needs key"
        if (!freeProvider()) return LlmPaperStrategy.FREE_ONLY_STATUS
        val q = quote() ?: return "idle: no BTC quote"
        if (q.tauS <= 60.0) return LlmPaperStrategy.PAST_OPEN_STATUS
        val prompt = String.format(Locale.US, LlmPaperStrategy.STRATEGY_PROMPT,
            q.ticker, q.strike ?: 0.0, q.spot ?: 0.0, q.tauS / 60, q.yesBid?.let { "%.2f".format(Locale.US, it) } ?: "-", q.yesAsk?.let { "%.2f".format(Locale.US, it) } ?: "-",
            q.fair("YES")?.let { "%.2f".format(Locale.US, it) } ?: "-")
        val messages = listOf("system" to "You are a cautious BTC scalping assistant.", "user" to prompt)
        val hit = try {
            call(messages)
        } catch (e: LlmSkipped) {
            return null
        } ?: return onPaceMiss().ifBlank { null }
        val (used, reply) = hit
        lastWindow = q.ticker
        runCatching { onUsage(used, reply) }
        val parsed = LlmPaperStrategy.parse(reply.text)
        parsed?.let { (side, conf) -> runCatching { onCall(q.ticker, if (side.equals("YES", true)) "UP" else "DOWN", conf) } }
        return "model ${reply.model} · " + (if (parsed == null) "skipped ${q.ticker}: ${reply.text.take(40)}" else {
            val placed = place(q, parsed.first, parsed.second)
            "${q.ticker}: ${parsed.first} conf ${"%.2f".format(Locale.US, parsed.second)} → ${placed ?: "paper entry queued"}"
        })
    }
}
