package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Maker-aware entry economics (fee probe 2026-10-07: maker rate 0 on
 * KXBTC15M / KXETH15M / KXSOL15M / KXBTCD; taker 0.07·P·(1−P)).
 *
 * Crossing the spread pays the ask AND the taker fee — the tape study put
 * takers at −1.01¢/contract overall. Resting a post-only bid one cent above
 * the best bid pays neither: the fill price is ~1−2¢ cheaper and the fee is
 * $0. The tradeoff is fill risk (the market may run away) and adverse
 * selection (fills arrive when price moves through you).
 *
 * This object is pure math: it compares the two entry styles and explains
 * the difference. It never places an order.
 */
object MakerEdge {

    data class Compare(
        val side: String,
        /** Taker entry: the ask. */
        val takePrice: Double,
        /** Maker entry: best bid + 1¢ (clipped to below the ask). */
        val restPrice: Double?,
        /** (taker all-in) − (maker all-in) per contract; fee savings + spread. */
        val savePerContract: Double?,
        /** EV at the taker entry (p − ask − fee). */
        val evTake: Double,
        /** EV at the maker entry (p − rest − 0 fee); null when not restable. */
        val evRest: Double?
    ) {
        val restable: Boolean get() = restPrice != null
        /** The maker EV minus the taker EV — how much the rest is worth if filled. */
        val evGain: Double? get() = if (evRest == null) null else evRest - evTake
        fun summary(): String = if (restPrice == null || evRest == null) {
            String.format(Locale.US, "take %.0f¢ · no room to rest", takePrice * 100)
        } else {
            String.format(
                Locale.US,
                "take %.0f¢ (+fee) · rest %.0f¢ no-fee · rest saves %.1f¢/ct",
                takePrice * 100, restPrice * 100, (savePerContract ?: 0.0) * 100
            )
        }
    }

    /**
     * (win chance of [side] at its entry price, side entry prices).
     * [pYes] is the model's P(YES). [yesBid]/[yesAsk] are the live quote.
     */
    fun compare(
        pYes: Double?,
        side: String,
        yesBid: Double?,
        yesAsk: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Compare? {
        val p = pYes?.takeIf { it.isFinite() } ?: return null
        val wantYes = side.equals("YES", ignoreCase = true)
        val winProb = if (wantYes) p else 1.0 - p
        val takePrice = if (wantYes) yesAsk else yesBid?.let { 1.0 - it }
        val tp = takePrice?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return null
        // Resting bid = the side's own best bid + 1¢, strictly under its ask.
        // YES quote is (yesBid, yesAsk); the NO quote mirrors to
        // (1 − yesAsk, 1 − yesBid). NO rest = (1 − yesAsk) + 1¢.
        val restPx = if (wantYes) {
            restPrice(yesBid, yesAsk)
        } else {
            restPrice(1.0 - yesAsk, 1.0 - yesBid)
        }
        val feeTake = KalshiFee.perContract(tp, feeRate)
        val evTake = winProb - tp - feeTake
        val evRest = restPx?.let { winProb - it }
        val save = restPx?.let { (tp + feeTake) - it }
        return Compare(
            side = if (wantYes) "YES" else "NO",
            takePrice = tp,
            restPrice = restPx,
            savePerContract = save,
            evTake = evTake,
            evRest = evRest
        )
    }

    /** Post-only bid price one cent above [bid], strictly under [ask]. */
    private fun restPrice(bid: Double?, ask: Double?): Double? {
        val b = bid?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val a = ask?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val p = ((b + 0.01) * 100.0).roundToLong() / 100.0
        if (p >= a - 1e-9) return null
        if (p < 0.05 - 1e-9 || p > 0.95 + 1e-9) return null
        return p
    }
}
