package com.dirk.kalshiodds.signal.sizing

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs

/**
 * Expected value **net of Kalshi fees and half-spread**.
 *
 * ## Fee model
 *
 * Official taker (https://kalshi.com/docs/kalshi-fee-schedule.pdf):
 *
 *     model_fee = feeRate × C × P × (1 − P)
 *     trade_fee = ceil_6dp(model_fee)
 *     order_fee = ceil_cent(C×P + trade_fee) − C×P
 *
 * Rounding: https://docs.kalshi.com/getting_started/fee_rounding
 * (non-direct member $0.01). Fee is paid on top of the buy, not taken
 * from the $1 settlement. Ranking amortizes the order fee across the
 * contracts a [stakeUsd] ticket (default $5) can buy.
 *
 * This is **not** an order ticket. The app never calls trade endpoints.
 *
 * ## Half-spread
 *
 * Lifting the offer (or hitting the bid) versus mid costs about
 * `½ × (yes_ask − yes_bid)`. We subtract that immediacy cost from EV.
 *
 * ## Per-contract net EV (chosen side)
 *
 *     P_paid = mid + halfSpread     (YES)  or  (1 − mid) + halfSpread (NO)
 *     fee    = orderFee(C, P_paid) / C     C = floor(stake / P_paid)
 *     net    = p_side − P_paid − fee
 *
 * [netEdgePp] = net × 100 so it is comparable to raw fair−mid edge.
 */
object NetExpectedValue {

    data class Result(
        val side: String,
        val contractPrice: Double,
        val feePerContract: Double,
        val halfSpread: Double,
        val grossEv: Double,
        val netEv: Double,
        val netEdgePp: Double,
        val rawEdgePp: Double,
        val feeRate: Double
    )

    fun compute(
        fairYes: Double,
        mid: Double,
        spreadDollars: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        preferSide: String? = null,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Result {
        val pYes = fairYes.coerceIn(0.02, 0.98)
        val m = mid.coerceIn(0.02, 0.98)
        val half = ((spreadDollars ?: 0.0).coerceAtLeast(0.0)) / 2.0
        val rawEdgePp = (pYes - m) * 100.0
        val side = when (preferSide?.uppercase()) {
            "YES" -> "YES"
            "NO" -> "NO"
            else -> if (rawEdgePp >= 0.0) "YES" else "NO"
        }
        val pSide = if (side == "YES") pYes else 1.0 - pYes
        val paid = (if (side == "YES") m else 1.0 - m) + half
        val clippedPaid = paid.coerceIn(com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS, 0.99)
        val fee = feePerContract(clippedPaid, feeRate, stakeUsd)
        val gross = pSide - clippedPaid
        val net = gross - fee
        return Result(
            side = side,
            contractPrice = clippedPaid,
            feePerContract = fee,
            halfSpread = half,
            grossEv = gross,
            netEv = net,
            netEdgePp = net * 100.0,
            rawEdgePp = rawEdgePp,
            feeRate = feeRate
        )
    }

    /**
     * Order-level taker fee amortized over the contracts [stakeUsd] buys at [p].
     */
    fun feePerContract(
        p: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Double = KalshiFee.perContract(p, feeRate, stakeUsd)

    /** True when ranking/alerting on net EV is at least as selective as raw |edge|. */
    fun preferNetForFilter(netEdgePp: Double, rawEdgePp: Double): Boolean =
        abs(netEdgePp) <= abs(rawEdgePp) + 1e-9
}
