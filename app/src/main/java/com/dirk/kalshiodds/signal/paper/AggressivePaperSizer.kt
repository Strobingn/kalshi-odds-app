package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor

/**
 * Aggressive uncapped paper sizing with per-bet learning.
 *
 * Stake per AI fill = paper equity × [SignalConstants.PAPER_AGGRESSIVE_FRACTION]
 * × learning multiplier. There are **no caps**: the multiplier is unbounded
 * (a hot book presses harder without limit, a cold one shrinks toward zero)
 * and fills are never clipped to cash — the paper book may go negative.
 *
 * Paper only. Never sizes live orders.
 */
object AggressivePaperSizer {

    /**
     * Learning multiplier from settled fills. Empty record = neutral 1.0.
     * Win rate above 50% scales up proportionally (60% → 1.4x, 75% → 2.0x,
     * unbounded above), below 50% scales down toward 0. P&L direction
     * breaks ties.
     */
    fun multiplier(settledWins: Int, settledTotal: Int, pnlUsd: Double): Double {
        if (settledTotal <= 0) return 1.0
        val winRate = settledWins.toDouble() / settledTotal
        val mult = 1.0 + (winRate - 0.5) * 4.0
        val leaned = if (pnlUsd < 0.0) mult * 0.8 else mult
        return leaned.coerceAtLeast(0.0)
    }

    /** All-in paper stake for the next AI fill. Uncapped. */
    fun stakeUsd(equityUsd: Double, multiplier: Double): Double {
        return equityUsd * SignalConstants.PAPER_AGGRESSIVE_FRACTION * multiplier
    }

    /** Contracts for [stakeUsd] at price [px]. */
    fun contracts(stakeUsd: Double, px: Double): Int {
        if (px <= 0.0 || stakeUsd <= 0.0) return 0
        return floor(stakeUsd / px + 1e-9).toInt()
    }
}
