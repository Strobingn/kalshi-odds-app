package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import java.util.Locale

/**
 * Pick the side by **expected value at the ask**, not by which side is more
 * likely to win (docs/ml-review-2026-09-27.md #2). The market is already
 * calibrated, so buying the favorite loses the spread and the fee; only a
 * fair that beats the price we actually pay is an edge:
 *
 *     ev_yes = p − yes_ask − fee(yes_ask)
 *     ev_no  = (1 − p) − no_ask − fee(no_ask)
 *     side   = the larger, if it is above [margin]; otherwise skip
 *
 * `fee` is [KalshiFee.perContract] (the rounded order fee amortized over a
 * [stakeUsd] ticket). A missing ask is derived from the opposite bid
 * (NO ask = 1 − YES bid), the same way [TicketBuilder.bestAsk] does.
 *
 * Mirrored by `ev_side` in `tools/backtest/pipeline.py` and
 * `ml/train_edge.py`. Pure math — never places an order.
 */
object EvSide {

    /**
     * Minimum EV per contract after the fee. Same 3¢ as
     * [TicketBuilder.modelBeatsImplied], so a side picked here also passes
     * the ticket's model-edge gate.
     */
    const val DEFAULT_MARGIN = 0.03

    data class Result(
        /** "YES" / "NO", or null = skip (neither side clears [margin]). */
        val side: String?,
        val evYes: Double?,
        val evNo: Double?,
        val yesAsk: Double?,
        val noAsk: Double?,
        val pYes: Double,
        val margin: Double,
        val reason: String
    ) {
        val skip: Boolean get() = side == null
    }

    /**
     * Null when there is nothing to judge: no finite [pYes], or no usable
     * ask on either side. Callers then fall back to their old side order.
     */
    fun decide(
        pYes: Double?,
        yesAsk: Double?,
        noAsk: Double?,
        yesBid: Double? = null,
        noBid: Double? = null,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        margin: Double = DEFAULT_MARGIN,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Result? {
        val p = pYes?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: return null
        val ya = KalshiPrice.usable(yesAsk) ?: KalshiPrice.impliedAskFromOppositeBid(noBid)
        val na = KalshiPrice.usable(noAsk) ?: KalshiPrice.impliedAskFromOppositeBid(yesBid)
        if (ya == null && na == null) return null
        val evYes = ya?.let { p - it - KalshiFee.perContract(it, feeRate, stakeUsd) }
        val evNo = na?.let { (1.0 - p) - it - KalshiFee.perContract(it, feeRate, stakeUsd) }
        val best: Pair<String, Double>? = when {
            evYes != null && (evNo == null || evYes >= evNo) -> "YES" to evYes
            evNo != null -> "NO" to evNo
            else -> null
        }
        val chosen = best?.takeIf { it.second > margin }
        val side = chosen?.first
        val reason = if (chosen != null) {
            val ask = if (chosen.first == "YES") ya else na
            String.format(
                Locale.US,
                "%s beats its %.0f¢ ask + fee by %.1f¢ (fair %.0f%%)",
                label(chosen.first),
                (ask ?: 0.0) * 100.0,
                chosen.second * 100.0,
                p * 100.0
            )
        } else {
            String.format(
                Locale.US,
                "Neither side beats its ask + fee by %.0f¢ (UP %s, DOWN %s)",
                margin * 100.0,
                cents(evYes),
                cents(evNo)
            )
        }
        return Result(
            side = side,
            evYes = evYes,
            evNo = evNo,
            yesAsk = ya,
            noAsk = na,
            pYes = p,
            margin = margin,
            reason = reason
        )
    }

    private fun label(side: String): String = if (side == "NO") "DOWN" else "UP"

    private fun cents(ev: Double?): String =
        ev?.let { String.format(Locale.US, "%+.1f¢", it * 100.0) } ?: "—"
}
