package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request

@kotlinx.serialization.Serializable
data class LimitOptions(
    val postOnly: Boolean = false,
    val timeInForce: String = CreateOrderV2Request.TIME_IN_FORCE_GTC,
    val expirationTime: Long? = null
) {
    fun validate(nowMs: Long, reduceOnly: Boolean) {
        require(timeInForce in setOf("good_till_canceled", "immediate_or_cancel", "fill_or_kill")) { "Invalid time in force" }
        require(!postOnly || timeInForce == "good_till_canceled") { "Maker-only needs GTC" }
        require(expirationTime == null || timeInForce == "good_till_canceled") { "Expiry needs GTC" }
        require(expirationTime == null || expirationTime > nowMs / 1000) { "Order expiry has passed" }
        require(!reduceOnly || (!postOnly && expirationTime == null)) { "Reduce-only exits use IOC" }
    }
}

/** Exact user size; conservative taker fees are reserved even for post-only orders. */
object LimitOrderEditor {
    fun edit(ticket: TradeTicket, count: Int, price: Double, options: LimitOptions,
             cashUsd: Double?, nowMs: Long): TradeTicket {
        require(!ticket.isSell) { "Use the reduce-only sell flow" }
        require(count in 1..5000) { "Enter 1–5000 contracts" }
        require(KalshiPrice.usable(price) != null && price.isFinite()) { "Enter a valid limit price" }
        val wire = KalshiPrice.toWireDollars(price) ?: error("Invalid price")
        require(kotlin.math.abs(wire.toDouble() - price) < 1e-8) { "Price supports at most four dollar decimal places" }
        options.validate(nowMs, false)
        val total = LiveOrderSizer.allInUsd(count, price)
        require(total <= LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-9) { "Limit exceeds the $5 all-in cap" }
        require(cashUsd != null && cashUsd.isFinite() && total <= cashUsd + 1e-9) { "Refresh live cash; order must fit available balance" }
        return ticket.copy(contracts = count, limitPrice = price,
            yesLimitPrice = if (ticket.side == "NO") 1.0 - price else price,
            bookSide = if (ticket.side == "NO") "ask" else "bid",
            stakeUsd = total, allInUsd = total, estimatedFillUsd = total,
            estimatedAvgFill = price, feeUsd = LiveOrderSizer.feeUsd(count, price),
            maxPayoutUsd = count.toDouble(), profitIfWinUsd = count - total,
            limitOptions = options, belowMinProfit = false,
            // Preserve closed/stale-market blocks; manual buys have no settlement-profit minimum.
            blockedReason = ticket.blockedReason,
            sizingNote = "$count contracts at ${price * 100}¢ · limit order · conservative fee reserve")
    }
}
