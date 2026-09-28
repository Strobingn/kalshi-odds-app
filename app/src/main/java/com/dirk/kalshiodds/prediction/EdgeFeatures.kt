package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schema-2 features for the market-offset [EdgeModel].
 * Order and math **must** match `tools/backtest/pipeline.edge_features`.
 *
 * Time of day is **UTC** on both sides (the schema-1 trainer used the UTC hour
 * while the phone used New York minutes — a 4–5 h skew).
 */
object EdgeFeatures {
    const val SIZE = 9
    val NAMES = listOf(
        "digital_gap",
        "dist_to_strike_vol",
        "spot_ret_1m",
        "spot_ret_5m",
        "tte_frac",
        "spread",
        "tod_sin",
        "tod_cos",
        "has_spot"
    )

    data class Raw(
        val marketMid: Double,
        val spread: Double? = null,
        val spot: Double? = null,
        val strike: Double? = null,
        val tteSeconds: Double? = null,
        val sigmaAnnual: Double? = null,
        val spotReturn1m: Double? = null,
        val spotReturn5m: Double? = null,
        val nowMs: Long = System.currentTimeMillis()
    )

    fun build(raw: Raw): DoubleArray {
        val tte = (raw.tteSeconds ?: 900.0).coerceIn(0.0, 1800.0)
        var digital: Double? = null
        var dist: Double? = null
        if (raw.spot != null && raw.strike != null && raw.sigmaAnnual != null) {
            digital = DigitalOptionFairValue.pFinishAbove(raw.spot, raw.strike, tte, raw.sigmaAnnual)
            dist = DigitalOptionFairValue.distanceVolUnits(raw.spot, raw.strike, tte, raw.sigmaAnnual)
        }
        val gap = digital?.let {
            (EdgeModel.logitFromProb(it) - EdgeModel.logitFromProb(raw.marketMid)).coerceIn(-4.0, 4.0)
        } ?: 0.0
        val secOfDay = Math.floorMod(raw.nowMs / 1000L, 86_400L).toDouble()
        val ang = 2.0 * PI * secOfDay / 86_400.0
        return doubleArrayOf(
            gap,
            dist?.coerceIn(-8.0, 8.0) ?: 0.0,
            raw.spotReturn1m?.coerceIn(-0.02, 0.02) ?: 0.0,
            raw.spotReturn5m?.coerceIn(-0.05, 0.05) ?: 0.0,
            (tte / 900.0).coerceIn(0.0, 1.0),
            raw.spread?.coerceIn(0.0, 0.2) ?: 0.0,
            sin(ang),
            cos(ang),
            if (digital != null) 1.0 else 0.0
        )
    }
}
