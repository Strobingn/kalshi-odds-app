package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Official Kalshi taker fee (CFTC fee schedule / docs.kalshi.com):
 * `round_up_to_cent(feeRate × C × P × (1 − P))` with default rate 0.07.
 * Pure math — never places an order.
 */
object KalshiFee {

    fun raw(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0).toDouble()
        val p = price.coerceIn(0.01, 0.99)
        val r = feeRate.coerceIn(0.0, 0.25)
        return (r * n * p * (1.0 - p)).coerceAtLeast(0.0)
    }

    /** Round up to the next cent — `ceil(raw × 100) / 100`. */
    fun roundUpToCent(dollars: Double): Double {
        if (!dollars.isFinite() || dollars <= 0.0) return 0.0
        return kotlin.math.ceil(dollars * 100.0 - 1e-12) / 100.0
    }

    fun perContract(price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        total(1, price, feeRate)

    fun total(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        roundUpToCent(raw(contracts, price, feeRate))

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
