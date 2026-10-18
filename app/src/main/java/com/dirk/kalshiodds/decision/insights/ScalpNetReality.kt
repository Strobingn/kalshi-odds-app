package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade

/**
 * Aggregated net reality metrics for a group of scalp trades.
 *
 * @property grossUsd Total gross P&L before fees (sum of netUsd + fees for each trade).
 * @property feesUsd Total fees paid (sum of entryFeeUsd + exitFeeUsd for each trade).
 * @property slippageUsd Estimated total slippage in USD.
 * @property netAfterFeesUsd Net P&L after fees (sum of netUsd, which is already net of fees).
 * @property netAfterSlippageUsd Net P&L after fees and estimated slippage.
 * @property trades Total number of closed trades with finite netUsd.
 * @property partialFills Number of trades that were partially filled.
 */
data class NetReality(
    val grossUsd: Double,
    val feesUsd: Double,
    val slippageUsd: Double,
    val netAfterFeesUsd: Double,
    val netAfterSlippageUsd: Double,
    val trades: Int,
    val partialFills: Int
)

/**
 * Utilities for computing net reality metrics from scalp trades.
 */
object ScalpNetReality {
    /** Default slippage estimate in cents per contract. */
    const val DEFAULT_SLIP_CENTS = 1.0

    /**
     * Computes net reality metrics for a list of scalp trades.
     *
     * @param trades List of scalp trades to analyze.
     * @param slipCentsPerContract Slippage estimate in cents per contract per leg.
     * @return NetReality object with aggregated metrics.
     */
    fun of(trades: List<ScalpTrade>, slipCentsPerContract: Double = DEFAULT_SLIP_CENTS): NetReality {
        val slip = slipCentsPerContract.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val validTrades = trades.filter {
            it.state == ScalpState.CLOSED &&
                    it.netUsd != null &&
                    !it.netUsd.isNaN() &&
                    it.netUsd.isFinite()
        }

        var totalGrossUsd = 0.0
        var totalFeesUsd = 0.0
        var totalNetAfterFeesUsd = 0.0
        var totalSlippageUsd = 0.0
        var partialFillCount = 0

        for (trade in validTrades) {
            val netUsd = trade.netUsd!!
            val fees = trade.entryFeeUsd + trade.exitFeeUsd
            val hasExitLeg = trade.soldContracts > 0

            totalNetAfterFeesUsd += netUsd
            totalFeesUsd += fees
            totalGrossUsd += netUsd + fees

            // Count partial fills
            if (trade.contracts > 0 && trade.entryPrice != null &&
                    trade.soldContracts > 0 && trade.soldContracts < trade.contracts) {
                partialFillCount++
            }

            // Calculate slippage
            val legs = if (hasExitLeg) 2 else 1
            val tradeSlippage = slip / 100.0 * trade.contracts * legs
            totalSlippageUsd += maxOf(tradeSlippage.coerceAtLeast(0.0), 0.0)
        }

        val netAfterSlippage = totalNetAfterFeesUsd - totalSlippageUsd

        return NetReality(
            grossUsd = totalGrossUsd,
            feesUsd = totalFeesUsd,
            slippageUsd = maxOf(totalSlippageUsd, 0.0),
            netAfterFeesUsd = totalNetAfterFeesUsd,
            netAfterSlippageUsd = netAfterSlippage,
            trades = validTrades.size,
            partialFills = partialFillCount
        )
    }

    /**
     * Card-level estimate when only aggregates are known (leaderboard rows): assumes [contractsPerTrade]
     * per round trip and two taker legs. Partial fills are unknown here (0).
     */
    fun estimate(netAfterFeesUsd: Double, feesUsd: Double, trades: Int, contractsPerTrade: Int, slipCentsPerContract: Double = DEFAULT_SLIP_CENTS): NetReality {
        val slip = (slipCentsPerContract.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) / 100.0 * contractsPerTrade.coerceAtLeast(0) * 2 * trades.coerceAtLeast(0)
        return NetReality(netAfterFeesUsd + feesUsd, feesUsd, slip, netAfterFeesUsd, netAfterFeesUsd - slip, trades.coerceAtLeast(0), 0)
    }

    /**
     * Formats a USD amount as a signed currency string.
     * Positive values get "+$", negative values get "\u2212$" (unicode minus).
     *
     * @param usd The amount in USD to format.
     * @return Formatted string like "+$1.23" or "\u2212$0.40".
     */
    fun fmt(usd: Double): String {
        val cents = String.format(java.util.Locale.US, "%.2f", Math.abs(usd))
        val sign = if (usd < 0 && cents != "0.00") "\u2212" else "+"
        return "$sign\$$cents"
    }
}
