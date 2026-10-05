package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.model.ProbabilityClamp
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
 * score of the YES probability. Picked-side Brier uses P(NO) and the NO
 * outcome for a NO pick. With binary outcomes the two Briers are equal.
 * Side hit rate and realized P&L measure the decision to trade.
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

    /**
     * Side the model thinks will win: YES when P(YES) > 50%, NO when
     * P(YES) < 50%. Null when the 2–98% clamp is binding, or at exactly 50%.
     * This is not the EV side (model versus market).
     */
    fun modelWinnerSide(e: PredictionLogEntry): String? = modelWinnerSide(e.predictedYes)

    fun modelWinnerSide(predictedYes: Double): String? {
        val p = probability01(predictedYes)
        if (!p.isFinite()) return null
        if (ProbabilityClamp.binding(p)) return null
        return when {
            p > 0.5 + 1e-12 -> "YES"
            p < 0.5 - 1e-12 -> "NO"
            else -> null
        }
    }

    /**
     * Side both prediction-log writers store. Model winner (P(YES) vs 50%),
     * not the edge-versus-market side. Clamp-bound or 50/50 is NO BET so
     * the row is not scored as a directional pick.
     */
    fun loggedModelSide(predictedYes: Double): String =
        modelWinnerSide(predictedYes) ?: SignalStance.NO_BET

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
     * `1 − P(YES)` when the pick is NO. Compare with whether that same
     * side settles, so complementing the forecast and outcome leaves the
     * proper score unchanged. A low Brier does not mean a bet was good.
     *
     * predictedYes=0.20, predictedSide=NO, result=no →
     * p_side=0.80, y_NO=1, (0.80−1)² = 0.04 (not (0.80−0)² = 0.64).
     */
    fun sideBrier(e: PredictionLogEntry): Double {
        val pSide = sideProbability01(e)
        val ySide = if (pickedSideIsYes(e) == outcomeYes(e.outcome)) 1.0 else 0.0
        val d = pSide - ySide
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
