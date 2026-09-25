package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import kotlin.math.abs

/**
 * Prediction-log probabilities must be in **0–1** for Brier and hit-rate.
 * 0.3.9 stored some rows as percent (44.0) and some as unit (0.44), and
 * scored hits from [PredictionLogEntry.predictedSide] (edge sign) while
 * Brier used [PredictionLogEntry.predictedYes]. That produced 0/5 hits
 * with Brier 0.003 on the same five settlements.
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

    fun hit(e: PredictionLogEntry): Boolean {
        val o = e.outcome ?: return false
        if (o.equals("void", ignoreCase = true)) return false
        return predictedYesSide(e) == outcomeYes(o)
    }

    fun brier(e: PredictionLogEntry): Double {
        val y = if (outcomeYes(e.outcome)) 1.0 else 0.0
        val p = probability01(e.predictedYes)
        val d = p - y
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
