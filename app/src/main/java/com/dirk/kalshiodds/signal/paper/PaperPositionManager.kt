package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.trade.KalshiFee

/**
 * Paper-only scalping exit policy for an already-open AI fill. A model cannot know the
 * exact tick before a decline; it can protect a proven gain after a retrace or
 * exit when its fair probability falls below the bid another trader will pay.
 */
object PaperPositionManager {
    // Aggressive paper scalp: no entry cooldown, protect a half-cent pullback,
    // and cut a net one-cent loss. These are exit controls only; SCALP entries
    // remain unrestricted and never route a real Kalshi order.
    private const val MIN_HOLD_MS = 0L
    private const val TRAILING_RETRACE = 0.005
    private const val FAIR_VALUE_EXIT_BUFFER = 0.005
    private const val MAX_NET_LOSS_PER_CONTRACT = 0.01

    data class Decision(val bid: Double, val reason: String)

    fun decide(
        fill: PaperFill,
        executableBid: Double?,
        fairSideProbability: Double?,
        nowMs: Long
    ): Decision? {
        if (fill.settled || !fill.source.startsWith("AI ") || nowMs - fill.createdAtMs < MIN_HOLD_MS) return null
        val bid = KalshiPrice.usable(executableBid) ?: return null
        val feePerContract = KalshiFee.total(fill.contracts, bid) / fill.contracts.toDouble()
        val entryPerContract = (fill.stakeUsd + fill.feeUsd) / fill.contracts.toDouble()
        val netGain = bid - feePerContract - entryPerContract
        val high = fill.highWaterMarkPrice.takeIf { it > 0.0 } ?: fill.limitPrice
        if (netGain <= -MAX_NET_LOSS_PER_CONTRACT) {
            return Decision(bid, "loss cut: net ${pct(netGain)} per contract")
        }
        val fair = fairSideProbability?.takeIf { it.isFinite() && it in 0.0..1.0 }
        if (fair != null && fair + FAIR_VALUE_EXIT_BUFFER <= bid) {
            return Decision(bid, "model fair ${pct(fair)} is below executable bid ${pct(bid)}")
        }
        if (netGain > 0.0 && high - bid >= TRAILING_RETRACE) {
            return Decision(bid, "trailing exit: peak ${pct(high)} → bid ${pct(bid)}")
        }
        return null
    }

    private fun pct(value: Double): String = String.format(java.util.Locale.US, "%.0f¢", value * 100.0)
}
