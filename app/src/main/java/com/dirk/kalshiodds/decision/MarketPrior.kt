package com.dirk.kalshiodds.decision

/**
 * final logit = logit(market mid) + bucket bias + bounded feature correction.
 * The correction is 0 unless the published model marks the coefficients validated.
 * Disagreement with the market has to be earned. No display floor.
 */
object MarketPrior {
    const val DEFAULT_CAP = 0.5

    data class Spec(
        val validated: Boolean = false,
        val cap: Double = DEFAULT_CAP,
        val featureCoefficients: DoubleArray = doubleArrayOf(),
        /** Keyed by "series|priceLevel|timeLeft". */
        val bucketBias: Map<String, Double> = emptyMap()
    )

    fun bucketKey(series: String, priceLevel: String, timeLeft: String): String =
        "${series.trim().uppercase()}|$priceLevel|$timeLeft"

    fun correction(
        spec: Spec,
        features: DoubleArray,
        bucketKey: String
    ): Double {
        if (!spec.validated) return 0.0
        var sum = spec.bucketBias[bucketKey] ?: 0.0
        val n = minOf(spec.featureCoefficients.size, features.size)
        for (i in 0 until n) sum += spec.featureCoefficients[i] * features[i]
        return sum.coerceIn(-spec.cap, spec.cap)
    }

    fun probability(
        marketMid: Double,
        spec: Spec = Spec(),
        features: DoubleArray = doubleArrayOf(),
        bucketKey: String = ""
    ): Double {
        if (!marketMid.isFinite()) return marketMid
        val corr = correction(spec, features, bucketKey)
        return DecisionMath.sigmoid(DecisionMath.logit(marketMid) + corr)
    }
}
