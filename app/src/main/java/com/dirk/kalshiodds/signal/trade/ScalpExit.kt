package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Buy a cheap contract, sell it when the bid is higher, and do not wait
 * for the 15-minute window to settle. Pure math — never places an order.
 *
 * A live sell is still an Approve ticket. The paper book is the only
 * path that closes itself.
 */
object ScalpExit {
    /** AI opens only when this side's ask is at or under 20¢. */
    const val MAX_ENTRY = 0.20

    /** Bid has to clear the fill by at least 2¢ before a sale is even considered. */
    const val MIN_RISE = 0.02

    const val SELL_NOTE = "Price is up. Sell now at the bid — do not hold to settlement."

    fun isLowPrice(ask: Double?): Boolean {
        val px = ask ?: return false
        if (!px.isFinite() || px <= 0.0) return false
        return px <= MAX_ENTRY + 1e-9
    }

    /**
     * True when selling [contracts] at [bid] returns more cash than the
     * entry stake plus both taker fees. The profit is the sale, not the $1 settlement.
     */
    fun shouldSell(
        entryPrice: Double,
        bid: Double?,
        contracts: Int,
        entryFeeUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Boolean {
        val b = bid ?: return false
        if (contracts < 1) return false
        if (!entryPrice.isFinite() || !b.isFinite() || entryPrice <= 0.0) return false
        if (b + 1e-9 < entryPrice + MIN_RISE) return false
        val sellFee = KalshiFee.total(contracts, b, feeRate)
        val proceeds = contracts * b - sellFee
        val cost = contracts * entryPrice + entryFeeUsd.coerceAtLeast(0.0)
        return proceeds > cost + 0.009
    }
}
