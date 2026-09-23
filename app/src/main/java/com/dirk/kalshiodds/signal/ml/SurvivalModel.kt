package com.dirk.kalshiodds.signal.ml

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Discrete-time hazard / survival: P(YES wins | time left, path).
 *
 * As TTE → 0 the mid dominates (the contract is settling). Earlier in
 * the window, momentum / imbalance / spot tilt the logit. Cold-start
 * safe: with empty path this is a mild pull of mid toward 0/1.
 */
object SurvivalModel {

    data class Result(
        val pYes: Double,
        val hazard: Double,
        val note: String
    )

    fun predict(
        mid: Double,
        tteFrac: Double,
        momentum: Double,
        imbalance: Double?,
        velocityPerSec: Double?,
        spot: Double?
    ): Result {
        val p0 = mid.coerceIn(0.02, 0.98)
        val t = tteFrac.coerceIn(0.0, 1.0)
        // Remaining-time weight: late window trusts the mid more.
        val pathW = (0.15 + 0.55 * t).coerceIn(0.10, 0.75)
        val mom = momentum.coerceIn(-0.25, 0.25)
        val imb = (imbalance ?: 0.0).coerceIn(-1.0, 1.0)
        val vel = (velocityPerSec ?: 0.0).coerceIn(-0.05, 0.05)
        val sp = (spot ?: 0.0).coerceIn(-0.03, 0.03)
        val tilt = 1.6 * mom + 0.35 * imb + 8.0 * vel + 6.0 * sp
        val z = MlMath.logit(p0) + pathW * tilt
        // Small late-window gravity toward the nearer absorbing barrier.
        val gravity = (0.5 - t) * 0.35 * if (p0 >= 0.5) 1.0 else -1.0
        val p = MlMath.clip01(MlMath.sigmoid(z + gravity))
        val hazard = (abs(p - p0) / sqrt((t + 0.05).coerceAtLeast(0.05))).coerceIn(0.0, 2.0)
        return Result(
            pYes = p,
            hazard = hazard,
            note = String.format(java.util.Locale.US, "surv %.0f%% h=%.2f", p * 100.0, hazard)
        )
    }
}
