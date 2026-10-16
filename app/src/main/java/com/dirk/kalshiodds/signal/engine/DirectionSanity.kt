package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import kotlin.math.abs
import kotlin.math.max

/**
 * Price-vs-target sanity for Kalshi crypto 15m “price up?” markets.
 *
 * Scoring’s `delta = fair − mid` is an **edge-vs-market** fade. When YES is
 * already expensive because spot is far above the strike, that fade recommends
 * NO / DOWN — the opposite of the contract semantics (YES = UP, NO = DOWN).
 *
 * The lock forces the recommended side to agree with `sign(spot − strike)`.
 * The displayed probability is the time- and volatility-aware digital
 * P(finish above), which can raise **or** lower the incoming fair.
 */
object DirectionSanity {

    const val SIDE_YES = "YES"
    const val SIDE_NO = "NO"

    /** Ignore 2-digit ticker suffixes like `KXBTC15M-26SEP231600-00`. */
    const val MIN_TICKER_STRIKE = 100.0

    /** ~$20 on BTC, ~$0.50 on SOL — enough to call the level “above/below”. */
    const val MIN_RELATIVE_GAP = 0.0002

    const val MIN_ABS_GAP_USD = 0.50

    data class Result(
        val side: String,
        val fairPp: Double,
        val applied: Boolean,
        val spotVsTargetUsd: Double? = null,
        val note: String? = null
    )

    /**
     * @param yesMeansUp false only when the market’s YES is explicitly a
     *        down/below contract. Default crypto 15m is YES=UP.
     * @param digitalFairPp time/vol-aware P(YES)×100 computed **before**
     *        the lock. When present it is used symmetrically.
     */
    fun apply(
        spotUsd: Double?,
        strikeUsd: Double?,
        spotReturn: Double?,
        fairPp: Double,
        predictedSide: String,
        yesMeansUp: Boolean = true,
        digitalFairPp: Double? = null,
        tteSeconds: Double? = null,
        sigmaAnnual: Double? = null
    ): Result {
        val spot = spotUsd?.takeIf { it.isFinite() && it > 0.0 }
        val strike = strikeUsd?.takeIf { it.isFinite() && it > 0.0 }
        if (spot == null || strike == null) {
            return Result(predictedSide, fairPp, applied = false)
        }
        val rawDelta = spot - strike
        val signed = if (yesMeansUp) rawDelta else -rawDelta
        val gap = gapUsd(strike)
        if (abs(signed) < gap) {
            return Result(predictedSide, fairPp, applied = false, spotVsTargetUsd = rawDelta)
        }

        val rising = spotReturn != null && spotReturn > 0.0
        val falling = spotReturn != null && spotReturn < 0.0
        val wantYes = signed > 0.0
        val confirmed = when {
            wantYes && (rising || spotReturn == null || abs(signed) >= 4.0 * gap) -> true
            !wantYes && (falling || spotReturn == null || abs(signed) >= 4.0 * gap) -> true
            else -> false
        }
        if (!confirmed) {
            return Result(predictedSide, fairPp, applied = false, spotVsTargetUsd = rawDelta)
        }

        val side = if (wantYes) SIDE_YES else SIDE_NO
        val dirPp = resolveDirectionalPp(
            signedUsd = signed,
            strike = strike,
            spot = spot,
            digitalFairPp = digitalFairPp,
            tteSeconds = tteSeconds,
            sigmaAnnual = sigmaAnnual
        )
        val fair = if (dirPp != null) {
            // Symmetric: digital fair can lower an over-confident favorite
            // as well as raise a faded one.
            dirPp
        } else {
            // No vol/tte — lock the side across 50 without inventing 87%.
            if (wantYes) max(fairPp, 52.0) else minOf(fairPp, 48.0)
        }.coerceIn(2.0, 98.0)

        val vs = if (rawDelta >= 0) "above" else "below"
        val mom = when {
            rising -> "rising"
            falling -> "falling"
            else -> "vs target"
        }
        val note = String.format(
            java.util.Locale.US,
            "spot %s target %+.0f · %s → %s",
            vs,
            rawDelta,
            mom,
            if (side == SIDE_YES) "UP/YES" else "DOWN/NO"
        )
        return Result(
            side = side,
            fairPp = fair,
            applied = true,
            spotVsTargetUsd = rawDelta,
            note = note
        )
    }

    fun parseStrike(ticker: String, title: String? = null, subtitle: String? = null): Double? {
        val labeled = listOfNotNull(subtitle, title)
        val money = Regex(
            """\$?\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[0-9]{4,}(?:\.[0-9]+)?)"""
        )
        for (text in labeled) {
            val match = money.find(text) ?: continue
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (value > 1.0) return value
        }
        val tail = ticker.substringAfterLast('-', "")
        val n = tail.replace(",", "").toDoubleOrNull() ?: return null
        return n.takeIf { it >= MIN_TICKER_STRIKE }
    }

    /**
     * Default Kalshi crypto 15m is “price up?” / “above strike?”.
     * Invert only when YES is clearly a below/down contract.
     */
    fun yesMeansUp(title: String?, subtitle: String?): Boolean {
        val text = "${title.orEmpty()} ${subtitle.orEmpty()}".lowercase()
        val down = Regex("""\b(below|under|down)\b""").containsMatchIn(text)
        val up = Regex("""\b(above|over|up)\b""").containsMatchIn(text)
        return !(down && !up)
    }

    fun gapUsd(strike: Double): Double =
        max(MIN_ABS_GAP_USD, abs(strike) * MIN_RELATIVE_GAP)

    /**
     * Time- and volatility-aware P(YES)×100. Replaces the old tanh that
     * mapped a 0.2% gap to 87% regardless of minutes left.
     */
    fun directionalFairPp(
        signedUsd: Double,
        strike: Double,
        tteSeconds: Double,
        sigmaAnnual: Double
    ): Double {
        val spot = strike + signedUsd
        val p = DigitalOptionFairValue.pFinishAbove(spot, strike, tteSeconds, sigmaAnnual)
            ?: return if (signedUsd > 0) 52.0 else 48.0
        return (p * 100.0).coerceIn(2.0, 98.0)
    }

    private fun resolveDirectionalPp(
        signedUsd: Double,
        strike: Double,
        spot: Double,
        digitalFairPp: Double?,
        tteSeconds: Double?,
        sigmaAnnual: Double?
    ): Double? {
        digitalFairPp?.takeIf { it.isFinite() }?.let { return it.coerceIn(2.0, 98.0) }
        val tte = tteSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val sig = sigmaAnnual?.takeIf { it.isFinite() && it > 1e-8 } ?: return null
        return DigitalOptionFairValue.pFinishAbove(spot, strike, tte, sig)
            ?.times(100.0)
            ?.coerceIn(2.0, 98.0)
    }
}
