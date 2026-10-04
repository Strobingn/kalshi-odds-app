package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * Rolling probability calibration.
 *
 * When enough settled samples exist:
 *   1. Fit a temperature scalar T so p_cal = sigmoid(logit(p) / T)
 *   2. Keep reliability bins (observed frequency per predicted bucket)
 *
 * Displayed P(YES) and edge use [apply] when [State.ready]; otherwise raw
 * model / blend values (cold start).
 */
object Calibrator {
    @Serializable
    data class ReliabilityBin(
        val lo: Double,
        val hi: Double,
        val meanPredicted: Double = 0.0,
        val meanOutcome: Double = 0.0,
        val n: Int = 0
    )

    @Serializable
    data class State(
        val temperature: Double = 1.0,
        val bins: List<ReliabilityBin> = emptyList(),
        val sampleCount: Int = 0,
        val ready: Boolean = false,
        val fittedAtMs: Long = 0L
    )

    data class Sample(val pYes: Double, val outcomeYes: Boolean)

    fun fit(
        samples: List<Sample>,
        nowMs: Long = System.currentTimeMillis(),
        minSamples: Int = SignalConstants.MIN_CALIBRATION_SAMPLES
    ): State {
        val usable = samples.mapNotNull { s ->
            val p = s.pYes.coerceIn(0.02, 0.98)
            if (p.isFinite()) Sample(p, s.outcomeYes) else null
        }
        if (usable.size < minSamples) {
            return State(sampleCount = usable.size, ready = false, fittedAtMs = nowMs)
        }
        var bestT = 1.0
        var bestNll = Double.POSITIVE_INFINITY
        var t = 0.50
        while (t <= 2.50 + 1e-9) {
            var nll = 0.0
            for (s in usable) {
                val p = applyTemperature(s.pYes, t).coerceIn(1e-4, 1.0 - 1e-4)
                nll += if (s.outcomeYes) -ln(p) else -ln(1.0 - p)
            }
            if (nll < bestNll) {
                bestNll = nll
                bestT = t
            }
            t += 0.05
        }
        val bins = reliabilityBins(usable)
        return State(
            temperature = bestT,
            bins = bins,
            sampleCount = usable.size,
            ready = true,
            fittedAtMs = nowMs
        )
    }

    fun fitEntries(entries: List<PredictionLogEntry>, nowMs: Long = System.currentTimeMillis()): State {
        val samples = entries.filter { com.dirk.kalshiodds.domain.CryptoMarkets.isBtc15m(it.series, it.ticker) }.mapNotNull { e ->
            val y = when (e.outcome?.lowercase()) {
                "yes" -> true
                "no" -> false
                else -> return@mapNotNull null
            }
            Sample(e.predictedYes, y)
        }
        return fit(samples, nowMs)
    }

    /**
     * Calibrate a YES probability in 0–1. Identity when [state] is cold.
     * Mixes temperature scaling with the matching reliability bin when that
     * bin has at least 5 observations.
     */
    fun apply(pYes: Double, state: State): Double {
        val p = pYes.coerceIn(0.02, 0.98)
        if (!state.ready) return p
        val warmed = applyTemperature(p, state.temperature)
        val bin = state.bins.firstOrNull { p >= it.lo && p < it.hi || (it.hi >= 1.0 && p >= it.lo) }
        return if (bin != null && bin.n >= 5) {
            (0.70 * warmed + 0.30 * bin.meanOutcome).coerceIn(0.02, 0.98)
        } else {
            warmed.coerceIn(0.02, 0.98)
        }
    }

    fun applyPp(pYesPp: Double, state: State): Double = apply(pYesPp / 100.0, state) * 100.0

    fun applyTemperature(p: Double, temperature: Double): Double {
        val t = temperature.coerceIn(0.35, 3.0)
        if (abs(t - 1.0) < 1e-6) return p.coerceIn(0.02, 0.98)
        return sigmoid(logit(p) / t)
    }

    fun logit(p: Double): Double {
        val x = p.coerceIn(1e-4, 1.0 - 1e-4)
        return ln(x / (1.0 - x))
    }

    fun sigmoid(z: Double): Double {
        val e = exp(-z.coerceIn(-30.0, 30.0))
        return 1.0 / (1.0 + e)
    }

    private fun reliabilityBins(samples: List<Sample>): List<ReliabilityBin> {
        val nBins = SignalConstants.RELIABILITY_BINS
        return (0 until nBins).map { i ->
            val lo = i / nBins.toDouble()
            val hi = (i + 1) / nBins.toDouble()
            val inBin = samples.filter { s ->
                if (i == nBins - 1) s.pYes >= lo else s.pYes >= lo && s.pYes < hi
            }
            ReliabilityBin(
                lo = lo,
                hi = hi,
                meanPredicted = if (inBin.isEmpty()) (lo + hi) / 2.0 else inBin.map { it.pYes }.average(),
                meanOutcome = if (inBin.isEmpty()) 0.0 else inBin.count { it.outcomeYes }.toDouble() / inBin.size,
                n = inBin.size
            )
        }
    }
}
