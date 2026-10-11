package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.model.SignalStance
import kotlin.math.abs

/**
 * Prediction-log probabilities must be in **0–1** for Brier and hit-rate.
 *
 * Hit rate scores the **picked side** ([PredictionLogEntry.predictedSide],
 * falling back to P(YES) > 0.5) against settlement — the side the app
 * actually showed / would have bet.
 *
 * P(YES) / P(UP) Brier is `(p − 1_{result=yes})²` and stays a calibration
 * score of the YES probability. Picked-side Brier uses `p_side`
 * (P(NO) when the pick is NO) against the YES settlement bit so a fade of
 * p(YES)=0.945 that loses is ≈0.893, not the misleading P(YES) 0.003.
 */
object ForecastUnits {

    fun probability01(raw: Double): Double {
        if (!raw.isFinite()) return raw
        val unit = if (raw > 1.0 + 1e-9) raw / 100.0 else raw
        return unit.coerceIn(0.0, 1.0)
    }

    fun outcomeYes(outcome: String?): Boolean = outcome.equals("yes", ignoreCase = true)

    fun predictedYesSide(e: PredictionLogEntry): Boolean =
        probability01(e.predictedYes) > 0.5

    /** YES if the app picked YES / UP; NO otherwise. Null side → P(YES) > 0.5. */
    fun pickedSideIsYes(e: PredictionLogEntry): Boolean {
        return when (e.predictedSide?.trim()?.uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> predictedYesSide(e)
        }
    }

    /** NO BET / unset directional picks are excluded from hit-rate and Brier. */
    fun isScoredPick(e: PredictionLogEntry): Boolean =
        !SignalStance.isNoBetSide(e.predictedSide)

    /** Probability assigned to the picked side, 0–1. */
    fun sideProbability01(e: PredictionLogEntry): Double {
        val pYes = probability01(e.predictedYes)
        return if (pickedSideIsYes(e)) pYes else 1.0 - pYes
    }

    fun hit(e: PredictionLogEntry): Boolean {
        if (!isScoredPick(e)) return false
        val o = e.outcome ?: return false
        if (o.equals("void", ignoreCase = true)) return false
        return pickedSideIsYes(e) == outcomeYes(o)
    }

    /** P(YES) / P(UP) Brier — calibration of the YES probability. */
    fun brier(e: PredictionLogEntry): Double {
        val y = if (outcomeYes(e.outcome)) 1.0 else 0.0
        val p = probability01(e.predictedYes)
        val d = p - y
        return d * d
    }

    /**
     * Picked-side Brier: `p_side` is P(YES) when the pick is YES and
     * `1 − P(YES)` when the pick is NO. Compared to the YES settlement
     * bit so a losing fade does not collapse to the P(YES) Brier.
     *
     * predictedYes=0.945, predictedSide=NO, result=yes →
     * p_side=0.055, y=1, (0.055−1)² ≈ 0.893 (not 0.003).
     */
    fun sideBrier(e: PredictionLogEntry): Double {
        val pSide = sideProbability01(e)
        val yYes = if (outcomeYes(e.outcome)) 1.0 else 0.0
        val d = pSide - yYes
        return d * d
    }

    fun marketBrier(e: PredictionLogEntry): Double {
        val y = if (outcomeYes(e.outcome)) 1.0 else 0.0
        val p = probability01(e.marketMid)
        val d = p - y
        return d * d
    }

    fun sideFromProbability(predictedYes: Double): String =
        if (probability01(predictedYes) > 0.5) "YES" else "NO"

    fun storedScoreConflicts(e: PredictionLogEntry): Boolean {
        if (e.score == null || e.outcome == null) return false
        val recomputed = if (hit(e)) 1 else 0
        return e.score != recomputed
    }

    fun storedBrierLooksLikePercent(e: PredictionLogEntry): Boolean {
        val b = e.brier ?: return false
        return b > 1.0 + 1e-9 && abs(b - brier(e)) > 1e-6
    }
}
