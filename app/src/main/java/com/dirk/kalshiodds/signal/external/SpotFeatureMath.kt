package com.dirk.kalshiodds.signal.external

import kotlin.math.tanh

/**
 * Maps public spot / funding / realized-vol into a small YES-fair nudge.
 * Fail-soft: returns null when nothing usable is present.
 */
object SpotFeatureMath {

    fun adjustPp(midPp: Double, features: AssetSpotFeatures?): Double? {
        return adjustPp(
            midPp = midPp,
            spotReturn1m = features?.spotReturn1m,
            spotReturn5m = features?.spotReturn5m,
            fundingRate = features?.fundingRate,
            realizedVol15m = features?.realizedVol15m
        )
    }

    /**
     * Allows the caller to provide returns from Kalshi's CF settlement-index
     * tape while retaining external funding/volatility only as context.
     */
    fun adjustPp(
        midPp: Double,
        spotReturn1m: Double?,
        spotReturn5m: Double?,
        fundingRate: Double? = null,
        realizedVol15m: Double? = null
    ): Double? {
        val ret = spotReturn5m ?: spotReturn1m
        val funding = fundingRate
        val vol = realizedVol15m
        if (ret == null && funding == null && vol == null) return null
        // 15m up/down contracts: a short positive spot print nudges YES.
        // Funding > 0 (longs pay) is a mild YES fade; vol is a small shrink.
        val retTerm = ret?.let { 6.0 * tanh(it / 0.003) } ?: 0.0
        val fundTerm = funding?.let { 2.0 * tanh(it / 0.0004) } ?: 0.0
        val volTerm = vol?.let { -1.2 * tanh(it / 0.008) } ?: 0.0
        return (midPp + retTerm + fundTerm + volTerm).coerceIn(2.0, 98.0)
    }

    fun label(features: AssetSpotFeatures?): String? {
        if (features == null) return null
        val parts = mutableListOf<String>()
        val ret = features.spotReturn5m ?: features.spotReturn1m
        if (ret != null) {
            parts += String.format(java.util.Locale.US, "spot %+.2f%%", ret * 100.0)
        }
        features.fundingRate?.let {
            parts += String.format(java.util.Locale.US, "fund %.3f%%", it * 100.0)
        }
        features.realizedVol15m?.let {
            parts += String.format(java.util.Locale.US, "rvol %.2f%%", it * 100.0)
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(" · ") + " (${features.source})"
    }

    fun settlementLabel(spotReturn1m: Double?, spotReturn5m: Double?): String? {
        val ret = spotReturn5m ?: spotReturn1m ?: return null
        return String.format(
            java.util.Locale.US,
            "settlement index %+.2f%% (Kalshi CF)",
            ret * 100.0
        )
    }
}
