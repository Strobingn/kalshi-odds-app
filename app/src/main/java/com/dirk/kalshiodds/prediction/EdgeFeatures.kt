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
    const val SIZE = 13
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
        "digital_fair",
        "prev_window_return",
        "is_funding_hour",
        "mid_squared"
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
        /**
         * Simple return over the FULL PREVIOUS 15-minute window (the window
         * before this market opened): the documented 15-minute sign
         * reversal tilt (arXiv 2608.21888: 50.2% -> 53.0% sign-flip by
         * prior-move size). Positive = BTC rose into this market's open.
         */
        val prevWindowReturn: Double? = null,
        /** True when this window closes into a perpetual funding settlement
         * (close time at 00/08/16 UTC): documented weaker reversal accuracy
         * and different payoff geometry in those hours. Parity with the
         * trainer's is_funding_hour. Derived from close time when null. */
        val isFundingHour: Boolean? = null
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
            (digital ?: raw.marketMid).toFloat().coerceIn(0f, 1f),
            (raw.prevWindowReturn ?: 0.0).toFloat().coerceIn(-0.05f, 0.05f),
            if (raw.isFundingHour ?: isFundingHour(raw.nowMs, raw.tteSeconds)) 1f else 0f,
            (raw.marketMid * raw.marketMid).toFloat().coerceIn(0f, 1f)
        )
    }

    /**
     * True when the window closing [nowMs] + [tteSeconds] lands on a
     * perpetual funding settlement (00/08/16 UTC, ±7.5 min).
     */
    fun isFundingHour(nowMs: Long, tteSeconds: Double?): Boolean {
        val closeMs = nowMs + (tteSeconds ?: 900.0).coerceAtLeast(0.0).toLong() * 1000L
        return closeMs % (8 * 3_600_000L) < 900_000L
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
