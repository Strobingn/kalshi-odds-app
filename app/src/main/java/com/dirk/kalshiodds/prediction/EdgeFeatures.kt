package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ln

/**
 * Compact tabular features for the imported offline edge model.
 * Order **must** match `ml/train_edge.py` / the JSON `feature_names`.
 */
object EdgeFeatures {
    const val SIZE = 10
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
        val digitalFair: Double? = null
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
        val tod = timeOfDayFrac(raw.nowMs)
        return floatArrayOf(
            (dist ?: 0.0).toFloat().coerceIn(-8f, 8f),
            tteFrac,
            raw.marketMid.toFloat().coerceIn(0f, 1f),
            (raw.imbalance ?: 0.0).toFloat().coerceIn(-1f, 1f),
            (raw.spread ?: 0.0).toFloat().coerceIn(0f, 1f),
            (raw.momentum ?: 0.0).toFloat().coerceIn(-1f, 1f),
            (raw.realizedVol01 ?: 0.0).toFloat().coerceIn(0f, 1f),
            (raw.crossAssetRet ?: 0.0).toFloat().coerceIn(-0.2f, 0.2f),
            tod,
            (digital ?: raw.marketMid).toFloat().coerceIn(0f, 1f)
        )
    }

    fun timeOfDayFrac(nowMs: Long, tz: TimeZone = TimeZone.getTimeZone("America/New_York")): Float {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMs
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return (minutes / (24.0 * 60.0)).toFloat().coerceIn(0f, 1f)
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
