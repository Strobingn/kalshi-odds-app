package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Compact tabular features for the imported offline edge model.
 * Order **must** match `ml/train_edge.py` / the JSON `feature_names`.
 *
 * Momentum and realized_vol come from 8 one-minute Coinbase closes
 * (`candleWindow`), not from a few seconds of live ticks.
 * time_of_day is the UTC hour / 24.
 */
object EdgeFeatures {
    const val SIZE = 10
    const val CANDLE_BARS = 8
    val NAMES = listOf(
        "dist_to_strike_vol",
        "tte_frac",
        "market_mid",
        "imbalance",
        "spread",
        "momentum",
        "realized_vol",
        "cross_asset",
        "time_of_day",
        "digital_fair"
    )

    data class Raw(
        val spot: Double? = null,
        val strike: Double? = null,
        val tteSeconds: Double? = null,
        val sigmaAnnual: Double? = null,
        val marketMid: Double,
        val imbalance: Double? = null,
        val spread: Double? = null,
        val momentum: Double? = null,
        val realizedVol01: Double? = null,
        val crossAssetRet: Double? = null,
        val nowMs: Long = System.currentTimeMillis(),
        val digitalFair: Double? = null,
        val coinbaseCloses: List<Double> = emptyList()
    )

    fun build(raw: Raw): FloatArray {
        val tte = (raw.tteSeconds ?: 900.0).coerceIn(0.0, 1800.0)
        val tteFrac = (tte / 900.0).toFloat().coerceIn(0f, 2f)
        val dist = if (raw.spot != null && raw.strike != null && raw.sigmaAnnual != null) {
            DigitalOptionFairValue.distanceVolUnits(raw.spot, raw.strike, tte, raw.sigmaAnnual)
        } else {
            null
        }
        val digital = raw.digitalFair
            ?: if (raw.spot != null && raw.strike != null && raw.sigmaAnnual != null) {
                DigitalOptionFairValue.pFinishAbove(raw.spot, raw.strike, tte, raw.sigmaAnnual)
            } else {
                null
            }
        val window = candleWindow(raw.coinbaseCloses)
        val momentum = window?.momentum ?: raw.momentum ?: 0.0
        val rvol = window?.realizedVol ?: raw.realizedVol01 ?: 0.0
        val tod = timeOfDayFrac(raw.nowMs)
        return floatArrayOf(
            (dist ?: 0.0).toFloat().coerceIn(-8f, 8f),
            tteFrac,
            raw.marketMid.toFloat().coerceIn(0f, 1f),
            (raw.imbalance ?: 0.0).toFloat().coerceIn(-1f, 1f),
            (raw.spread ?: 0.0).toFloat().coerceIn(0f, 1f),
            momentum.toFloat().coerceIn(-1f, 1f),
            rvol.toFloat().coerceIn(0f, 1f),
            (raw.crossAssetRet ?: 0.0).toFloat().coerceIn(-0.2f, 0.2f),
            tod,
            (digital ?: raw.marketMid).toFloat().coerceIn(0f, 1f)
        )
    }

    /**
     * Same formula as `train_edge.coinbase_window_features`:
     * momentum = (last − first) / first; realized_vol = pop-std / mean.
     */
    data class CandleWindow(val momentum: Double, val realizedVol: Double)

    fun candleWindow(closes: List<Double>): CandleWindow? {
        val xs = closes.filter { it.isFinite() && it > 0.0 }.takeLast(CANDLE_BARS)
        if (xs.size < 2) return null
        val mom = (xs.last() - xs.first()) / xs.first()
        val rvol = if (xs.size >= 3) {
            val mu = xs.average()
            if (mu > 0.0) {
                val var_ = xs.map { val d = it - mu; d * d }.average()
                sqrt(var_.coerceAtLeast(0.0)) / mu
            } else {
                0.0
            }
        } else {
            0.0
        }
        return CandleWindow(
            momentum = mom.coerceIn(-1.0, 1.0),
            realizedVol = rvol.coerceIn(0.0, 1.0)
        )
    }

    fun timeOfDayFrac(nowMs: Long, tz: TimeZone = TimeZone.getTimeZone("UTC")): Float {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return (hour / 24.0).toFloat().coerceIn(0f, 1f)
    }

    fun seriesId(ticker: String): Int {
        val u = ticker.uppercase()
        return when {
            u.contains("ETH") && !u.contains("BTC") -> 1
            u.contains("SOL") -> 2
            else -> 0
        }
    }

    fun logMoneyness(spot: Double, strike: Double): Double? {
        if (spot <= 0.0 || strike <= 0.0) return null
        return ln(spot / strike)
    }
}
