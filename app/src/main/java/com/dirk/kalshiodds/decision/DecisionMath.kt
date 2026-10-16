package com.dirk.kalshiodds.decision

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Probability math for the decision path. Numerical clipping at 1e-15
 * keeps logit defined. It is not a 2% display floor — a 0.1% probability
 * stays a 0.1% probability.
 */
object DecisionMath {
    const val LOGIT_EPS = 1e-15
    const val SECONDS_PER_YEAR = 365.25 * 24.0 * 3600.0

    fun logit(p: Double): Double {
        val x = p.coerceIn(LOGIT_EPS, 1.0 - LOGIT_EPS)
        return ln(x / (1.0 - x))
    }

    fun sigmoid(z: Double): Double {
        val e = exp(-z.coerceIn(-40.0, 40.0))
        return 1.0 / (1.0 + e)
    }

    /** z = ln(spot/target) / (realizedVol * sqrt(timeRemaining)). Units must match. */
    fun zDistance(spot: Double, target: Double, realizedVol: Double, timeRemaining: Double): Double? {
        if (!spot.isFinite() || !target.isFinite() || !realizedVol.isFinite() || !timeRemaining.isFinite()) return null
        if (spot <= 0.0 || target <= 0.0 || realizedVol <= 0.0 || timeRemaining <= 0.0) return null
        val denom = realizedVol * sqrt(timeRemaining)
        if (!denom.isFinite() || denom <= 0.0) return null
        val z = ln(spot / target) / denom
        return z.takeIf { it.isFinite() }
    }

    /**
     * Same z as ml/train_edge.py dist_vol: annualized sigma and seconds remaining.
     */
    fun zAnnual(spot: Double, target: Double, sigmaAnnual: Double, secondsRemaining: Double): Double? {
        if (secondsRemaining <= 0.0) return null
        val years = secondsRemaining / SECONDS_PER_YEAR
        return zDistance(spot, target, sigmaAnnual, years)
    }

    fun brier(pairs: List<Pair<Double, Boolean>>): Double? {
        if (pairs.isEmpty()) return null
        return pairs.sumOf { (p, y) ->
            val t = if (y) 1.0 else 0.0
            val d = p - t
            d * d
        } / pairs.size
    }

    fun logLoss(pairs: List<Pair<Double, Boolean>>): Double? {
        if (pairs.isEmpty()) return null
        return pairs.sumOf { (p, y) ->
            val q = p.coerceIn(LOGIT_EPS, 1.0 - LOGIT_EPS)
            if (y) -ln(q) else -ln(1.0 - q)
        } / pairs.size
    }

    /** Normal approximation 95% interval. */
    fun meanCi95(values: List<Double>): Pair<Double, Double>? {
        if (values.isEmpty()) return null
        val mean = values.average()
        if (values.size == 1) return mean to mean
        val v = values.sumOf { val d = it - mean; d * d } / (values.size - 1)
        val se = sqrt(v.coerceAtLeast(0.0) / values.size)
        return (mean - 1.96 * se) to (mean + 1.96 * se)
    }
}
