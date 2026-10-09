package com.dirk.kalshiodds.signal.ml

/**
 * Ensemble-variance (or MC-dropout proxy) gate.
 *
 * A ticket / high-rank opportunity only proceeds when [uncertainty] is at
 * or below the Settings threshold. A single live model is treated as a
 * mild proxy so 0.2.x fallback is not blocked.
 */
object UncertaintyGate {

    data class Result(
        val uncertainty: Double,
        val passed: Boolean,
        val method: String,
        val nMembers: Int
    )

    fun evaluate(
        members: List<Double>,
        threshold: Double,
        enabled: Boolean,
        mcSamples: List<Double> = emptyList()
    ): Result {
        val live = members.filter { it.isFinite() }
        val mc = mcSamples.filter { it.isFinite() }
        val (unc, method, n) = when {
            live.size >= 2 -> Triple(MlMath.stddev(live), "ensemble", live.size)
            mc.size >= 2 -> Triple(MlMath.stddev(mc), "mc-dropout", mc.size)
            live.size == 1 -> Triple(singleModelProxy(live[0]), "single-proxy", 1)
            else -> Triple(0.06, "cold", 0)
        }
        val clipped = unc.coerceIn(0.0, 0.50)
        val passed = !enabled || clipped <= threshold
        return Result(uncertainty = clipped, passed = passed, method = method, nMembers = n)
    }

    /**
     * When only the MLP is live, use a conservative stand-in so the gate
     * can still fire on a 50/50 coin-flip without inventing ensemble var.
     */
    fun singleModelProxy(pYes: Double): Double {
        val p = pYes.coerceIn(0.02, 0.98)
        return (0.18 * (1.0 - kotlin.math.abs(p - 0.5) * 2.0)).coerceIn(0.02, 0.18)
    }

    fun dropoutMask(size: Int, rate: Double, seed: Int): BooleanArray {
        val mask = BooleanArray(size)
        var x = seed.takeIf { it != 0 } ?: 1
        for (i in 0 until size) {
            x = x * 1_664_525 + 1_013_904_223
            val u = ((x ushr 1) and 0x7fff_ffff) / 2147483647.0
            mask[i] = u < rate
        }
        return mask
    }
}
