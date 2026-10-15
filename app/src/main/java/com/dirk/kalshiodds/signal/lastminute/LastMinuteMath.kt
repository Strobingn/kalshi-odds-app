package com.dirk.kalshiodds.signal.lastminute

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Pure port of `research/last_minute/fine_trades.py:fair_p` and
 * `research/last_minute/common.py` (`ceil_to`, `all_in_cost`, `size_bet`).
 * Golden vectors in [LastMinuteMathTest] must match Python to 1e-4.
 */
object LastMinuteMath {

    private val SQRT2 = sqrt(2.0)

    /**
     * P(final 60s average ≥ open 60s average).
     * [tau] is seconds to close. [obsMean] is 0 when tau ≥ 60.
     */
    fun fairP(
        x: Double,
        tau: Double,
        obsMean: Double,
        sigS: Double,
        k: Double = LastMinuteConstants.K,
        eta: Double = LastMinuteConstants.ETA
    ): Double {
        if (!x.isFinite() || !tau.isFinite() || !sigS.isFinite()) return Double.NaN
        val sig = k * sigS
        val late = tau < LastMinuteConstants.FINAL_MINUTE_SEC
        val nobs = if (late) LastMinuteConstants.FINAL_MINUTE_SEC - tau else 0.0
        val mean = if (late) {
            (nobs * obsMean + tau * x) / LastMinuteConstants.FINAL_MINUTE_SEC.toDouble()
        } else {
            x
        }
        val variance = if (late) {
            val t = maxOf(tau, 1.0)
            val frac = tau / LastMinuteConstants.FINAL_MINUTE_SEC.toDouble()
            frac * frac * sig * sig * t / 3.0
        } else {
            sig * sig * (tau - 40.0)
        }
        val denom = sqrt(variance + eta * eta)
        if (!denom.isFinite() || denom <= 0.0) return if (mean >= 0.0) 1.0 else 0.0
        return phi(mean / denom)
    }

    /** Standard normal CDF. Abramowitz & Stegun 7.1.26 via erf. */
    fun phi(z: Double): Double {
        if (!z.isFinite()) return if (z > 0.0) 1.0 else 0.0
        return 0.5 * (1.0 + erf(z / SQRT2))
    }

    fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) *
            t * exp(-ax * ax)
        return sign * y
    }

    /**
     * Python `common.ceil_to`: `math.ceil(round(x / q, 9)) * q`.
     * Python 3 `round` is bankers; we match the 9-decimal pre-round then ceil.
     */
    fun ceilTo(x: Double, q: Double): Double {
        if (!x.isFinite() || x <= 0.0) return 0.0
        if (!q.isFinite() || q <= 0.0) return 0.0
        val n = roundToDecimals(x / q, 9)
        return ceil(n) * q
    }

    fun allInCost(
        contracts: Int,
        price: Double,
        rate: Double = LastMinuteConstants.TAKER_RATE
    ): Double {
        val c = contracts
        if (c <= 0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        val fee = ceilTo(rate * c * price * (1.0 - price), 1e-6)
        return ceilTo(c * price + fee, 0.01)
    }

    /** Largest whole C with [allInCost] ≤ [stakeUsd]. Returns (C, cost). */
    fun sizeBet(
        price: Double,
        stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD,
        rate: Double = LastMinuteConstants.TAKER_RATE
    ): Pair<Int, Double> {
        if (!price.isFinite() || price <= 0.0 || price >= 1.0) return 0 to 0.0
        if (!stakeUsd.isFinite() || stakeUsd <= 0.0) return 0 to 0.0
        var c = (stakeUsd / price).toInt()
        while (c > 0 && allInCost(c, price, rate) > stakeUsd + 1e-9) c -= 1
        val cost = if (c > 0) allInCost(c, price, rate) else 0.0
        return c to cost
    }

    fun evPerDollar(contracts: Int, winProb: Double, cost: Double): Double? {
        if (contracts <= 0 || !cost.isFinite() || cost <= 0.0) return null
        if (!winProb.isFinite()) return null
        return (contracts * winProb - cost) / cost
    }

    /**
     * Per-second vol from completed-minute log closes (oldest first).
     * `sig_s = sqrt(mean r_1m^2 over prior 60 minutes, min 30) / sqrt(60)`.
     */
    fun perSecondVol(minuteLogCloses: List<Double>): Double? {
        if (minuteLogCloses.size < LastMinuteConstants.VOL_MIN_MINUTES + 1) return null
        val rets = minuteLogCloses.zipWithNext { a, b ->
            if (!a.isFinite() || !b.isFinite()) return@zipWithNext Double.NaN
            b - a
        }.filter { it.isFinite() }
        val used = rets.takeLast(LastMinuteConstants.VOL_MINUTES)
        if (used.size < LastMinuteConstants.VOL_MIN_MINUTES) return null
        val meanSq = used.sumOf { it * it } / used.size
        if (!meanSq.isFinite() || meanSq < 0.0) return null
        return sqrt(meanSq) / sqrt(LastMinuteConstants.FINAL_MINUTE_SEC.toDouble())
    }

    fun logSpotOverStrike(spot: Double, strike: Double): Double? {
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        return kotlin.math.ln(spot / strike)
    }

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return round(value * scale) / scale
    }
}
