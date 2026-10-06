package com.dirk.kalshiodds.decision

/**
 * Market-as-prior residual model:
 *
 *   final p = market p + clamp(calibrated model p − market p, −cap, +cap)
 *
 * The market is the prior; the model only earns a bounded correction. The
 * cap is [CAP] when the regime is approved (calibrated and not worse than
 * the market out of fold) and [CAP_UNAPPROVED] otherwise. No display floor.
 */
object MarketPrior {
    const val VERSION = "market-residual-v1"
    const val CAP = 0.08
    const val CAP_UNAPPROVED = 0.02

    fun capFor(approved: Boolean): Double = if (approved) CAP else CAP_UNAPPROVED

    fun correction(marketP: Double, modelP: Double, cap: Double): Double {
        if (!marketP.isFinite() || !modelP.isFinite()) return 0.0
        val c = cap.coerceAtLeast(0.0)
        return (modelP - marketP).coerceIn(-c, c)
    }

    fun finalProbability(marketP: Double, modelP: Double?, cap: Double): Double {
        if (!marketP.isFinite()) return marketP
        val m = modelP ?: return marketP.coerceIn(0.0, 1.0)
        return (marketP + correction(marketP, m, cap)).coerceIn(0.0, 1.0)
    }
}
