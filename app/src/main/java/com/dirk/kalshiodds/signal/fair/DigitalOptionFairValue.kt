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

    /** Kalshi 15m crypto settles on the average of the last 60 s of the index. */
    const val SETTLE_WINDOW_SECONDS = 60.0

    fun indexNoiseLog(asset: String?): Double = when (asset?.uppercase()) {
        "BTC" -> 0.5e-4
        "ETH" -> 0.9e-4
        "SOL" -> 1.1e-4
        else -> 1.0e-4
    }

    /**
     * P(YES) under Kalshi's settlement rule: the simple average of the index
     * over the last 60 seconds is at least the strike (ties settle YES).
     * This is the contract the app is scored on. A point-spot digital is not.
     */
    fun pSettleAtLeast(
        spot: Double,
        strike: Double,
        tteSeconds: Double,
        sigmaAnnual: Double,
        observedMeanLog: Double? = null,
        indexNoise: Double = 1.0e-4,
        windowSeconds: Double = SETTLE_WINDOW_SECONDS
    ): Double? {
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!tteSeconds.isFinite() || !sigmaAnnual.isFinite() || sigmaAnnual <= 0.0) return null
        if (!windowSeconds.isFinite() || windowSeconds <= 0.0) return null
        val lnS = ln(spot)
        val lnK = ln(strike)
        val obs = observedMeanLog?.takeIf { it.isFinite() } ?: lnS
        val w = windowSeconds
        val t = tteSeconds.coerceAtLeast(0.0)
        val s2 = sigmaAnnual * sigmaAnnual / SECONDS_PER_YEAR
        val (mean, pathVar) = if (t >= w) {
            lnS to s2 * ((t - w) + w / 3.0)
        } else {
            val f = t / w
            ((w - t) * obs + t * lnS) / w to f * f * s2 * t / 3.0
        }
        val noise = if (indexNoise.isFinite() && indexNoise > 0.0) indexNoise else 0.0
        val sd = sqrt(pathVar + noise * noise)
        if (!sd.isFinite() || sd <= 1e-15) return if (mean >= lnK) 1.0 else 0.0
        return normCdf((mean - lnK) / sd).coerceIn(0.0, 1.0)
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
     * Drift-aware digital: adds a **shrunk** drift estimate to d2.
     *
     * Plain [pFinishAbove] assumes zero drift, which systematically
     * underprices the side a fast 15-minute move is running toward. With
     * per-bar log returns over [driftDtSeconds]-second bars:
     *
     *     μ̂_raw  = mean(returns) / dt        (annualized)
     *     shrink = n / (n + κ)               (κ ≈ 20 bars — small samples
     *     μ̂      = shrink × μ̂_raw             collapse toward 0 drift)
     *     d2     = [ln(S/K) + (μ̂ − ½σ²)T] / (σ√T)
     *
     * μ̂ is capped at ±2× the vol scale so a short burst cannot dominate.
     * Falls back to [pFinishAbove] when fewer than 4 returns are usable.
     */
    fun pFinishAboveWithDrift(
        spot: Double,
        strike: Double,
        tteSeconds: Double,
        sigmaAnnual: Double,
        logReturns: List<Double>,
        driftDtSeconds: Double = 60.0,
        shrinkKappa: Double = 20.0
    ): Double? {
        if (logReturns.filter { it.isFinite() }.size < 4) {
            return pFinishAbove(spot, strike, tteSeconds, sigmaAnnual)
        }
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!tteSeconds.isFinite() || tteSeconds <= 0.0) {
            return if (spot > strike) 1.0 else 0.0
        }
        if (!sigmaAnnual.isFinite() || sigmaAnnual <= 1e-8) return null
        val xs = logReturns.filter { it.isFinite() }
        val barsPerYear = SECONDS_PER_YEAR / driftDtSeconds.coerceAtLeast(1.0)
        val muRaw = xs.average() * barsPerYear
        val shrink = xs.size / (xs.size + shrinkKappa.coerceAtLeast(1.0))
        val muCap = 2.0 * sigmaAnnual
        val mu = (shrink * muRaw).coerceIn(-muCap, muCap)
        val tYears = (tteSeconds / SECONDS_PER_YEAR).coerceIn(1e-10, 1.0)
        val volSqrtT = sigmaAnnual * sqrt(tYears)
        if (volSqrtT <= 1e-12) return if (spot > strike) 1.0 else 0.0
        val d2 = (ln(spot / strike) + (mu - 0.5 * sigmaAnnual * sigmaAnnual) * tYears) / volSqrtT
        return normCdf(d2).coerceIn(0.0, 1.0)
    }

    /**
     * EWMA (RiskMetrics) annualized vol — weights recent bars by λ^age so a
     * fresh vol spike moves the estimate immediately, unlike the flat
     * sample window in [realizedVolAnnual]. λ = 0.94 (standard daily
     * RiskMetrics); for 1-minute crypto bars a faster λ ≈ 0.85 is sane.
     * Needs ≥ 4 finite returns.
     */
    fun ewmaVolAnnual(
        logReturns: List<Double>,
        dtSeconds: Double = 60.0,
        lambda: Double = 0.85
    ): Double? {
        val xs = logReturns.filter { it.isFinite() }
        if (xs.size < 4) return null
        val lam = lambda.coerceIn(0.5, 0.999)
        var v: Double? = null
        for (r in xs) {
            v = if (v == null) r * r else lam * v + (1.0 - lam) * r * r
        }
        val std = sqrt(v ?: return null)
        if (!std.isFinite() || std <= 0.0) return null
        val barsPerYear = SECONDS_PER_YEAR / dtSeconds.coerceAtLeast(1.0)
        return (std * sqrt(barsPerYear)).coerceIn(0.01, 5.0)
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
