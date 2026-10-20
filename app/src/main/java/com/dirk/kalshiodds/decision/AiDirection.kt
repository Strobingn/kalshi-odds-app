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

    /** A quote whose book is older than this is stale: no call. */
    const val MAX_BOOK_AGE_MS = 30_000L

    /**
     * Current BTC window from the scalper's live marks: the soonest-closing open window, and within it the strike
     * nearest spot (the at-the-money contract). An exact distance tie uses the strike nearest [openPx], then gives
     * up — ticker order is not a tie break, because it prefers one side of the ladder.
     */
    fun current(marks: Map<String, ScalpRule.Quote>, nowMs: Long, openPx: Double? = null): ScalpRule.Quote? {
        val open = marks.values.filter { it.ticker.uppercase().contains("BTC") && it.closeMs > nowMs }
        val close = open.minOfOrNull { it.closeMs } ?: return null
        val window = open.filter { it.closeMs == close }
        val fresh = window.filter { nowMs - it.bookAtMs <= MAX_BOOK_AGE_MS }.ifEmpty { return window.maxByOrNull { it.bookAtMs } } // stale → forecast() says no call
        // One spot for every strike: the freshest quote's, so an old per-quote spot cannot make a stale strike look ATM.
        val spot = fresh.filter { it.spot != null }.maxByOrNull { it.bookAtMs }?.spot
        return AtmStrike.pick(fresh, { q -> AtmStrike.priceDistance(q.strike, spot) }, { q -> AtmStrike.priceDistance(q.strike, openPx) })
    }

    fun forecast(q: ScalpRule.Quote?, nowMs: Long, pYes: (ScalpRule.Quote) -> Double?): Forecast {
        if (q == null) return Forecast(Call.NONE, null, null, null)
        val left = ((q.closeMs - nowMs) / 1000).coerceAtLeast(0)
        if (nowMs - q.bookAtMs > MAX_BOOK_AGE_MS) return Forecast(Call.NONE, null, left, q.ticker) // frozen feed
        val p = runCatching { pYes(q) }.getOrNull()?.takeIf { it.isFinite() } ?: return Forecast(Call.NONE, null, left, q.ticker)
        val conf = maxOf(p, 1 - p)
        val call = when { conf - 0.5 < NEUTRAL_BAND -> Call.NONE; p > 0.5 -> Call.UP; else -> Call.DOWN }
        return Forecast(call, conf, left, q.ticker)
    }

    fun label(f: Forecast) = when (f.call) { Call.UP -> "AI says UP"; Call.DOWN -> "AI says DOWN"; Call.NONE -> "No clear call" }
    fun timeLeft(s: Long?) = s?.let { "%d:%02d left".format(it / 60, it % 60) } ?: "no live window"
}

/**
 * At-the-money strike. Nearest the live spot wins. An exact tie goes to the strike nearest the window open,
 * and a remaining tie picks neither side.
 */
object AtmStrike {
    fun priceDistance(price: Double?, level: Double?): Double {
        if (price == null || level == null || !price.isFinite() || !level.isFinite()) return Double.POSITIVE_INFINITY
        return kotlin.math.abs(price - level)
    }

    fun <T> pick(items: List<T>, distToSpot: (T) -> Double, distToOpen: (T) -> Double): T? {
        if (items.isEmpty()) return null
        val nearest = items.minOf(distToSpot)
        val atSpot = items.filter { distToSpot(it) == nearest }
        if (atSpot.size == 1) return atSpot.first()
        val nearestOpen = atSpot.minOf(distToOpen)
        if (nearestOpen == Double.POSITIVE_INFINITY) return null
        val atOpen = atSpot.filter { distToOpen(it) == nearestOpen }
        return if (atOpen.size == 1) atOpen.first() else null
    }
}
