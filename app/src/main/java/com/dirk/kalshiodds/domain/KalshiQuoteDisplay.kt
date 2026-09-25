package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Kalshi-app display: "Up 64¢ · 1.52x" from the **best ask**.
 *
 * Multiple = settlement / total cost on the default card stake
 * (`C × $1 / (C×P + orderFee)`, `C = floor(stake / P)`).
 * Fee is paid on top of the purchase, not taken from the $1 payout.
 * Never uses the AI probability. 0¢ and missing asks return null.
 */
object KalshiQuoteDisplay {

    /** Whole cents only. Sub-cent asks use [formatAsk], never this clamp. */
    fun cents(ask: Double?): Int? {
        val p = KalshiPrice.usable(ask) ?: return null
        val c = kotlin.math.round(p * 100.0).toInt()
        return c.takeIf { it in 1..99 && kotlin.math.abs(p * 100.0 - c) < 1e-9 }
    }

    /**
     * Payout multiple at [stakeUsd] (default $5 ticket).
     * 1¢ @ $5 → ~93.46x (not 99x). Null for 0¢ / no-ask.
     */
    fun multiplier(
        ask: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Double? {
        val p = ask ?: return null
        return KalshiFee.payoutMultiple(p, stakeUsd, feeRate)
    }

    /** @deprecated Use [multiplier]; fee is always the order-level KalshiFee. */
    fun multiplier(ask: Double?, includeFee: Boolean, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double? {
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

    /**
     * Exact tick string: `0.1¢`, `1.5¢`, `1.8¢`, `12¢`, `99.9¢`.
     * Never rounds 1.5¢ → `2¢` or 0.1¢ → `0¢` / `1¢`.
     */
    fun formatPriceCents(price: Double): String {
        if (!price.isFinite()) return "—"
        val cents = BigDecimal.valueOf(price)
            .setScale(4, RoundingMode.HALF_UP)
            .movePointRight(2)
            .stripTrailingZeros()
        return if (cents.scale() <= 0) {
            "${cents.toPlainString()}¢"
        } else {
            String.format(Locale.US, "%.1f¢", cents.toDouble())
        }
    }

    /**
     * Always a visible string: `"62.36x"`, `"no ask"`, or `"can't size $5"`.
     * Never a blank — 10¢+ asks must not disappear.
     */
    fun multipleLabel(
        ask: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): String {
        if (KalshiPrice.usable(ask) == null) return "no ask"
        val m = multiplier(ask, feeRate, stakeUsd) ?: return "can't size $${stakeUsd.toInt()}"
        return String.format(Locale.US, "%.2fx", m)
    }

    fun buttonLabel(
        up: Boolean,
        ask: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): String {
        val price = formatAsk(ask)
        val m = multiplier(ask, feeRate, stakeUsd)
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
