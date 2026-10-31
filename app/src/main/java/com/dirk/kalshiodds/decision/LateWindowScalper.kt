package com.dirk.kalshiodds.decision

import java.util.Locale

/**
 * Paper scalper that only opens inside the last minutes of a 15-minute window.
 * It uses the Hybrid Quant entry filter with the hybrid gate on and scores above
 * 0.65 dropped. AI Learn does not move its window or its factory defaults.
 */
object LateWindowScalper {
    const val CODE = "late5"
    const val LABEL = "Late window (<5 min)"
    const val PROMOTE_TRADES = 200
    const val DEFAULT_MINUTES = 5
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 5

    private var minutes = DEFAULT_MINUTES

    fun matches(id: String?): Boolean = id == CODE

    fun minutes(): Int = minutes

    /** Owner control. Clamped to 1–5. AI Learn does not call this. */
    fun setMinutes(raw: Int) {
        minutes = raw.coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun readMinutes(raw: Int) {
        minutes = if (raw in MIN_MINUTES..MAX_MINUTES) raw else DEFAULT_MINUTES
    }

    /** AI path. The stored window does not move. */
    fun applyAiMinutes(requested: Int): String {
        return AdviceApply.PROTECTED + "Late window time-left stays ${minutes()} min (asked for $requested)"
    }

    fun windowSeconds(mins: Int = minutes()): Double = mins.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60.0

    /** Strictly under the configured minutes. Exactly 5:00 is outside a 5-minute window. */
    fun inWindow(tauS: Double, mins: Int = minutes()): Boolean =
        tauS.isFinite() && tauS > 0.0 && tauS + 1e-9 < windowSeconds(mins)

    /** Stored highest buy, or the 95¢ factory max. Never above the late-window ceiling. */
    fun bandMaxCents(maxCents: Int? = null): Int =
        (maxCents ?: PaperFleetDefaults.LATE_BUY_MAX).coerceIn(PaperFleetDefaults.BUY_MIN + 1, PaperFleetDefaults.LATE_BUY_MAX)

    fun askOk(ask: Double, maxCents: Int? = null): Boolean {
        val cap = bandMaxCents(maxCents) / 100.0
        return ask.isFinite() && ask + 1e-9 >= PaperFleetDefaults.BUY_MIN / 100.0 && ask <= cap + 1e-9
    }

    /**
     * Bid that banks the 25¢ take-profit. A 95¢ entry cannot pay 120¢, so the
     * target stops at [PaperFleetDefaults.PAYOUT_CENTS]. Below that, the position holds to settlement.
     */
    fun takeProfitBid(entry: Double, profitCents: Int = PaperFleetDefaults.PROFIT_CENTS): Double {
        if (!entry.isFinite() || entry <= 0.0) return PaperFleetDefaults.PAYOUT_CENTS / 100.0
        return (entry + profitCents / 100.0).coerceAtMost(PaperFleetDefaults.PAYOUT_CENTS / 100.0)
    }

    fun coins(hybrid: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        out += "BTC"
        for (c in hybrid) {
            val u = c.trim().uppercase(Locale.US)
            if (u.isNotEmpty()) out += u
        }
        return out
    }

    fun tradesCoin(coin: String, hybrid: Set<String>): Boolean = coin.uppercase(Locale.US) in coins(hybrid)

    /**
     * Null when the entry may open. Otherwise a short reason.
     * [modelEnter] is the Hybrid Quant decision with the gate on and the 0.65 ceiling applied.
     */
    fun blockReason(
        coin: String,
        hybridCoins: Set<String>,
        tauS: Double,
        ask: Double,
        score: Double,
        modelEnter: Boolean,
        modelReason: String,
        mins: Int = minutes(),
        maxCents: Int? = null
    ): String? {
        if (!tradesCoin(coin, hybridCoins)) return "coin $coin is outside the Hybrid Quant set"
        if (!inWindow(tauS, mins)) return "outside late window · under ${mins.coerceIn(MIN_MINUTES, MAX_MINUTES)} min"
        if (!askOk(ask, maxCents)) return "outside ${PaperFleetDefaults.BUY_MIN}–${bandMaxCents(maxCents)}¢ buy band"
        if (!score.isFinite() || score > ModelFilter.HIGH_SCORE) return "high-score regime · score above ${ModelFilter.HIGH_SCORE}"
        if (!modelEnter) return modelReason.ifBlank { "hybrid gate blocked" }
        return null
    }

    fun progress(closed: Int): Float =
        (closed.coerceAtLeast(0).toFloat() / PROMOTE_TRADES.toFloat()).coerceIn(0f, 1f)

    fun promoteLabel(closed: Int): String {
        val n = closed.coerceAtLeast(0)
        return if (n >= PROMOTE_TRADES) "Ready to promote · $n trades"
        else "needs $PROMOTE_TRADES trades · $n/$PROMOTE_TRADES"
    }

    data class Slice(val name: String, val n: Int, val wins: Int, val netUsd: Double)

    /** Closed-trade regime line. Empty input is a blank string. */
    fun regimeLine(rows: List<Pair<String, Double>>): String {
        if (rows.isEmpty()) return ""
        val acc = LinkedHashMap<String, Slice>()
        for ((name, net) in rows) {
            val key = name.ifBlank { "unspecified" }
            val prev = acc[key]
            val win = if (net > 0.0) 1 else 0
            acc[key] = if (prev == null) Slice(key, 1, win, net)
            else prev.copy(n = prev.n + 1, wins = prev.wins + win, netUsd = prev.netUsd + net)
        }
        return acc.values.joinToString(" · ") { s ->
            String.format(Locale.US, "%s %d/%d %+.2f", s.name, s.wins, s.n, s.netUsd)
        }
    }
}
