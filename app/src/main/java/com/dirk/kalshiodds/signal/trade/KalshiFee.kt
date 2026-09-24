package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Kalshi-style taker fee: `feeRate × P × (1 − P)` per contract.
 * Pure math — never places an order.
 */
object KalshiFee {

    fun perContract(price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val p = price.coerceIn(0.01, 0.99)
        val r = feeRate.coerceIn(0.0, 0.25)
        return (r * p * (1.0 - p)).coerceAtLeast(0.0)
    }

    fun total(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        perContract(price, feeRate) * contracts.coerceAtLeast(0)

    /** Settlement $1 × contracts minus entry fee. */
    fun netPayout(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0)
        return n * SignalConstants.CONTRACT_SETTLEMENT_USD - total(n, price, feeRate)
    }

    /** Profit if the side wins: net payout − cost. */
    fun netProfit(contracts: Int, vwap: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0)
        val cost = n * vwap
        return netPayout(n, vwap, feeRate) - cost
    }
}
