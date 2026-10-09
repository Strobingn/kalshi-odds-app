package com.dirk.kalshiodds.prediction

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shared 8-feature builder matching ml/FEATURES.md / training.
 *
 * Order:
 * 0 mid_price, 1 volume_norm, 2 tte_frac, 3 volatility,
 * 4 momentum, 5 mean_reversion, 6 series_id, 7 oi_norm
 */
object FeatureVector {
    const val SIZE = 8
    val NAMES = listOf(
        "mid_price",
        "volume_norm",
        "tte_frac",
        "volatility",
        "momentum",
        "mean_reversion",
        "series_id",
        "oi_norm"
    )

    /** Softmax output: index 0 = P(NO), index 1 = P(YES). */
    const val IDX_NO = 0
    const val IDX_YES = 1

    /** 0 = crypto (BTC/ETH/SOL + peers). 1 = legacy WTI training class — unused at runtime. */
    fun seriesId(ticker: String): Float =
        if (ticker.uppercase().contains("WTI")) 1f else 0f

    fun build(
        mid: Double,
        volume: Double,
        closeEpochMs: Long?,
        nowMs: Long,
        series: List<FeatureHistory.Point>,
        ticker: String,
        openInterest: Double = 0.0
    ): FloatArray {
        val secsToClose = closeEpochMs?.let { ((it - nowMs) / 1000.0).coerceAtLeast(0.0) } ?: 900.0
        val tteFrac = (secsToClose / 900.0).coerceIn(0.0, 1.0)
        val volumeNorm = ln(1.0 + volume) / ln(1.0 + 1_000_000.0)
        val oiNorm = ln(1.0 + openInterest.coerceAtLeast(0.0)) / ln(1.0 + 1_000_000.0)

        val recent = series.takeLast(8)
        val mids = recent.map { it.mid }
        val volatility = when {
            mids.size >= 3 -> {
                val mean = mids.average()
                sqrt(mids.map { (it - mean) * (it - mean) }.average()).coerceIn(0.0, 1.0)
            }
            else -> 0.05
        }
        val momentum = when {
            mids.size >= 2 -> (mids.last() - mids.first()).coerceIn(-1.0, 1.0)
            else -> 0.0
        }
        val meanReversion = 0.5 - mid

        return floatArrayOf(
            mid.toFloat().coerceIn(0f, 1f),
            volumeNorm.toFloat(),
            tteFrac.toFloat(),
            volatility.toFloat(),
            momentum.toFloat(),
            meanReversion.toFloat(),
            seriesId(ticker),
            oiNorm.toFloat()
        )
    }

    fun standardize(raw: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
        val out = FloatArray(SIZE)
        for (i in 0 until SIZE) {
            val s = if (std[i] < 1e-6f) 1f else std[i]
            out[i] = (raw[i] - mean[i]) / s
        }
        return out
    }
}
