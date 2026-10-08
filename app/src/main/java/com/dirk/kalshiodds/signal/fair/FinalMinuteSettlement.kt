package com.dirk.kalshiodds.signal.fair

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Paper-research estimate for a Kalshi crypto contract's final settlement
 * minute. CF supplies the arithmetic mean and the exact number of one-second
 * observations already locked into the 60-second settlement average.
 *
 * This is deliberately separate from [DigitalOptionFairValue]: it preserves
 * the arithmetic CF mean rather than pretending that the partial average is a
 * point spot or a log mean. It never submits an order.
 */
object FinalMinuteSettlement {
    const val WINDOW_SAMPLES = 60

    data class Estimate(
        val yesProbability: Double,
        val observedSamples: Int,
        val observedAverageUsd: Double,
        val requiredRemainingAverageUsd: Double?,
        val remainingSamples: Int,
        val settled: Boolean
    )

    /**
     * Calculates the arithmetic average required from the remaining CF
     * observations, then approximates its probability under a driftless
     * lognormal index path. The approximation is a research feature and must
     * be validated against the recorded CF rows before it earns model weight.
     */
    fun estimate(
        observedAverageUsd: Double?,
        observedSamples: Int,
        currentIndexUsd: Double?,
        strikeUsd: Double?,
        sigmaAnnual: Double?,
        indexNoiseLog: Double = 1.0e-4
    ): Estimate? {
        val observed = observedAverageUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val spot = currentIndexUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val strike = strikeUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val samples = observedSamples.coerceIn(0, WINDOW_SAMPLES)
        if (samples <= 0) return null
        val remaining = WINDOW_SAMPLES - samples
        if (remaining == 0) {
            return Estimate(
                yesProbability = if (observed >= strike) 1.0 else 0.0,
                observedSamples = samples,
                observedAverageUsd = observed,
                requiredRemainingAverageUsd = null,
                remainingSamples = 0,
                settled = true
            )
        }
        val required = ((WINDOW_SAMPLES * strike) - (samples * observed)) / remaining
        if (required <= 0.0) {
            return Estimate(1.0, samples, observed, required, remaining, settled = false)
        }
        val sigma = sigmaAnnual?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        // Variance of a remaining Brownian average. This is intentionally
        // conservative: it increases uncertainty rather than declaring a late
        // window deterministic from one last-price print.
        val secondsPerYear = DigitalOptionFairValue.SECONDS_PER_YEAR
        val pathVariance = (sigma * sigma / secondsPerYear) * remaining / 3.0
        val noise = indexNoiseLog.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val sd = sqrt(pathVariance + noise * noise)
        val probability = if (sd <= 1e-15) {
            if (spot >= required) 1.0 else 0.0
        } else {
            DigitalOptionFairValue.normCdf((ln(spot) - ln(required)) / sd)
        }
        return Estimate(
            yesProbability = probability.coerceIn(0.0, 1.0),
            observedSamples = samples,
            observedAverageUsd = observed,
            requiredRemainingAverageUsd = required,
            remainingSamples = remaining,
            settled = false
        )
    }
}
