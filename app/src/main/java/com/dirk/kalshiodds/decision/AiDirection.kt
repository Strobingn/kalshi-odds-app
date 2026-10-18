package com.dirk.kalshiodds.decision

/**
 * 1.0.4 home banner: the on-phone GBM's live direction call for the current BTC 15-minute window — the same
 * [GbmModel.pSide] the strategies use (never the LLM). UP = P(YES settles) > 50%. Calls within [NEUTRAL_BAND]
 * of 50%, or with no forecast, are "No clear call".
 */
object AiDirection {
    const val NEUTRAL_BAND = 0.05

    enum class Call { UP, DOWN, NONE }
    data class Forecast(val call: Call, val confidence: Double?, val secondsLeft: Long?, val ticker: String?)

    /** Current (soonest-closing, still-open) BTC window from the scalper's live marks. */
    fun current(marks: Map<String, ScalpRule.Quote>, nowMs: Long): ScalpRule.Quote? =
        marks.values.filter { it.ticker.uppercase().contains("BTC") && it.closeMs > nowMs }.minByOrNull { it.closeMs }

    fun forecast(q: ScalpRule.Quote?, nowMs: Long, pYes: (ScalpRule.Quote) -> Double?): Forecast {
        if (q == null) return Forecast(Call.NONE, null, null, null)
        val left = ((q.closeMs - nowMs) / 1000).coerceAtLeast(0)
        val p = runCatching { pYes(q) }.getOrNull()?.takeIf { it.isFinite() } ?: return Forecast(Call.NONE, null, left, q.ticker)
        val conf = maxOf(p, 1 - p)
        val call = when { conf - 0.5 < NEUTRAL_BAND -> Call.NONE; p > 0.5 -> Call.UP; else -> Call.DOWN }
        return Forecast(call, conf, left, q.ticker)
    }

    fun label(f: Forecast) = when (f.call) { Call.UP -> "AI says UP"; Call.DOWN -> "AI says DOWN"; Call.NONE -> "No clear call" }
    fun timeLeft(s: Long?) = s?.let { "%d:%02d left".format(it / 60, it % 60) } ?: "no live window"
}
