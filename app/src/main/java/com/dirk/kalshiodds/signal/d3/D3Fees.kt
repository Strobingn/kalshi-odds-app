package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.signal.trade.KalshiFee

/**
 * Maker fee for D3. Read from the KXBTCD series payload
 * (`fee_type`, `fee_multiplier`) — never a hardcoded "$0 for KXBTCD".
 *
 * Kalshi's published maker formula is `M × 0.0175 × C × P × (1 − P)`.
 * `M` defaults to **0** unless the series is in the Maker Fees table.
 * GET /series/KXBTCD returns `fee_type=quadratic`, `fee_multiplier=1`
 * and no maker multiplier, so the looked-up maker fee is $0.
 */
object D3Fees {

    data class Schedule(
        val feeType: String,
        val takerMultiplier: Double,
        val makerMultiplier: Double
    ) {
        val makerFeeRate: Double
            get() = if (makerMultiplier <= 0.0 || feeType.equals("none", true)) {
                0.0
            } else {
                KalshiFee.MAKER_COEFFICIENT * makerMultiplier
            }
    }

    fun fromSeries(
        feeType: String?,
        feeMultiplier: Double?,
        makerMultiplier: Double? = null
    ): Schedule {
        val type = feeType?.trim()?.lowercase().orEmpty().ifBlank { "unknown" }
        val taker = feeMultiplier?.takeIf { it.isFinite() } ?: 1.0
        // Maker M is omitted on KXBTCD → default 0 (not in Maker Fees table).
        val maker = makerMultiplier?.takeIf { it.isFinite() } ?: 0.0
        return Schedule(feeType = type, takerMultiplier = taker, makerMultiplier = maker)
    }

    fun makerFeeUsd(contracts: Int, price: Double, schedule: Schedule): Double {
        val rate = schedule.makerFeeRate
        if (rate <= 0.0) return 0.0
        return KalshiFee.total(contracts, price, rate)
    }

    fun allInUsd(contracts: Int, price: Double, schedule: Schedule): Double {
        val n = contracts.coerceAtLeast(0)
        if (n <= 0) return 0.0
        val position = n * KalshiFee.clipPrice(price)
        return position + makerFeeUsd(n, price, schedule)
    }
}
