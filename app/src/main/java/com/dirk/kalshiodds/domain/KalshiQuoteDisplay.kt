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

    /**
     * Kalshi-app payout multiple on the buy button.
     *
     * Gross `1/ask` for 64¢ is 1.56x; the official app shows **1.52x / 2.59x**
     * because the taker fee (`0.07 × P × (1−P)`) is included in the cost:
     * `1 / (ask + fee)`. Match that so the hero is identical to Kalshi.
     */
    fun multiplier(ask: Double?, includeFee: Boolean = true, feeRate: Double = 0.07): Double? {
        val p = KalshiPrice.usable(ask) ?: return null
        if (p <= 0.0) return null
        val fee = if (includeFee) (feeRate.coerceIn(0.0, 0.25) * p * (1.0 - p)).coerceAtLeast(0.0) else 0.0
        val cost = p + fee
        if (cost <= 0.0) return null
        return 1.0 / cost
    }

    /** Gross `1/ask` without the taker fee (tests / EV math). */
    fun grossMultiplier(ask: Double?): Double? = multiplier(ask, includeFee = false)

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
