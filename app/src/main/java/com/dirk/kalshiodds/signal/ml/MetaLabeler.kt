package com.dirk.kalshiodds.signal.ml

/**
 * Secondary take/skip head on top of the primary side pick.
 * Logistic SGD on |edge|, confidence, uncertainty, spread, anomaly.
 * Cold start always takes (does not hide 0.2.x opportunities).
 */
class MetaLabeler(
    var weights: DoubleArray = doubleArrayOf(-0.4, 0.35, 0.8, -1.2, -0.9, -0.7),
    var bias: Double = 0.2,
    var sampleCount: Int = 0
) {
    data class Result(
        val pTake: Double,
        val take: Boolean,
        val ready: Boolean,
        val note: String
    )

    fun predict(
        absEdgePp: Double,
        confidence: Double,
        uncertainty: Double,
        spread: Double?,
        anomaly: Double,
        threshold: Double = 0.45
    ): Result {
        val p = MlMath.clip01(MlMath.sigmoid(logit(absEdgePp, confidence, uncertainty, spread, anomaly)))
        val ready = sampleCount >= MIN_SAMPLES
        val take = !ready || p >= threshold
        return Result(
            pTake = p,
            take = take,
            ready = ready,
            note = if (!ready) "meta cold" else String.format(java.util.Locale.US, "meta %.0f%%", p * 100.0)
        )
    }

    fun update(
        absEdgePp: Double,
        confidence: Double,
        uncertainty: Double,
        spread: Double?,
        anomaly: Double,
        primaryHit: Boolean,
        lr: Double = 0.08
    ) {
        val x = features(absEdgePp, confidence, uncertainty, spread, anomaly)
        val p = MlMath.sigmoid(bias + (x.indices).sumOf { weights[it] * x[it] }).coerceIn(1e-4, 1.0 - 1e-4)
        val y = if (primaryHit) 1.0 else 0.0
        val err = y - p
        for (i in weights.indices) {
            weights[i] = (weights[i] + lr * err * x[i]).coerceIn(-4.0, 4.0)
        }
        bias = (bias + lr * err).coerceIn(-2.0, 2.0)
        sampleCount += 1
    }

    private fun logit(
        absEdgePp: Double,
        confidence: Double,
        uncertainty: Double,
        spread: Double?,
        anomaly: Double
    ): Double {
        val x = features(absEdgePp, confidence, uncertainty, spread, anomaly)
        return bias + (x.indices).sumOf { weights[it] * x[it] }
    }

    private fun features(
        absEdgePp: Double,
        confidence: Double,
        uncertainty: Double,
        spread: Double?,
        anomaly: Double
    ) = doubleArrayOf(
        1.0,
        (absEdgePp / 10.0).coerceIn(0.0, 3.0),
        confidence.coerceIn(0.0, 1.0),
        uncertainty.coerceIn(0.0, 0.5),
        (spread ?: 0.0).coerceIn(0.0, 0.25) * 8.0,
        anomaly.coerceIn(0.0, 1.0)
    )

    companion object {
        const val MIN_SAMPLES = 10
    }
}
