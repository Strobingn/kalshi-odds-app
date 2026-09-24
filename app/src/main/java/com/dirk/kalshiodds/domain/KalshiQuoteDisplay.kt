package com.dirk.kalshiodds.domain

import java.util.Locale

/**
 * Kalshi-app display: "Up 64¢ · 1.52x" from the **best ask**,
 * multiplier = 1 / ask. Never uses the AI probability as if it were the market.
 */
object KalshiQuoteDisplay {

    fun cents(ask: Double?): Int? {
        val p = KalshiPrice.usable(ask) ?: return null
        return kotlin.math.round(p * 100.0).toInt().coerceIn(1, 99)
    }

    fun multiplier(ask: Double?): Double? {
        val p = KalshiPrice.usable(ask) ?: return null
        if (p <= 0.0) return null
        return 1.0 / p
    }

    fun impliedChance(ask: Double?): Double? = KalshiPrice.usable(ask)

    fun buttonLabel(up: Boolean, ask: Double?): String {
        val c = cents(ask)
        val m = multiplier(ask)
        val side = if (up) "Up" else "Down"
        if (c == null || m == null) return if (up) "Buy UP" else "Buy DOWN"
        return String.format(Locale.US, "%s %d¢ · %.2fx", side, c, m)
    }

    fun targetNowLine(strike: Double?, spot: Double?): String? {
        if (strike == null || !strike.isFinite() || strike <= 0.0) return null
        if (spot == null || !spot.isFinite() || spot <= 0.0) {
            return String.format(Locale.US, "Target $%,.2f", strike)
        }
        val d = spot - strike
        val arrow = if (d >= 0) "▲" else "▼"
        return String.format(Locale.US, "Target $%,.2f · Now $%,.2f %s $%,.2f", strike, spot, arrow, kotlin.math.abs(d))
    }

    fun aiLabel(yesProb01: Double?): String? {
        val p = yesProb01?.takeIf { it.isFinite() } ?: return null
        val pct = (p * 100.0).coerceIn(2.0, 98.0)
        val side = if (pct >= 50.0) "UP" else "DOWN"
        return String.format(Locale.US, "AI: %.0f%% %s", if (pct >= 50.0) pct else 100.0 - pct, side)
    }
}
