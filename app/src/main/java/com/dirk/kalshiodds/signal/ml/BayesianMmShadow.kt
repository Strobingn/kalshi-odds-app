package com.dirk.kalshiodds.signal.ml

/**
 * Bayesian market-maker shadow: latent fair + inventory pressure
 * inferred from mid / imbalance / cancels. One more ensemble voter.
 */
class BayesianMmShadow(private val alpha: Double = 0.18, private val shade: Double = 0.12) {
    data class State(
        val fair: Double = 0.5,
        val inventory: Double = 0.0,
        val shadow: Double = 0.5,
        val n: Int = 0
    )

    data class Result(
        val shadowYes: Double,
        val inventory: Double,
        val note: String
    )

    private val byTicker = linkedMapOf<String, State>()

    @Synchronized
    fun update(
        ticker: String,
        mid: Double,
        imbalance: Double?,
        cancelSpike: Double?,
        depthQuality: Double?
    ): Result {
        val prev = byTicker[ticker] ?: State(fair = mid.coerceIn(0.02, 0.98))
        val m = mid.coerceIn(0.02, 0.98)
        val imb = (imbalance ?: 0.0).coerceIn(-1.0, 1.0)
        val dq = (depthQuality ?: 0.4).coerceIn(0.0, 1.0)
        val cancel = (cancelSpike ?: 0.0).coerceIn(-1.0, 1.0)
        val fair = ((1.0 - alpha) * prev.fair + alpha * (m + 0.15 * imb * dq)).coerceIn(0.02, 0.98)
        val inv = ((1.0 - 0.25) * prev.inventory + 0.25 * (imb - 0.35 * cancel)).coerceIn(-1.0, 1.0)
        val shadow = (fair - shade * inv).coerceIn(0.02, 0.98)
        byTicker[ticker] = State(fair = fair, inventory = inv, shadow = shadow, n = prev.n + 1)
        if (byTicker.size > 48) byTicker.remove(byTicker.keys.first())
        return Result(
            shadowYes = shadow,
            inventory = inv,
            note = String.format(java.util.Locale.US, "MM θ=%.0f inv%+.0f", shadow * 100.0, inv * 100.0)
        )
    }
}
