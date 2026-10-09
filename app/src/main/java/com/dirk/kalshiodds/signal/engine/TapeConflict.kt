package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.KalshiPrice
import kotlin.math.abs

/**
 * Primary side is **market + spot vs strike + fair value**, never a
 * sparkline slope. A single bad tick cannot flip direction.
 *
 * The "AI vs tape" banner only fires after [SUSTAINED_STREAK] consecutive
 * disagreements between the model and that primary.
 */
object TapeConflict {

    enum class Trend { UP, DOWN, FLAT }

    /** ~4 bp over 1m is enough to call a 15m ladder “ticking up/down”. */
    const val CLEAR_1M = 0.0004

    /** ~8 bp over 5m — the Binance/Coinbase window we already cache. */
    const val CLEAR_5M = 0.0008

    /** Banner only after this many consecutive model-vs-primary disagreements. */
    const val SUSTAINED_STREAK = 3

    data class Result(
        val trend: Trend,
        val modelSide: String,
        val primarySide: String,
        val conflict: Boolean,
        val banner: String?,
        val tapeReturn: Double?,
        val disagreementStreak: Int = 0
    )

    fun trend(spotReturn1m: Double?, spotReturn5m: Double?): Trend {
        val r5 = spotReturn5m?.takeIf { it.isFinite() }
        val r1 = spotReturn1m?.takeIf { it.isFinite() }
        val primary = r5 ?: r1 ?: return Trend.FLAT
        val clear = if (r5 != null) CLEAR_5M else CLEAR_1M
        if (abs(primary) < clear) return Trend.FLAT
        if (r5 != null && r1 != null && r5 * r1 < 0.0 && abs(r1) >= CLEAR_1M) {
            return Trend.FLAT
        }
        return if (primary > 0.0) Trend.UP else Trend.DOWN
    }

    /**
     * Market + spot vs strike (+ fair). Never a sparkline. Keep
     * [previousPrimary] unless the new evidence is clean and decisive.
     */
    fun primaryFromMarket(
        yesAsk: Double?,
        noAsk: Double?,
        spotUsd: Double?,
        strikeUsd: Double?,
        fairYes: Double?,
        previousPrimary: String?,
        yesBid: Double? = null,
        noBid: Double? = null
    ): String {
        val (yb, ya) = QuoteSanity.usablePair(yesBid, yesAsk)
        val (_, na) = QuoteSanity.usablePair(noBid, noAsk)
        val impliedYesBid = na?.let { KalshiPrice.usable(1.0 - it) } ?: yb
        val mid = QuoteSanity.robustMid(impliedYesBid, ya)
            ?: QuoteSanity.robustMid(yb, ya)
            ?: na?.let { KalshiPrice.usable(1.0 - it) }

        val spot = spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        val strike = strikeUsd?.takeIf { it.isFinite() && it > 0.0 }
        val fromSpot = if (spot != null && strike != null) {
            val gap = spot - strike
            val rel = abs(gap) / strike
            when {
                rel < DirectionSanity.MIN_RELATIVE_GAP && abs(gap) < DirectionSanity.MIN_ABS_GAP_USD -> null
                gap > 0.0 -> DirectionSanity.SIDE_YES
                gap < 0.0 -> DirectionSanity.SIDE_NO
                else -> null
            }
        } else {
            null
        }
        val fromMarket = mid?.let {
            when {
                it > 0.52 -> DirectionSanity.SIDE_YES
                it < 0.48 -> DirectionSanity.SIDE_NO
                else -> null
            }
        }
        val fromFair = fairYes?.takeIf { it.isFinite() }?.let {
            when {
                it > 0.52 -> DirectionSanity.SIDE_YES
                it < 0.48 -> DirectionSanity.SIDE_NO
                else -> null
            }
        }
        val decided = fromSpot ?: fromMarket ?: fromFair
        val prev = previousPrimary?.uppercase()?.takeIf {
            it == DirectionSanity.SIDE_YES || it == DirectionSanity.SIDE_NO
        }
        if (decided == null) return prev ?: DirectionSanity.SIDE_YES
        if (prev == null || prev == decided) return decided
        // A single quote print cannot flip the previous primary. Spot vs
        // strike is the underlying, not a book tick; market+fair agreement
        // is two independent signals.
        if (fromSpot != null) return fromSpot
        if (fromMarket != null && fromFair != null && fromMarket == fromFair) return fromMarket
        return prev
    }

    /**
     * @param modelSide YES/NO after DirectionSanity (keep that lock).
     */
    fun evaluate(
        spotReturn1m: Double?,
        spotReturn5m: Double?,
        modelSide: String,
        yesAsk: Double? = null,
        noAsk: Double? = null,
        spotUsd: Double? = null,
        strikeUsd: Double? = null,
        fairYes: Double? = null,
        previousPrimary: String? = null,
        priorStreak: Int = 0,
        yesBid: Double? = null,
        noBid: Double? = null,
        modelYesPercent: Double? = null
    ): Result {
        val stored = if (modelSide.equals("NO", ignoreCase = true)) DirectionSanity.SIDE_NO
        else DirectionSanity.SIDE_YES
        val side = when {
            modelYesPercent != null && modelYesPercent > 50.0 -> DirectionSanity.SIDE_YES
            modelYesPercent != null && modelYesPercent < 50.0 -> DirectionSanity.SIDE_NO
            else -> stored
        }
        val t = trend(spotReturn1m, spotReturn5m)
        val tapeRet = (spotReturn5m ?: spotReturn1m)?.takeIf { it.isFinite() }
        val primary = primaryFromMarket(
            yesAsk = yesAsk,
            noAsk = noAsk,
            spotUsd = spotUsd,
            strikeUsd = strikeUsd,
            fairYes = fairYes,
            previousPrimary = previousPrimary ?: side,
            yesBid = yesBid,
            noBid = noBid
        )
        val disagree = side != primary
        val streak = if (disagree) priorStreak + 1 else 0
        val conflict = disagree && streak >= SUSTAINED_STREAK
        val banner = if (conflict) {
            val ai = if (side == DirectionSanity.SIDE_YES) "UP" else "DOWN"
            val mkt = if (primary == DirectionSanity.SIDE_YES) "UP" else "DOWN"
            "AI says $ai, market + spot say $mkt"
        } else {
            null
        }
        return Result(
            trend = t,
            modelSide = side,
            primarySide = primary,
            conflict = conflict,
            banner = banner,
            tapeReturn = tapeRet,
            disagreementStreak = streak
        )
    }
}
