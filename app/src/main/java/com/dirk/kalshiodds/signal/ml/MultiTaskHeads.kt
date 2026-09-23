package com.dirk.kalshiodds.signal.ml

/**
 * Heads on the shared 16-d backbone:
 *  - P(YES) logit
 *  - time-to-move (seconds until |Δmid| ≳ 2pp)
 *  - mid volatility (next-window std, probability units)
 *  - P(fill at the conservative limit)
 *
 * Last-layer weights are what [ContinualFineTune] updates from settlements.
 */
data class HeadWeights(
    val yesW: FloatArray,
    val yesB: Float,
    val ttmW: FloatArray,
    val ttmB: Float,
    val volW: FloatArray,
    val volB: Float,
    val fillW: FloatArray,
    val fillB: Float
) {
    fun copyOf(): HeadWeights = HeadWeights(
        yesW.copyOf(), yesB,
        ttmW.copyOf(), ttmB,
        volW.copyOf(), volB,
        fillW.copyOf(), fillB
    )
}

data class MultiTaskOutput(
    val pYes: Double,
    val timeToMoveSec: Double,
    val midVol: Double,
    val pFill: Double
)

class MultiTaskHeads(var weights: HeadWeights) {

    fun infer(h: FloatArray): MultiTaskOutput {
        val w = weights
        val zYes = MlMath.dot(h, w.yesW) + w.yesB
        val zTtm = MlMath.dot(h, w.ttmW) + w.ttmB
        val zVol = MlMath.dot(h, w.volW) + w.volB
        val zFill = MlMath.dot(h, w.fillW) + w.fillB
        return MultiTaskOutput(
            pYes = MlMath.clip01(MlMath.sigmoid(zYes.toDouble())),
            timeToMoveSec = (MlMath.softplus(zTtm.toDouble()) * 45.0).coerceIn(2.0, 600.0),
            midVol = (MlMath.softplus(zVol.toDouble()) * 0.04).coerceIn(0.001, 0.25),
            pFill = MlMath.clip01(MlMath.sigmoid(zFill.toDouble()))
        )
    }

    companion object {
        fun defaults(): MultiTaskHeads {
            val d = SharedBackbone.DIM
            val yes = FloatArray(d)
            yes[0] = 0.85f
            yes[2] = 0.55f
            yes[3] = 0.40f
            val ttm = FloatArray(d)
            ttm[1] = -0.35f
            ttm[4] = 0.25f
            val vol = FloatArray(d)
            vol[1] = 0.45f
            vol[4] = 0.30f
            val fill = FloatArray(d)
            fill[4] = 0.50f
            fill[5] = 0.25f
            return MultiTaskHeads(
                HeadWeights(
                    yesW = yes, yesB = 0f,
                    ttmW = ttm, ttmB = 0.4f,
                    volW = vol, volB = -0.8f,
                    fillW = fill, fillB = 0.1f
                )
            )
        }
    }
}

/**
 * Heuristic fallbacks when the sequence encoder is cold. Never invents
 * confidence — they degrade to null-ish mid-range values.
 */
object MultiTaskHeuristics {
    fun timeToMoveSec(velocityPerSec: Double?, thresholdPp: Double = 2.0): Double? {
        val v = velocityPerSec ?: return null
        val abs = kotlin.math.abs(v)
        if (abs < 1e-5) return null
        return ((thresholdPp / 100.0) / abs).coerceIn(2.0, 600.0)
    }

    fun midVol(mids: List<Double>): Double? {
        if (mids.size < 3) return null
        val mean = mids.average()
        val var_ = mids.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(var_).coerceIn(0.001, 0.25)
    }

    fun pFill(depthNear: Double?, contracts: Double?, spread: Double?): Double? {
        if (depthNear == null && contracts == null) return null
        val need = (contracts ?: 1.0).coerceAtLeast(1.0)
        val depth = (depthNear ?: 0.0).coerceAtLeast(0.0)
        val raw = if (need <= 0.0) 0.5 else (depth / need).coerceIn(0.0, 1.5)
        val spreadPen = 1.0 - ((spread ?: 0.0) / 0.20).coerceIn(0.0, 0.40)
        return MlMath.clip01(0.25 + 0.60 * (raw / 1.5) * spreadPen)
    }
}
