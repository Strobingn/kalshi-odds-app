package com.dirk.kalshiodds.signal.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * Price-vs-target sanity for Kalshi crypto 15m “price up?” markets.
 *
 * Scoring’s `delta = fair − mid` is an **edge-vs-market** fade. When YES is
 * already expensive because spot is far above the strike, that fade recommends
 * NO / DOWN — the opposite of the contract semantics (YES = UP, NO = DOWN).
 *
 * When spot is clearly past the strike (and moving that way, or far past),
 * the blend is pulled **halfway to the digital fair value** Φ(d2) — which
 * knows time left and volatility. It no longer forces the fair *up* with
 * `max(fair, tanh(gap))`: that fixed curve ignored time and σ and put SOL
 * at 87% with 14 min left where Φ(d2) says ~70%, inventing favorite edges
 * early in the window. The bet side itself is chosen later by net EV at the
 * ask ([com.dirk.kalshiodds.signal.sizing.NetExpectedValue.bestSide]).
 *
 * Python twin: `tools/backtest/pipeline.direction_sanity` (mode "digital").
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
     */
    fun apply(
        spotUsd: Double?,
        strikeUsd: Double?,
        spotReturn: Double?,
        fairPp: Double,
        predictedSide: String,
        yesMeansUp: Boolean = true,
        /** Φ(d2) in pp for YES = UP; null → the fixed tanh curve (no σ available). */
        digitalPp: Double? = null
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

        // P(YES): the digital is P(up), so flip it when YES is the down contract.
        // The tanh fallback is already in YES terms (it takes the signed gap).
        val dirPp = digitalPp?.let { if (yesMeansUp) it else 100.0 - it }
            ?: directionalFairPp(signed, strike)
        val fair = 0.5 * fairPp.coerceIn(0.5, 99.5) + 0.5 * dirPp
        val side = if (fair >= 50.0) SIDE_YES else SIDE_NO

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
            fairPp = fair.coerceIn(0.5, 99.5),
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
     * Smooth 15m P(YES) from signed USD distance. A ~0.2% gap maps near 90%.
     */
    fun directionalFairPp(signedUsd: Double, strike: Double): Double {
        val scale = max(abs(strike) * 0.002, 1.0)
        val p = 0.50 + 0.48 * tanh(signedUsd / scale)
        return (p * 100.0).coerceIn(2.0, 98.0)
    }
}
