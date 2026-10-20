package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpTrade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 1.0.9 "Ask the AI why": builds a prefilled question from a paper trade and hands it to the AI tab,
 * which sends it once. Pure text — never places or changes an order.
 */
object AskAiWhy {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending

    fun ask(question: String) { _pending.value = question }

    /** Returns the pending question exactly once (null afterwards). */
    @Synchronized fun take(): String? = _pending.value.also { _pending.value = null }

    private fun c(p: Double?) = p?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"

    fun question(scalper: String, t: ScalpTrade): String {
        val exit = if (t.soldContracts > 0) t.proceedsUsd / t.soldContracts else null
        val side = if (t.side.equals("YES", true)) "UP / YES" else "DOWN / NO"
        val net = t.netUsd?.let { String.format(Locale.US, "%+.2f", it) } ?: "open"
        return buildString {
            append("Why did this paper trade ").append(if ((t.netUsd ?: 0.0) > 0) "win" else if ((t.netUsd ?: 0.0) < 0) "lose" else "turn out this way").append("? ")
            append("Scalper: ").append(scalper).append("; market ").append(t.ticker).append("; side ").append(side).append("; ")
            append(t.contracts).append(" ct; entry ").append(c(t.entryPrice)).append(" (signal ask ").append(c(t.signalAsk)).append("); exit ").append(c(exit)).append("; ")
            append("model prob at signal ").append(String.format(Locale.US, "%.0f%%", t.fairAtSignal * 100)).append("; ")
            append("signal time ").append(com.dirk.kalshiodds.domain.EtTime.dateClockSec(t.signalAtMs))
            t.closedAtMs?.let { append(", closed ").append(com.dirk.kalshiodds.domain.EtTime.dateClockSec(it)) }
            append("; net after fees $").append(net)
            t.exitReason?.let { append("; exit reason: ").append(it.take(120)) }
            append(". What drove the outcome and what should this scalper do differently?")
        }
    }
}
