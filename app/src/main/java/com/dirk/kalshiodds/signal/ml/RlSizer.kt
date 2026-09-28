package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.exp

/**
 * Advisory contextual-bandit sizer. Softmax over stake fractions of the
 * user-configured ticket stake. **Never places an order.** Respects the
 * $10 max ticket stake. Cold start is identity (1.0 × configured).
 */
class RlSizer(
    val logits: DoubleArray = DoubleArray(ACTIONS.size),
    var sampleCount: Int = 0
) {
    data class Advice(
        val fraction: Double,
        val stakeUsd: Double,
        val actionIndex: Int,
        val ready: Boolean,
        val note: String
    )

    fun suggest(
        edgePp: Double,
        confidence: Double,
        uncertainty: Double,
        tteFrac: Double,
        configuredStake: Double
    ): Advice {
        val idx = policyIndex(edgePp, confidence, uncertainty, tteFrac)
        val frac = ACTIONS[idx]
        val stake = clipStake(configuredStake * frac)
        val ready = sampleCount >= MIN_SAMPLES
        return Advice(
            fraction = frac,
            stakeUsd = stake,
            actionIndex = idx,
            ready = ready,
            note = if (ready) {
                String.format(java.util.Locale.US, "RL ×%.2f → $%.2f (advisory)", frac, stake)
            } else {
                "RL cold — using configured stake"
            }
        )
    }

    fun update(
        edgePp: Double,
        confidence: Double,
        uncertainty: Double,
        tteFrac: Double,
        actionIndex: Int,
        reward: Double,
        lr: Double = 0.08
    ) {
        val feats = features(edgePp, confidence, uncertainty, tteFrac)
        val probs = policy(feats)
        val a = actionIndex.coerceIn(0, ACTIONS.lastIndex)
        for (i in logits.indices) {
            val adv = if (i == a) 1.0 - probs[i] else -probs[i]
            logits[i] = (logits[i] + lr * reward * adv).coerceIn(-3.0, 3.0)
        }
        sampleCount += 1
    }

    fun policyIndex(edgePp: Double, confidence: Double, uncertainty: Double, tteFrac: Double): Int {
        val p = policy(features(edgePp, confidence, uncertainty, tteFrac))
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
        return best
    }

    fun snapshot(): Pair<DoubleArray, Int> = logits.copyOf() to sampleCount

    fun restore(saved: DoubleArray, n: Int) {
        for (i in logits.indices) if (i < saved.size) logits[i] = saved[i]
        sampleCount = n
    }

    private fun features(edgePp: Double, confidence: Double, uncertainty: Double, tteFrac: Double) =
        doubleArrayOf(
            1.0,
            (absEdge(edgePp) / 10.0).coerceIn(0.0, 3.0),
            confidence.coerceIn(0.0, 1.0),
            (1.0 - uncertainty).coerceIn(0.0, 1.0),
            tteFrac.coerceIn(0.0, 1.0)
        )

    private fun policy(feats: DoubleArray): DoubleArray {
        val z = DoubleArray(ACTIONS.size) { i ->
            logits[i] + 0.15 * feats[1] * (ACTIONS[i] - 1.0)
        }
        val m = z.maxOrNull() ?: 0.0
        val e = z.map { exp(it - m) }
        val s = e.sum().coerceAtLeast(1e-12)
        return e.map { it / s }.toDoubleArray()
    }

    companion object {
        val ACTIONS = doubleArrayOf(0.25, 0.50, 1.00, 1.25)
        const val MIN_SAMPLES = 8

        fun clipStake(raw: Double): Double =
            raw.coerceIn(
                SignalConstants.TICKET_STAKE_MIN_USD,
                SignalConstants.TICKET_STAKE_HARD_CAP_USD
            )

        fun rewardFromSettlement(hit: Boolean, edgePp: Double): Double {
            val mag = (kotlin.math.abs(edgePp) / 10.0).coerceIn(0.2, 2.0)
            return if (hit) mag else -mag
        }

        private fun absEdge(edgePp: Double) = kotlin.math.abs(edgePp)
    }
}
