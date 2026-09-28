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
        val windowVol = volumeLastMinutes(series, nowMs, minutes = 8) ?: 0.0
        val volumeNorm = ln(1.0 + windowVol) / ln(1.0 + 1_000_000.0)
        val oiNorm = ln(1.0 + openInterest.coerceAtLeast(0.0)) / ln(1.0 + 1_000_000.0)

        val minute = lastMinute(series, nowMs)
        val mids = minute.map { it.mid }
        val volatility = if (mids.size >= 2) {
            (mids.maxOrNull()!! - mids.minOrNull()!!).coerceIn(0.0, 1.0)
        } else {
            0.05
        }
        val eightMin = lastMinutes(series, nowMs, minutes = 8)
        val eightMids = eightMin.map { it.mid }
        val momentum = when {
            eightMids.size >= 2 -> (eightMids.last() - eightMids.first()).coerceIn(-1.0, 1.0)
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

    /** Points in the last [minutes] minutes, oldest first. */
    fun lastMinutes(
        series: List<FeatureHistory.Point>,
        nowMs: Long,
        minutes: Int
    ): List<FeatureHistory.Point> {
        val cut = nowMs - minutes * 60_000L
        return series.filter { it.nowMs >= cut }
    }

    fun lastMinute(series: List<FeatureHistory.Point>, nowMs: Long): List<FeatureHistory.Point> =
        lastMinutes(series, nowMs, 1)

    /**
     * 8-minute volume: delta of cumulative market volume over the window,
     * matching training's sum of 8 one-minute candle volumes.
     */
    fun volumeLastMinutes(
        series: List<FeatureHistory.Point>,
        nowMs: Long,
        minutes: Int
    ): Double? {
        val win = lastMinutes(series, nowMs, minutes)
        if (win.size < 2) return null
        val delta = win.last().volume - win.first().volume
        return delta.coerceAtLeast(0.0)
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
