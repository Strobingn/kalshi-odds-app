package com.dirk.kalshiodds.signal.trade

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * "Rest 1¢ better": turn a buy ticket (which crosses the spread and pays
 * Kalshi's taker fee) into a **post-only resting bid** one cent above the
 * best bid. Kalshi charges nothing for resting orders on the BTC / ETH /
 * SOL 15-minute series (fee probe, 2026-10-05: fee_type quadratic, maker
 * rate 0), and post-only guarantees the order can never turn into a taker
 * order: Kalshi rejects it instead of crossing.
 *
 * What it buys you: a price a cent or more under the ask and no fee, so
 * about 2.5–3.5¢ per contract cheaper than buying now. What it costs: the
 * order may not fill, and fills tend to arrive when the price is moving
 * against the order. It is cancelled after [CANCEL_AFTER_MS] if unfilled.
 * Not proven to be profitable; it only removes the cost of buying.
 */
object RestingBid {
    const val CANCEL_AFTER_MS = 30_000L
    const val TICK = 0.01
    const val MIN_PRICE = 0.05
    const val MAX_PRICE = 0.95
    private const val EPS = 1e-9

    const val NOTE =
        "Rests a no-fee bid 1¢ above the best bid and cancels after 30 s if unfilled. " +
            "Cheaper than buying now, but it may not fill, and fills tend to come when the price moves against you."

    const val NO_QUOTE = "No live bid or ask to rest against"
    const val NOT_THIS_COIN = "Resting bids lost money on ETH and SOL in the cloud recordings: BTC only"

    /**
     * Coins where resting is offered. The cloud maker simulation (7 days,
     * 2026-10-03 → 10-09, conservative fills, bid + 1¢, margin 0.02,
     * cancel 30 s) lost on ETH (−0.80¢/contract, 99% range below zero, also
     * on held-out days) and SOL (−0.95¢ on held-out days, range below zero).
     * BTC is unproven either way, so only BTC keeps the option.
     */
    val SERIES = listOf("KXBTC15M")

    fun allowedFor(ticker: String): Boolean = SERIES.any { ticker.startsWith(it, ignoreCase = true) }
    const val NO_ROOM = "Spread is 1¢: no room to rest a bid"

    /** (price, contracts) for a resting bid, or null with the reason in [failure]. */
    data class Plan(val price: Double, val contracts: Int)

    fun priceFor(bid: Double?, ask: Double?): Double? {
        val b = bid?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val a = ask?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val p = ((b + TICK) * 100.0).roundToLong() / 100.0
        if (p >= a - EPS) return null
        if (p < MIN_PRICE - EPS || p > MAX_PRICE + EPS) return null
        return p
    }

    /**
     * The resting version of [ticket], or a failure message. Buys only;
     * the stake stays the ticket's stake and no fee is charged.
     */
    fun build(ticket: TradeTicket, bid: Double?, ask: Double?): Result<TradeTicket> {
        if (ticket.isSell || ticket.paperOnly || ticket.reduceOnly) {
            return Result.failure(IllegalStateException("Only live buys can rest"))
        }
        if (!allowedFor(ticket.ticker)) return Result.failure(IllegalStateException(NOT_THIS_COIN))
        if (bid == null || ask == null) return Result.failure(IllegalStateException(NO_QUOTE))
        val price = priceFor(bid, ask) ?: return Result.failure(
            IllegalStateException(
                if (bid + TICK >= ask - EPS) NO_ROOM else "Price is outside 5–95¢: not resting"
            )
        )
        val contracts = floor(ticket.stakeUsd / price + EPS).toInt()
        if (contracts <= 0) {
            return Result.failure(IllegalStateException("Stake is too small for one contract at ${cents(price)}"))
        }
        val cost = contracts * price
        val yesLimit = if (ticket.side.equals("YES", ignoreCase = true)) price else ((1.0 - price) * 10_000.0).roundToLong() / 10_000.0
        return Result.success(
            ticket.copy(
                limitPrice = price,
                yesLimitPrice = yesLimit,
                contracts = contracts,
                estimatedFillUsd = cost,
                estimatedAvgFill = price,
                maxPayoutUsd = contracts.toDouble(),
                feeUsd = 0.0,
                allInUsd = cost,
                profitIfWinUsd = contracts - cost,
                belowMinProfit = false,
                netEvUsd = null,
                netEvPerContract = null,
                netEdgePp = null,
                visibleContracts = null,
                postOnly = true,
                restingCancelAfterMs = CANCEL_AFTER_MS,
                sizingNote = String.format(
                    Locale.US,
                    "%d contracts resting at %s · %s if filled · no fee · was %s to buy now",
                    contracts,
                    cents(price),
                    "$" + String.format(Locale.US, "%.2f", cost),
                    cents(ask)
                ),
                gateNote = NOTE
            )
        )
    }

    fun cents(p: Double): String = String.format(Locale.US, "%.0f¢", p * 100.0)
}
