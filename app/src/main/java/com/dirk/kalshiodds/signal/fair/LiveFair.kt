package com.dirk.kalshiodds.signal.fair

/**
 * Chart fair for one Kalshi tick. Same digital formula [DirectionSanity] uses,
 * recomputed from the spot known at that tick. Calibration curves are not in here.
 */
object LiveFair {
    fun yesPercent(spot: Double, strike: Double, tteSeconds: Double, sigmaAnnual: Double): Double? {
        val p = DigitalOptionFairValue.pFinishAbove(spot, strike, tteSeconds, sigmaAnnual) ?: return null
        return (p * 100.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    }
}
