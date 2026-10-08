package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor

/**
 * Buy under 50¢, hold while the bid is rising, and sell when it comes
 * off the high. Do not wait for the 15-minute window to settle.
 * A cheaper ask gets a larger share of the paper cash, so the same
 * rise pays more. Pure math — never places an order.
 *
 * A live sell is still an Approve ticket. The paper book is the only
 * path that closes itself.
 */
object ScalpExit {
    /** Buys at this ask or higher are a coin-flip or a favorite. Skip them. */
    const val MAX_ENTRY = 0.50

    /** The high has to clear the fill by at least this much before a rollover can sell. */
    const val MIN_RISE = 0.02

    /** Bid this far under the high means the move is rolling over. */
    const val GIVEBACK = 0.03

    /** Smallest share of paper cash, used near 49¢. */
    const val MIN_CASH_FRACTION = 0.08

    /** Extra share added as the ask falls toward 0. At 4¢ the buy is about 37% of cash. */
    const val EXTRA_CASH_FRACTION = 0.32

    const val SELL_NOTE = "Bid is coming off the high. Sell now — do not wait for the drop or for settlement."

    fun isLowPrice(ask: Double?): Boolean {
        val px = ask ?: return false
        if (!px.isFinite() || px <= 0.0) return false
        return px < MAX_ENTRY - 1e-9
    }

    /**
     * Dollar size before contract rounding. Lower [price] → larger fraction
     * of [cashUsd]. Never the whole book.
     */
    fun stakeUsd(price: Double, cashUsd: Double): Double {
        if (!isLowPrice(price) || !cashUsd.isFinite() || cashUsd <= 0.0) return 0.0
        val cheapness = ((MAX_ENTRY - price) / MAX_ENTRY).coerceIn(0.0, 1.0)
        val fraction = MIN_CASH_FRACTION + EXTRA_CASH_FRACTION * cheapness
        return cashUsd * fraction
    }

    fun contractsFor(price: Double, cashUsd: Double): Int {
        val stake = stakeUsd(price, cashUsd)
        if (stake <= 0.0 || price <= 0.0) return 0
        return floor(stake / price + 1e-9).toInt().coerceAtLeast(0)
    }

    /**
     * True when [bid] has fallen [GIVEBACK] from [peak] after a real rise,
     * and selling here still clears the entry stake plus both taker fees.
     * A bid that is still the high is a hold.
     */
    fun shouldSell(
        entryPrice: Double,
        bid: Double?,
        peak: Double?,
        contracts: Int,
        entryFeeUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Boolean {
        val b = bid ?: return false
        if (contracts < 1) return false
        if (!entryPrice.isFinite() || !b.isFinite() || entryPrice <= 0.0) return false
        val high = peak?.takeIf { it.isFinite() && it > 0.0 } ?: entryPrice
        if (b + 1e-9 >= high) return false
        if (high - b < GIVEBACK - 1e-9) return false
        if (high + 1e-9 < entryPrice + MIN_RISE) return false
        val sellFee = KalshiFee.total(contracts, b, feeRate)
        val proceeds = contracts * b - sellFee
        val cost = contracts * entryPrice + entryFeeUsd.coerceAtLeast(0.0)
        return proceeds > cost + 0.009
    }
}
