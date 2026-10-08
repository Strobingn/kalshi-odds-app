package com.dirk.kalshiodds.signal.fair

import kotlin.math.sqrt

/**
 * Last-minute settlement math for Kalshi 15-minute crypto binaries.
 *
 * ## Why this exists
 *
 * KXBTC15M does **not** settle on the last trade. The expiration value is
 * the **average of the 60 one-second CF Benchmarks RTI prints** in the
 * final minute before expiry (and the opening reference is the 60-second
 * average before the window opens). A tie resolves **Yes**.
 *
 * That makes the final minute a partially observable countdown: after n
 * seconds, n/60 of the settlement is already written. Retail flow anchors
 * on "spot vs strike" and chases last-second spikes; the running average
 * frequently says the outcome is already decided. This object computes
 * the truth of the countdown so the app can flag the dislocation
 * (analysis only — never places orders).
 *
 * ## Math
 *
 *     settlement S = (Σ locked + Σ remaining) / 60
 *     YES wins iff  S ≥ strike          (tie pays Yes)
 *
 * With n locked samples summing to L and r = 60 − n remaining, the
 * remaining samples must average
 *
 *     m* = (60·K − L) / r
 *
 * Modelling the remaining prints as iid around the current index level s
 * with per-second vol σ_sec:
 *
 *     Σ remaining ~ N(r·s, r·σ²)
 *     P(YES) = Φ( (s − m*)·√r / σ_sec )
 *
 * When r = 0 the outcome is fully determined. Where CF Benchmarks applies
 * trimmed averaging (top/bottom 20% excluded) a single extreme second is
 * worth even less, so this estimate is conservative for fade purposes.
 */
object SettlementAverage {

    const val SAMPLES_PER_MINUTE = 60

    data class State(
        /** Running sum of the final-minute index prints observed so far. */
        val lockedSum: Double,
        /** Number of final-minute prints observed (0..60). */
        val lockedCount: Int,
        /** Opening reference price (the strike the window must beat). */
        val strike: Double,
        val totalSamples: Int = SAMPLES_PER_MINUTE
    ) {
        val remainingCount: Int get() = (totalSamples - lockedCount).coerceAtLeast(0)
        val lockedFraction: Double get() =
            if (totalSamples <= 0) 1.0 else lockedCount.toDouble() / totalSamples
        val lockedMean: Double? get() =
            if (lockedCount <= 0) null else lockedSum / lockedCount
    }

    fun state(samples: List<Double>, strike: Double): State? {
        if (!strike.isFinite() || strike <= 0.0) return null
        val xs = samples.filter { it.isFinite() && it > 0.0 }.take(SAMPLES_PER_MINUTE)
        return State(lockedSum = xs.sum(), lockedCount = xs.size, strike = strike)
    }

    /**
     * Average the remaining prints must hold for Yes to win. Positive
     * infinity when nothing remains (outcome already decided).
     */
    fun requiredRemainingMean(state: State): Double {
        val r = state.remainingCount
        if (r <= 0) return Double.POSITIVE_INFINITY
        return (state.totalSamples * state.strike - state.lockedSum) / r
    }

    /**
     * How much harder the flip is than the naive "spot vs strike" read:
     * `(m* − s) / (K − s)`. Research reconstructions show ~4× once
     * three-quarters of the minute is locked. Null when spot sits exactly
     * on the strike or the outcome is decided.
     */
    fun flipDifficultyMultiple(state: State, currentIndex: Double): Double? {
        if (state.remainingCount <= 0) return null
        val gap = state.strike - currentIndex
        if (gap == 0.0 || !gap.isFinite()) return null
        return (requiredRemainingMean(state) - currentIndex) / gap
    }

    /**
     * Probability the window settles Yes given [currentIndex] and
     * [sigmaAnnual] (same annualized vol convention as
     * [DigitalOptionFairValue]). Deterministic 1.0 / 0.0 once all 60
     * prints are in; ties resolve Yes, so settlement exactly on the
     * strike counts as a win.
     */
    fun pFinishYes(state: State, currentIndex: Double, sigmaAnnual: Double): Double? {
        if (!currentIndex.isFinite() || currentIndex <= 0.0) return null
        val r = state.remainingCount
        if (r <= 0) {
            val settled = state.lockedSum / state.totalSamples
            return if (settled >= state.strike) 1.0 else 0.0
        }
        if (!sigmaAnnual.isFinite() || sigmaAnnual <= 1e-8) return null
        val sigmaSec = sigmaAnnual / sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR)
        if (sigmaSec <= 0.0) return null
        val mStar = requiredRemainingMean(state)
        val z = (currentIndex - mStar) / currentIndex * sqrt(r.toDouble()) / sigmaSec
        return DigitalOptionFairValue.normCdf(z).coerceIn(0.0, 1.0)
    }

    /**
     * Fade dislocation in percentage points: model P(Yes)×100 minus the
     * market's Yes price in cents. Strongly negative → the crowd is
     * overpaying for a spike the average says cannot matter (fade Yes /
 * buy No); strongly positive → the mirror image. Null when the
     * probability cannot be computed.
     */
    fun dislocationPp(state: State, currentIndex: Double, sigmaAnnual: Double, marketYesCents: Double): Double? {
        val p = pFinishYes(state, currentIndex, sigmaAnnual) ?: return null
        if (!marketYesCents.isFinite() || marketYesCents !in 0.0..100.0) return null
        return p * 100.0 - marketYesCents
    }
}
