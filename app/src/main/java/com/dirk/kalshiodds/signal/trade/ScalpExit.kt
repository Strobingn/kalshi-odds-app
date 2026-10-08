package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor

/**
 * Buy under 50¢ during the first 7 minutes, while the contract is still
 * moving. A cheaper ask gets a larger share of the paper cash. Hold the
 * rise. Sell when the bid comes off the high, or when those first 7
 * minutes are over and the price has flattened. Do not wait for settlement.
 *
 * A live sell is still an Approve ticket. The paper book is the only
 * path that closes itself.
 */
object ScalpExit {
    /** Buys at this ask or higher are a coin-flip or a favorite. Skip them. */
    const val MAX_ENTRY = 0.50

    /** The high has to clear the fill by at least this much before a sale. */
    const val MIN_RISE = 0.02

    /** Bid this far under the high means the move is rolling over. */
    const val GIVEBACK = 0.03

    /**
     * The up-move is usually done by 5–7 minutes. After this the bid
     * flattens, so a new buy is late and an open winner should be sold.
     */
    const val MOVE_WINDOW_MS = 7L * 60L * 1000L

    /** Smallest share of paper cash, used near 49¢. */
    const val MIN_CASH_FRACTION = 0.08

    /** Extra share added as the ask falls toward 0. At 4¢ the buy is about 37% of cash. */
    const val EXTRA_CASH_FRACTION = 0.32

    const val SELL_NOTE = "Early move is over. Sell now — do not sit through the flat part of the window."

    fun isLowPrice(ask: Double?): Boolean {
        val px = ask ?: return false
        if (!px.isFinite() || px <= 0.0) return false
        return px < MAX_ENTRY - 1e-9
    }

    /** Milliseconds since the 15-minute window opened. Null if the clock is missing. */
    fun elapsedMs(nowMs: Long, closeTimeEpochMs: Long?, openTimeEpochMs: Long? = null): Long? {
        val open = openTimeEpochMs ?: closeTimeEpochMs?.minus(MarketLifecycle.WINDOW_MS) ?: return null
        return nowMs - open
    }

    /** True only in the first 7 minutes, when the contract is still moving. */
    fun inMoveWindow(nowMs: Long, closeTimeEpochMs: Long?, openTimeEpochMs: Long? = null): Boolean {
        val elapsed = elapsedMs(nowMs, closeTimeEpochMs, openTimeEpochMs) ?: return false
        return elapsed >= 0L && elapsed < MOVE_WINDOW_MS
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
     * Sell a winner when the bid has fallen [GIVEBACK] from [peak], or when
     * [elapsedMs] is past the first 7 minutes and the sale still clears fees.
     * A new high inside that window is a hold. A loser is not dumped.
     */
    fun shouldSell(
        entryPrice: Double,
        bid: Double?,
        peak: Double?,
        contracts: Int,
        entryFeeUsd: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        elapsedMs: Long? = null
    ): Boolean {
        val b = bid ?: return false
        if (contracts < 1) return false
        if (!entryPrice.isFinite() || !b.isFinite() || entryPrice <= 0.0) return false
        val high = peak?.takeIf { it.isFinite() && it > 0.0 } ?: entryPrice
        if (high + 1e-9 < entryPrice + MIN_RISE) return false
        val sellFee = KalshiFee.total(contracts, b, feeRate)
        val proceeds = contracts * b - sellFee
        val cost = contracts * entryPrice + entryFeeUsd.coerceAtLeast(0.0)
        if (proceeds <= cost + 0.009) return false
        val offHigh = b + 1e-9 < high && high - b >= GIVEBACK - 1e-9
        if (offHigh) return true
        return elapsedMs != null && elapsedMs >= MOVE_WINDOW_MS
    }
}
