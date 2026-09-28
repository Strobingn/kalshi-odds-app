package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.floor
import kotlin.math.min

/**
 * Fractional Kelly size for **paper** fills only. Never used on a live
 * order. There is no dollar max — contracts are capped only by the
 * paper bankroll and (when known) ask-book depth.
 *
 * Per contract: payout = $1, cost = live ask + official taker fee
 * `ceil_cent(0.07 × C × P × (1 − P))` on a one-contract equivalent fill.
 *
 *     f = (p × payout − cost) / (payout − cost)
 *     stake = bankroll × f × kellyFraction
 *
 * Skip when f ≤ 0. Pure math — never places an order.
 */
object PaperKellySizer {

    const val PAYOUT_USD = SignalConstants.CONTRACT_SETTLEMENT_USD
    const val DEFAULT_KELLY_FRACTION = SignalConstants.DEFAULT_PAPER_KELLY_FRACTION
    const val MIN_KELLY_FRACTION = SignalConstants.PAPER_KELLY_FRACTION_MIN
    const val MAX_KELLY_FRACTION = SignalConstants.PAPER_KELLY_FRACTION_MAX

    data class Result(
        val skip: Boolean,
        val reason: String? = null,
        val kellyF: Double = 0.0,
        val kellyFraction: Double = DEFAULT_KELLY_FRACTION,
        val contracts: Int = 0,
        val stakeUsd: Double = 0.0,
        val feeUsd: Double = 0.0,
        val allInUsd: Double = 0.0,
        val ask: Double = 0.0,
        val costPerContract: Double = 0.0
    ) {
        val ok: Boolean get() = !skip && contracts > 0
    }

    /**
     * Full Kelly fraction of bankroll for one contract at [ask] with
     * model win probability [winProb]. Negative / zero means no edge
     * after fees.
     */
    fun fullKelly(
        winProb: Double?,
        ask: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double {
        val p = winProb?.takeIf { it.isFinite() } ?: return Double.NaN
        val px = KalshiPrice.usable(ask) ?: return Double.NaN
        val cost = KalshiFee.totalCost(1, px, feeRate)
        return fullKellyFromCost(p, cost)
    }

    fun fullKellyFromCost(winProb: Double, costUsd: Double, payoutUsd: Double = PAYOUT_USD): Double {
        if (!winProb.isFinite() || !costUsd.isFinite() || costUsd <= 0.0) return Double.NaN
        val denom = payoutUsd - costUsd
        if (!denom.isFinite() || denom <= 1e-12) return 0.0
        return (winProb * payoutUsd - costUsd) / denom
    }

    fun size(
        winProb: Double?,
        ask: Double,
        bankrollUsd: Double,
        kellyFraction: Double = DEFAULT_KELLY_FRACTION,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        depthContracts: Int? = null
    ): Result {
        val px = KalshiPrice.usable(ask)
            ?: return Result(skip = true, reason = "Paper skip — unusable ask")
        val p = winProb?.takeIf { it.isFinite() }
            ?: return Result(skip = true, reason = "Paper skip — no model win probability")
        val frac = kellyFraction.coerceIn(MIN_KELLY_FRACTION, MAX_KELLY_FRACTION)
        val bankroll = bankrollUsd.takeIf { it.isFinite() && it > 0.0 }
            ?: return Result(skip = true, reason = "Paper skip — empty bankroll")
        val cost1 = KalshiFee.totalCost(1, px, feeRate)
        val f = fullKellyFromCost(p, cost1)
        if (!f.isFinite() || f <= 0.0) {
            return Result(
                skip = true,
                reason = "Paper skip — Kelly ≤ 0 after fees",
                kellyF = if (f.isFinite()) f else 0.0,
                kellyFraction = frac,
                ask = px,
                costPerContract = cost1
            )
        }
        val target = bankroll * f * frac
        val cap = min(target, bankroll)
        if (cap + 1e-9 < cost1) {
            return Result(
                skip = true,
                reason = "Paper skip — bankroll cannot cover 1 ct after fees",
                kellyF = f,
                kellyFraction = frac,
                ask = px,
                costPerContract = cost1
            )
        }
        if (depthContracts == null) {
            return Result(
                skip = true,
                reason = "Paper skip — unknown ask depth",
                kellyF = f,
                kellyFraction = frac,
                ask = px,
                costPerContract = cost1
            )
        }
        val n = maxContracts(px, cap, feeRate, depthContracts)
        if (n < 1) {
            return Result(
                skip = true,
                reason = if (depthContracts <= 0) {
                    "Paper skip — no size at the ask"
                } else {
                    "Paper skip — cannot fit 1 contract under bankroll"
                },
                kellyF = f,
                kellyFraction = frac,
                ask = px,
                costPerContract = cost1
            )
        }
        val allIn = KalshiFee.totalCost(n, px, feeRate)
        val fee = KalshiFee.total(n, px, feeRate)
        return Result(
            skip = false,
            kellyF = f,
            kellyFraction = frac,
            contracts = n,
            stakeUsd = allIn,
            feeUsd = fee,
            allInUsd = allIn,
            ask = px,
            costPerContract = cost1
        )
    }

    /**
     * Largest whole [n] with all-in ≤ [capUsd] and n ≤ [depthContracts].
     * Unknown / missing depth is 0 — never unlimited.
     */
    fun maxContracts(
        ask: Double,
        capUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        depthContracts: Int? = null
    ): Int {
        val px = KalshiPrice.usable(ask) ?: return 0
        if (!capUsd.isFinite() || capUsd <= 0.0) return 0
        val depthCap = depthContracts?.takeIf { it >= 0 } ?: return 0
        if (depthCap <= 0) return 0
        val hiGuess = (floor(capUsd / px) + 2.0).toInt().coerceAtLeast(0)
        val hi = min(hiGuess, depthCap)
        if (hi < 1) return 0
        if (KalshiFee.totalCost(1, px, feeRate) > capUsd + 1e-9) return 0
        var lo = 1
        var best = 0
        var h = hi
        while (lo <= h) {
            val mid = (lo + h) ushr 1
            if (mid < 1) {
                lo = 1
                continue
            }
            val allIn = KalshiFee.totalCost(mid, px, feeRate)
            if (allIn <= capUsd + 1e-9) {
                best = mid
                lo = mid + 1
            } else {
                h = mid - 1
            }
        }
        return best
    }
}
