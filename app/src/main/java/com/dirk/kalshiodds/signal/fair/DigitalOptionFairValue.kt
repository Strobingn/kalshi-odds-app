package com.dirk.kalshiodds.signal.fair

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Volatility-based cash-or-nothing digital: P(spot finishes above strike).
 *
 * For a 15-minute Kalshi crypto "price up?" contract, YES pays if
 * S_T > K. With r ≈ 0 over 15 minutes:
 *
 *     d2 = [ln(S/K) − ½ σ² T] / (σ √T)
 *     P  = Φ(d2)
 *
 * [sigmaAnnual] is annualized log-return vol. [tteSeconds] is time to
 * expiry. Missing / degenerate inputs return null so the feature drops out.
 */
object DigitalOptionFairValue {
    const val SECONDS_PER_YEAR = 365.25 * 24.0 * 3600.0
    const val WINDOW_SECONDS = 900.0

    /** Typical annualized log-vol used when the live 1m window is missing. */
    const val TYPICAL_BTC_SIGMA = 0.40
    const val TYPICAL_ETH_SIGMA = 0.55
    const val TYPICAL_SOL_SIGMA = 0.74

    fun typicalSigma(seriesOrAsset: String?): Double {
        val u = seriesOrAsset.orEmpty().uppercase()
        return when {
            u.contains("SOL") -> TYPICAL_SOL_SIGMA
            u.contains("ETH") && !u.contains("BTC") -> TYPICAL_ETH_SIGMA
            else -> TYPICAL_BTC_SIGMA
        }
    }

    fun pFinishAbove(
        spot: Double,
        strike: Double,
        tteSeconds: Double,
        sigmaAnnual: Double
    ): Double? {
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!tteSeconds.isFinite() || tteSeconds <= 0.0) {
            return if (spot > strike) 1.0 else 0.0
        }
        if (!sigmaAnnual.isFinite() || sigmaAnnual <= 1e-8) return null
        val tYears = (tteSeconds / SECONDS_PER_YEAR).coerceIn(1e-10, 1.0)
        val volSqrtT = sigmaAnnual * sqrt(tYears)
        if (volSqrtT <= 1e-12) return if (spot > strike) 1.0 else 0.0
        val d2 = (ln(spot / strike) - 0.5 * sigmaAnnual * sigmaAnnual * tYears) / volSqrtT
        return normCdf(d2).coerceIn(0.0, 1.0)
    }

    /** Distance to strike in remaining-vol units: ln(S/K) / (σ √T). */
    fun distanceVolUnits(
        spot: Double,
        strike: Double,
        tteSeconds: Double,
        sigmaAnnual: Double
    ): Double? {
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!sigmaAnnual.isFinite() || sigmaAnnual <= 1e-8) return null
        val tYears = (tteSeconds.coerceAtLeast(1.0) / SECONDS_PER_YEAR).coerceIn(1e-10, 1.0)
        val volSqrtT = sigmaAnnual * sqrt(tYears)
        if (volSqrtT <= 1e-12) return null
        return ln(spot / strike) / volSqrtT
    }

    /**
     * Annualize a sample of log returns. [dtSeconds] is the bar length
     * (60 for 1-minute candles). Needs ≥ 4 finite returns.
     */
    fun realizedVolAnnual(logReturns: List<Double>, dtSeconds: Double = 60.0): Double? {
        val xs = logReturns.filter { it.isFinite() }
        if (xs.size < 4) return null
        val mean = xs.average()
        val varSum = xs.sumOf { val d = it - mean; d * d }
        val std = sqrt(varSum / (xs.size - 1).coerceAtLeast(1))
        if (!std.isFinite() || std <= 0.0) return null
        val barsPerYear = SECONDS_PER_YEAR / dtSeconds.coerceAtLeast(1.0)
        return (std * sqrt(barsPerYear)).coerceIn(0.01, 5.0)
    }

    fun logReturns(closes: List<Double>): List<Double> {
        if (closes.size < 2) return emptyList()
        val out = ArrayList<Double>(closes.size - 1)
        var prev = closes.first()
        for (i in 1 until closes.size) {
            val c = closes[i]
            if (prev > 0.0 && c > 0.0 && prev.isFinite() && c.isFinite()) {
                out.add(ln(c / prev))
            }
            prev = c
        }
        return out
    }

    /** Φ(x) via erf. */
    fun normCdf(x: Double): Double {
        if (!x.isFinite()) return if (x > 0) 1.0 else 0.0
        return 0.5 * (1.0 + erf(x / SQRT2))
    }

    /**
     * Abramowitz & Stegun 7.1.26 erf approximation. Max error ~1.5e-7.
     */
    fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    private const val SQRT2 = 1.4142135623730951
}
