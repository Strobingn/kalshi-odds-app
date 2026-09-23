package com.dirk.kalshiodds.signal.ml

/**
 * Compact autoencoder over an 8-d book snapshot.
 *
 * Features: bestBid, bestAsk, spread, imbalance, depthNear, depthFar,
 * cancelSpike, quotePull. Bottleneck (4-d) feeds the scorer / GBM.
 */
class MicrostructureEncoder(
    private val enc1: Array<FloatArray>,
    private val enc1b: FloatArray,
    private val enc2: Array<FloatArray>,
    private val enc2b: FloatArray,
    private val dec1: Array<FloatArray>,
    private val dec1b: FloatArray,
    private val dec2: Array<FloatArray>,
    private val dec2b: FloatArray
) {
    fun encode(x: FloatArray): FloatArray {
        val h = MlMath.dense(x, enc1, enc1b, MlMath::relu)
        return MlMath.dense(h, enc2, enc2b) { it }
    }

    fun reconstruct(z: FloatArray): FloatArray {
        val h = MlMath.dense(z, dec1, dec1b, MlMath::relu)
        return MlMath.dense(h, dec2, dec2b) { it }
    }

    fun reconstructionError(x: FloatArray): Double {
        val y = reconstruct(encode(x))
        var s = 0.0
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i] - y[i]).toDouble()
            s += d * d
        }
        return if (n == 0) 0.0 else s / n
    }

    /** Scalar “book surprise” used as a GBM feature. */
    fun surprise(x: FloatArray): Double = reconstructionError(x)

    companion object {
        const val INPUT = 8
        const val HIDDEN = 6
        const val LATENT = 4

        fun snapshot(
            bestBid: Double?,
            bestAsk: Double?,
            spread: Double?,
            imbalance: Double?,
            depthNear: Double?,
            depthFar: Double?,
            cancelSpike: Double?,
            quotePull: Double?
        ): FloatArray {
            val bid = (bestBid ?: 0.5).toFloat()
            val ask = (bestAsk ?: 0.5).toFloat()
            val spr = (spread ?: kotlin.math.abs((bestAsk ?: 0.5) - (bestBid ?: 0.5))).toFloat()
            val near = kotlin.math.ln(1.0 + (depthNear ?: 0.0)).toFloat() / 6f
            val far = kotlin.math.ln(1.0 + (depthFar ?: 0.0)).toFloat() / 6f
            return floatArrayOf(
                bid.coerceIn(0f, 1f),
                ask.coerceIn(0f, 1f),
                spr.coerceIn(0f, 1f),
                (imbalance ?: 0.0).toFloat().coerceIn(-1f, 1f),
                near.coerceIn(0f, 1f),
                far.coerceIn(0f, 1f),
                (cancelSpike ?: 0.0).toFloat().coerceIn(-1f, 1f),
                (quotePull ?: 0.0).toFloat().coerceIn(-1f, 1f)
            )
        }

        /**
         * Near-identity encoder so unused channels still pass through a
         * compressed view of bid/ask/imb/depth.
         */
        fun defaults(): MicrostructureEncoder {
            val e1 = Array(HIDDEN) { i ->
                val row = FloatArray(INPUT)
                if (i < INPUT) row[i] = 0.85f
                row
            }
            val e1b = FloatArray(HIDDEN)
            val e2 = Array(LATENT) { i ->
                val row = FloatArray(HIDDEN)
                row[i % HIDDEN] = 0.90f
                if (i + LATENT < HIDDEN) row[i + LATENT] = 0.15f
                row
            }
            val e2b = FloatArray(LATENT)
            val d1 = Array(HIDDEN) { i ->
                val row = FloatArray(LATENT)
                row[i % LATENT] = 0.90f
                row
            }
            val d1b = FloatArray(HIDDEN)
            val d2 = Array(INPUT) { i ->
                val row = FloatArray(HIDDEN)
                if (i < HIDDEN) row[i] = 0.85f
                row
            }
            val d2b = FloatArray(INPUT)
            return MicrostructureEncoder(e1, e1b, e2, e2b, d1, d1b, d2, d2b)
        }
    }
}
