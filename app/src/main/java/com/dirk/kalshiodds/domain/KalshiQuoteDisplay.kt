package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale

/**
 * Kalshi-app display: "Up 64¢ · 1.53x" from the **best ask**.
 *
 * Multiple = [KalshiFee.netPayout] per dollar staked at that ask
 * (`(1 − perContract fee) / ask`). Never uses the AI probability.
 * 0¢ and missing asks return null — never divide by zero.
 */
object KalshiQuoteDisplay {

    fun cents(ask: Double?): Int? {
        val p = KalshiPrice.usable(ask) ?: return null
        val c = kotlin.math.round(p * 100.0).toInt()
        return c.takeIf { it in 1..99 }
    }

    /**
     * Net payout per dollar staked at [ask], using [KalshiFee.perContract]
     * (documented `round_up(0.07 × P × (1 − P))` to the next cent).
     *
     * 1¢ → 99.00x, 99¢ → 1.00x. Null for 0¢ / no-ask.
     */
    fun multiplier(ask: Double?, feeRate: Double = 0.07): Double? {
        val p = KalshiPrice.usable(ask) ?: return null
        if (p <= 0.0) return null
        val fee = KalshiFee.perContract(p, feeRate)
        val net = (1.0 - fee).coerceAtLeast(0.0)
        return net / p
    }

    /** @deprecated Use [multiplier]; fee is always KalshiFee.perContract. */
    fun multiplier(ask: Double?, includeFee: Boolean, feeRate: Double = 0.07): Double? {
        if (!includeFee) return grossMultiplier(ask)
        return multiplier(ask, feeRate)
    }

    /** Gross `1/ask` without the taker fee (tests / EV math). */
    fun grossMultiplier(ask: Double?): Double? {
        val p = KalshiPrice.usable(ask) ?: return null
        if (p <= 0.0) return null
        return 1.0 / p
    }

    fun impliedChance(ask: Double?): Double? = KalshiPrice.usable(ask)

    fun formatAsk(ask: Double?): String {
        val p = KalshiPrice.usable(ask) ?: return "—"
        return formatPriceCents(p)
    }

    fun formatBid(bid: Double?): String {
        if (bid == null || !bid.isFinite()) return "—"
        if (bid <= 0.0 + 1e-12) return "—"
        if (bid >= 1.0 - 1e-12) return "100¢"
        return formatPriceCents(bid)
    }

    fun formatPriceCents(price: Double): String {
        val c = price * 100.0
        return if (c + 1e-9 < 1.0) {
            String.format(Locale.US, "%.1f¢", c)
        } else {
            String.format(Locale.US, "%.0f¢", c)
        }
    }

    fun buttonLabel(up: Boolean, ask: Double?, feeRate: Double = 0.07): String {
        val price = formatAsk(ask)
        val m = multiplier(ask, feeRate)
        val side = if (up) "Up" else "Down"
        if (price == "—" || m == null) return if (up) "Buy UP" else "Buy DOWN"
        return String.format(Locale.US, "%s %s · %.2fx", side, price, m)
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
