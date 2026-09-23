package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.engine.RegimeTag
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monte Carlo mid paths conditioned on vol / regime.
 * Returns P(predicted edge still has the same sign at expiry).
 */
object PathSimulator {
    const val DEFAULT_PATHS = 48
    const val STEPS = 10

    data class Result(
        val pSurvive: Double,
        val pYesExpiry: Double,
        val paths: Int,
        val note: String
    )

    fun simulate(
        mid: Double,
        fairYes: Double,
        tteSeconds: Long?,
        vol: Double,
        velocityPerSec: Double?,
        regime: RegimeTag,
        paths: Int = DEFAULT_PATHS,
        seed: Int = 17
    ): Result {
        val m0 = mid.coerceIn(0.02, 0.98)
        val sideYes = fairYes >= m0
        val tte = (tteSeconds ?: 300L).coerceIn(5L, 900L).toDouble()
        val dt = tte / STEPS
        val volMul = when (regime) {
            RegimeTag.VOL_SPIKE -> 1.8
            RegimeTag.CHOP -> 1.25
            RegimeTag.TREND -> 0.90
            RegimeTag.QUIET -> 0.65
        }
        val sigma = (vol.coerceIn(0.002, 0.20) * volMul).coerceIn(0.002, 0.25)
        val drift = (velocityPerSec ?: 0.0).coerceIn(-0.02, 0.02)
        var survive = 0
        var yesEnd = 0
        var rng = seed.takeIf { it != 0 } ?: 1
        repeat(paths.coerceIn(8, 128)) {
            var x = m0
            repeat(STEPS) {
                rng = rng * 1_664_525 + 1_013_904_223
                val u = ((rng ushr 1) and 0x7fff_ffff) / 2147483647.0
                rng = rng * 1_664_525 + 1_013_904_223
                val v = ((rng ushr 1) and 0x7fff_ffff) / 2147483647.0
                val z = boxMuller(u, v)
                x = (x + drift * dt + sigma * sqrt(dt) * z).coerceIn(0.01, 0.99)
            }
            val endYes = x >= 0.5
            if (endYes) yesEnd += 1
            val still = if (sideYes) x >= m0 - 1e-9 || endYes else x <= m0 + 1e-9 || !endYes
            // Edge survives if expiry side matches the predicted side.
            if ((sideYes && endYes) || (!sideYes && !endYes)) survive += 1
            else if (still && abs(x - 0.5) < 0.02) {
                /* near-tie already counted above */
            }
        }
        val n = paths.coerceIn(8, 128).toDouble()
        val pS = survive / n
        val pY = yesEnd / n
        return Result(
            pSurvive = pS,
            pYesExpiry = pY,
            paths = n.toInt(),
            note = String.format(java.util.Locale.US, "MC P(edge) %.0f%%", pS * 100.0)
        )
    }

    internal fun boxMuller(u: Double, v: Double): Double {
        val a = u.coerceIn(1e-9, 1.0 - 1e-9)
        val b = v.coerceIn(1e-9, 1.0 - 1e-9)
        return sqrt(-2.0 * kotlin.math.ln(a)) * kotlin.math.cos(2.0 * Math.PI * b)
    }
}
