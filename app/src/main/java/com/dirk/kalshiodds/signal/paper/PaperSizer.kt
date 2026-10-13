package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor
import kotlin.math.min

/**
 * How much of its paper cash the AI puts on one bet. **Paper money only.**
 *
 * The AI stakes a quarter of the Kelly fraction of its cash, and never
 * more than [MAX_FRACTION] (10%) on one bet. Full Kelly is only the
 * fastest-growing size when the probability is right; this model's
 * probabilities have lost to Kalshi's price, so full Kelly just magnifies a
 * bad forecast. No edge (q ≤ cost including the taker fee) means no bet.
 * For a contract bought at price p with fee f, and a win chance q:
 *
 *   cost c = p + f,  edge fraction = (q − c) / (1 − c),
 *   stake = cash × min([MAX_FRACTION], [KELLY_MULTIPLIER] × fraction)
 *
 * A lost bet cannot wipe the paper account. Reset puts it back to $100.
 */
object PaperSizer {
    /**
     * Cap on the paper cash the AI may stake on one bet. A model that merely
     * matches the market makes large-stake paper results mostly luck; 10%
     * keeps variance honest while still letting a real edge compound.
     */
    const val MAX_FRACTION = 0.10

    /** Quarter Kelly: survives an overconfident win probability. */
    const val KELLY_MULTIPLIER = 0.25

    /** Kelly fraction of cash for a buy at [price] with win chance [winProb], or 0 with no edge. */
    fun fraction(
        price: Double,
        winProb: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double {
        val q = winProb?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return 0.0
        if (!price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        val c = price + feeRate * price * (1.0 - price)
        if (c >= 1.0 || q <= c) return 0.0
        return min(MAX_FRACTION, KELLY_MULTIPLIER * (q - c) / (1.0 - c)).coerceAtLeast(0.0)
    }

    /** Whole contracts to buy, or 0 when there is no edge. Never more than cash covers. */
    fun contracts(
        cashUsd: Double,
        price: Double,
        winProb: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Int {
        if (!cashUsd.isFinite() || cashUsd <= 0.0) return 0
        val f = fraction(price, winProb, feeRate)
        if (f <= 0.0) return 0
        val c = price + feeRate * price * (1.0 - price)
        val want = floor(cashUsd * f / c + 1e-9).toInt()
        return PaperBuy.capContracts(want, cashUsd, price, feeRate).first
    }
}
