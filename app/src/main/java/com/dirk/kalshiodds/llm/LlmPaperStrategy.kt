package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The single PAPER "LLM" strategy. At most ONE LLM call per BTC 15-minute window, in the first 3 minutes.
 * The LLM only proposes YES/NO/SKIP + confidence; code enforces every hard rule around it ([ScalpBook.llmEntry]
 * + the V2 lane: ask ≤ 55¢, window start only, 3¢ trail below peak bid, no hold to close unless > 95% confidence).
 * Output only ever creates a paper ScalpTrade — there is no path from here to the real-order client.
 */
class LlmPaperStrategy(private val book: ScalpBook, private val settings: LlmSettings, private val client: LlmClient = LlmClient(settings)) {
    private val _status = MutableStateFlow("needs key")
    val status: StateFlow<String> = _status
    private var lastWindow: String? = null

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            runCatching { tick() }.onFailure { _status.value = "error (${settings.model}, no fallback model): ${it.message?.take(120)}" }
            delay(15_000)
        }
    }

    private suspend fun tick() {
        if (!settings.hasKey) { _status.value = "needs key"; return }
        val q = book.marks.value.values.filter { it.ticker.uppercase().contains("BTC") && it.closeMs > it.nowMs }.minByOrNull { it.closeMs } ?: run { _status.value = "idle: no BTC quote"; return }
        val minuteIn = 15.0 - q.tauS / 60.0
        if (q.ticker == lastWindow) return
        if (minuteIn > LLM_OPEN_MIN) { _status.value = "idle until next window start"; return }
        lastWindow = q.ticker // rate limit: one decision per window, even on failure
        val prompt = String.format(Locale.US, STRATEGY_PROMPT,
            q.ticker, q.strike ?: 0.0, q.spot ?: 0.0, q.tauS / 60, q.yesBid?.let { "%.2f".format(Locale.US, it) } ?: "-", q.yesAsk?.let { "%.2f".format(Locale.US, it) } ?: "-",
            q.fair("YES")?.let { "%.2f".format(Locale.US, it) } ?: "-")
        val r = withContext(Dispatchers.IO) { client.chatReply(listOf("system" to "You are a cautious BTC scalping assistant.", "user" to prompt), 20) }
        val reply = r.text
        val d = parse(reply)
        _status.value = "model ${r.model} · " + (if (d == null) "skipped ${q.ticker}: ${reply.take(40)}" else {
            val placed = book.llmEntry(q, d.first, d.second)
            "${q.ticker}: ${d.first} conf ${"%.2f".format(Locale.US, d.second)} → ${placed ?: "paper entry queued"}"
        })
    }

    companion object {
        const val LLM_OPEN_MIN = 3.0
        const val STRATEGY_PROMPT = "Kalshi BTC 15-minute market %s. Strike %.2f, BTC spot %.2f, minutes left %.1f. YES bid/ask %s/%s. Model fair YES %s. " +
            "Paper trade only. Rules: buy at most 55c, 3c trailing stop. Reply ONE line: 'YES <conf 0-1>' or 'NO <conf 0-1>' or 'SKIP'."
        fun parse(reply: String): Pair<String, Double>? {
            val m = Regex("\\b(YES|NO)\\b\\s*([01](?:\\.\\d+)?)?", RegexOption.IGNORE_CASE).find(reply.trim()) ?: return null
            return m.groupValues[1].uppercase() to (m.groupValues[2].toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        }
    }
}
