package com.dirk.kalshiodds.signal.ml

import kotlinx.serialization.Serializable

/**
 * Stacks MLP + Temporal CNN + TinyLSTM + GBM. Missing members drop out and
 * the rest renormalize, so a cold phone (MLP only) is identical to 0.2.x.
 */
object EnsembleStack {

    @Serializable
    data class Weights(
        val mlp: Double = 0.55,
        val cnn: Double = 0.20,
        val lstm: Double = 0.10,
        val gbm: Double = 0.15,
        val sampleCount: Int = 0
    ) {
        val ready: Boolean get() = sampleCount >= MIN_STACK_SAMPLES
    }

    data class Member(
        val name: String,
        val pYes: Double,
        val enabled: Boolean = true
    )

    data class Result(
        val pYes: Double,
        val members: List<Member>,
        val used: List<String>
    )

    fun identity(): Weights = Weights()

    fun blend(members: List<Member>, weights: Weights): Result {
        val live = members.filter { it.enabled && it.pYes.isFinite() }
        if (live.isEmpty()) {
            return Result(pYes = 0.5, members = members, used = emptyList())
        }
        if (live.size == 1) {
            return Result(pYes = MlMath.clip01(live[0].pYes), members = live, used = listOf(live[0].name))
        }
        val raw = live.map { it to weightOf(it.name, weights) }
        val sumW = raw.sumOf { it.second }.coerceAtLeast(1e-9)
        val p = raw.sumOf { (m, w) -> m.pYes * w } / sumW
        return Result(
            pYes = MlMath.clip01(p),
            members = live,
            used = live.map { it.name }
        )
    }

    /**
     * One SGD step on log-loss of the stacked p, nudging member weights
     * toward models that agreed with the outcome.
     */
    fun update(state: Weights, members: List<Member>, outcomeYes: Boolean, lr: Double = 0.08): Weights {
        val live = members.filter { it.enabled && it.pYes.isFinite() }
        if (live.isEmpty()) return state
        val y = if (outcomeYes) 1.0 else 0.0
        val blended = blend(live, state)
        val err = y - blended.pYes
        var mlp = state.mlp
        var cnn = state.cnn
        var lstm = state.lstm
        var gbm = state.gbm
        for (m in live) {
            val agree = 1.0 - kotlin.math.abs(m.pYes - y)
            val delta = lr * err * (m.pYes - 0.5) * (0.5 + agree)
            when (m.name) {
                "mlp" -> mlp = (mlp + delta).coerceIn(0.15, 0.80)
                "cnn" -> cnn = (cnn + delta).coerceIn(0.00, 0.50)
                "lstm" -> lstm = (lstm + delta).coerceIn(0.00, 0.40)
                "gbm" -> gbm = (gbm + delta).coerceIn(0.00, 0.50)
            }
        }
        val sum = mlp + cnn + lstm + gbm
        return Weights(
            mlp = mlp / sum,
            cnn = cnn / sum,
            lstm = lstm / sum,
            gbm = gbm / sum,
            sampleCount = state.sampleCount + 1
        )
    }

    private fun weightOf(name: String, w: Weights): Double = when (name) {
        "mlp" -> w.mlp
        "cnn" -> w.cnn
        "lstm" -> w.lstm
        "gbm" -> w.gbm
        else -> 0.0
    }

    const val MIN_STACK_SAMPLES = 8
}
