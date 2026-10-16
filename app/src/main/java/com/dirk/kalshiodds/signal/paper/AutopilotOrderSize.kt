package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * One fee-aware Kelly clip shared by paper and shadow (0.3.40: no live Autopilot).
 * There is no dollar cap. Depth and the paper bankroll are the only limits.
 * A large clip is a warning in the UI, not a block.
 */
object AutopilotOrderSize {
    /** Soft UI hint only. Never used to refuse an order. */
    const val LARGE_CLIP_USD = 50.0

    fun quote(
        decision: PaperAutopilot.Decision,
        fill: PaperFill?,
        depth: Int?,
        kellyFraction: Double,
        feeRate: Double,
        cashUsd: Double
    ): PaperKellySizer.Result {
        if (fill != null && fill.contracts > 0) {
            val fee = (fill.stakeUsd - fill.contracts * fill.limitPrice).coerceAtLeast(0.0)
            return PaperKellySizer.Result(
                skip = false,
                kellyF = fill.kellyF ?: decision.kellyF,
                kellyFraction = fill.kellyFraction ?: kellyFraction,
                contracts = fill.contracts,
                stakeUsd = fill.stakeUsd,
                feeUsd = fee,
                allInUsd = fill.stakeUsd,
                ask = fill.limitPrice
            )
        }
        val side = decision.side
            ?: return PaperKellySizer.Result(skip = true, reason = "No side")
        val sized = PaperKellySizer.size(
            winProb = side.winProb,
            ask = side.ask,
            bankrollUsd = decision.freeBankrollUsd,
            kellyFraction = kellyFraction,
            feeRate = feeRate,
            depthContracts = depth,
            maxStakeUsd = decision.maxStakeUsd
        )
        if (!sized.ok) return sized
        val (qty, _) = PaperBuy.capContracts(
            want = sized.contracts,
            cashUsd = cashUsd,
            price = sized.ask,
            feeRate = feeRate
        )
        if (qty < 1) {
            return sized.copy(skip = true, contracts = 0, reason = "Paper cash cannot cover the Kelly clip")
        }
        if (qty == sized.contracts) return sized
        val allIn = com.dirk.kalshiodds.signal.trade.KalshiFee.totalCost(qty, sized.ask, feeRate)
        val fee = com.dirk.kalshiodds.signal.trade.KalshiFee.total(qty, sized.ask, feeRate)
        return sized.copy(contracts = qty, stakeUsd = allIn, feeUsd = fee, allInUsd = allIn)
    }

    fun isLarge(allInUsd: Double): Boolean = allInUsd > LARGE_CLIP_USD + 1e-6

    fun largeClipWarning(allInUsd: Double): String? {
        if (!isLarge(allInUsd)) return null
        return String.format(
            java.util.Locale.US,
            "Large clip $%.2f — not blocked. Size is fee-aware Kelly on the paper bankroll.",
            allInUsd
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun manualApproveStaysCapped(): Double = SignalConstants.LIVE_ALL_IN_CAP_USD
}
