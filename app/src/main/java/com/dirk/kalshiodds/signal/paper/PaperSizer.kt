package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee

/**
 * Paper-only position sizing: the AI stakes what the **Kelly criterion** says
 * its edge is worth — no $5 / $10 clip. Never used for live orders.
 *
 * Buying C contracts at all-in cost q per contract (ask + Kalshi taker fee)
 * with win chance p returns (1 − q)/q per dollar staked on a win and −1 on a
 * loss, so the growth-optimal fraction of equity is
 *
 *     f* = (p − q) / (1 − q)
 *
 * The book stakes [KELLY_MULTIPLIER] × f* × equity, limited only by paper
 * cash. Half-Kelly keeps ~75% of full Kelly's growth with far lower swings,
 * and model win chances are estimates — full Kelly on an overstated p
 * over-bets and grows *slower*. No positive edge at this ask → no bet.
 */
object PaperSizer {
    const val KELLY_MULTIPLIER = 0.5

    data class Size(
        val contracts: Int,
        val costUsd: Double,
        val kellyFraction: Double,
        val targetUsd: Double
    ) {
        val note: String
            get() = String.format(
                java.util.Locale.US,
                "Kelly %.1f%% × %.1f → $%.2f",
                kellyFraction * 100.0,
                KELLY_MULTIPLIER,
                costUsd
            )
    }

    /** Full-Kelly fraction of equity for win chance [p] at all-in cost [q] per contract. */
    fun kellyFraction(p: Double, q: Double): Double {
        if (!p.isFinite() || !q.isFinite() || q <= 0.0 || q >= 1.0) return 0.0
        return ((p - q) / (1.0 - q)).coerceAtLeast(0.0)
    }

    /**
     * Contracts to paper-buy at [price] given [winProb], or null when there
     * is no positive edge after fees or the stake rounds to 0 contracts.
     */
    fun size(
        winProb: Double,
        price: Double,
        equityUsd: Double,
        cashUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Size? {
        val px = KalshiPrice.usable(price) ?: return null
        if (!winProb.isFinite() || equityUsd <= 0.0 || cashUsd <= 0.0) return null
        // Fee per contract depends on size a little; estimate it at a mid-size clip first.
        var q = px + KalshiFee.perContract(px, feeRate, stakeUsd = equityUsd.coerceAtLeast(1.0) * 0.05)
        var f = kellyFraction(winProb, q)
        if (f <= 0.0) return null
        val target = (KELLY_MULTIPLIER * f * equityUsd).coerceAtMost(cashUsd)
        var c = kotlin.math.floor(target / q).toInt()
        while (c > 0 && KalshiFee.totalCost(c, px, feeRate) > target + 1e-9) c--
        if (c < 1) return null
        val cost = KalshiFee.totalCost(c, px, feeRate)
        // Re-check the edge at the actual per-contract cost of this size.
        q = cost / c
        f = kellyFraction(winProb, q)
        if (f <= 0.0) return null
        return Size(contracts = c, costUsd = cost, kellyFraction = f, targetUsd = target)
    }
}
