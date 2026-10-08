package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.trade.KalshiFee

/**
 * Paper-only exit policy for an already-open AI fill. A model cannot know the
 * exact tick before a decline; it can protect a proven gain after a retrace or
 * exit when its fair probability falls below the bid another trader will pay.
 */
object PaperPositionManager {
    private const val MIN_HOLD_MS = 5_000L
    private const val TRAILING_RETRACE = 0.02
    private const val FAIR_VALUE_EXIT_BUFFER = 0.025
    private const val MIN_NET_GAIN_PER_CONTRACT = 0.005

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
        if (netGain < MIN_NET_GAIN_PER_CONTRACT) return null

        val high = fill.highWaterMarkPrice.takeIf { it > 0.0 } ?: fill.limitPrice
        if (high - bid >= TRAILING_RETRACE) {
            return Decision(bid, "trailing exit: peak ${pct(high)} → bid ${pct(bid)}")
        }
        val fair = fairSideProbability?.takeIf { it.isFinite() && it in 0.0..1.0 }
        if (fair != null && fair + FAIR_VALUE_EXIT_BUFFER <= bid) {
            return Decision(bid, "model fair ${pct(fair)} is below executable bid ${pct(bid)}")
        }
        return null
    }

    private fun pct(value: Double): String = String.format(java.util.Locale.US, "%.0f¢", value * 100.0)
}
