package com.dirk.kalshiodds.signal.engine

import kotlin.math.abs

/**
 * Cross-check a directional suggestion against the live Bitcoin (or
 * ETH/SOL) tape — short-horizon spot returns already fetched by
 * [com.dirk.kalshiodds.signal.external.ExternalMarketClient].
 *
 * Does **not** replace [DirectionSanity] (spot − strike lock). This only
 * decides when the model/lock side conflicts with a clear chart trend so
 * the UI can show both and align the primary hero with the tape.
 */
object TapeConflict {

    enum class Trend { UP, DOWN, FLAT }

    /** ~4 bp over 1m is enough to call a 15m ladder “ticking up/down”. */
    const val CLEAR_1M = 0.0004

    /** ~8 bp over 5m — the Binance/Coinbase window we already cache. */
    const val CLEAR_5M = 0.0008

    data class Result(
        val trend: Trend,
        val modelSide: String,
        val primarySide: String,
        val conflict: Boolean,
        val banner: String?,
        val tapeReturn: Double?
    )

    fun trend(spotReturn1m: Double?, spotReturn5m: Double?): Trend {
        val r5 = spotReturn5m?.takeIf { it.isFinite() }
        val r1 = spotReturn1m?.takeIf { it.isFinite() }
        val primary = r5 ?: r1 ?: return Trend.FLAT
        val clear = if (r5 != null) CLEAR_5M else CLEAR_1M
        if (abs(primary) < clear) return Trend.FLAT
        if (r5 != null && r1 != null && r5 * r1 < 0.0 && abs(r1) >= CLEAR_1M) {
            // 1m already reversed a 5m move — treat as flat, not a silent fade.
            return Trend.FLAT
        }
        return if (primary > 0.0) Trend.UP else Trend.DOWN
    }

    /**
     * @param modelSide YES/NO after DirectionSanity (keep that lock).
     */
    fun evaluate(
        spotReturn1m: Double?,
        spotReturn5m: Double?,
        modelSide: String
    ): Result {
        val side = if (modelSide.equals("NO", ignoreCase = true)) DirectionSanity.SIDE_NO
        else DirectionSanity.SIDE_YES
        val t = trend(spotReturn1m, spotReturn5m)
        val tapeRet = (spotReturn5m ?: spotReturn1m)?.takeIf { it.isFinite() }
        if (t == Trend.FLAT) {
            return Result(
                trend = t,
                modelSide = side,
                primarySide = side,
                conflict = false,
                banner = null,
                tapeReturn = tapeRet
            )
        }
        val tapeYes = t == Trend.UP
        val modelYes = side == DirectionSanity.SIDE_YES
        val conflict = tapeYes != modelYes
        val primary = when {
            conflict && tapeYes -> DirectionSanity.SIDE_YES
            conflict && !tapeYes -> DirectionSanity.SIDE_NO
            else -> side
        }
        val banner = if (conflict) {
            val ai = if (modelYes) "UP" else "DOWN"
            val tape = if (tapeYes) "UP" else "DOWN"
            "AI says $ai, but live chart shows $tape"
        } else {
            null
        }
        return Result(
            trend = t,
            modelSide = side,
            primarySide = primary,
            conflict = conflict,
            banner = banner,
            tapeReturn = tapeRet
        )
    }
}
