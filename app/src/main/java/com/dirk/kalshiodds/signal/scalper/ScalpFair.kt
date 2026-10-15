package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import kotlin.math.sqrt

/**
 * The YES fair price the late-favourite strategies trade on: the chance the
 * settlement average finishes at or above the strike, from the live spot
 * (the CF Benchmarks index when fresh, else Coinbase), the trailing 1-minute
 * volatility and, inside the final minute, the part of the average already
 * fixed ([DigitalOptionFairValue.pSettleAtLeast]). Pure.
 */
object ScalpFair {

    /**
     * Std of 1-minute log returns used while the spot stream's own estimate
     * warms up, BTC only: Kashi's fallback (2.9e-5 per √s, `Scalp.kt`) in
     * one-minute units.
     */
    const val BTC_FALLBACK_STD_1M = 2.9e-5 * 7.745966692414834

    /**
     * Null when an input is missing or unusable. [std1m] is the std of
     * 1-minute log returns; [observedMeanLog] the mean ln price already
     * fixed in the final minute (null before it).
     */
    fun fairYes(
        spot: Double?,
        strike: Double?,
        tteSeconds: Double,
        std1m: Double?,
        coin: String,
        observedMeanLog: Double? = null
    ): Double? {
        val s = spot?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val k = strike?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (!tteSeconds.isFinite() || tteSeconds <= 0.0) return null
        val sd = std1m?.takeIf { it.isFinite() && it > 0.0 }
            ?: (if (coin.equals("BTC", ignoreCase = true)) BTC_FALLBACK_STD_1M else return null)
        val annual = sd * sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR / 60.0)
        return DigitalOptionFairValue.pSettleAtLeast(
            spot = s,
            strike = k,
            tteSeconds = tteSeconds,
            sigmaAnnual = annual,
            observedMeanLog = observedMeanLog,
            indexNoise = DigitalOptionFairValue.indexNoiseLog(coin)
        )
    }
}
