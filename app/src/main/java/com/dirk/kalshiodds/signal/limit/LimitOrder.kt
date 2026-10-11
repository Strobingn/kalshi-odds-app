package com.dirk.kalshiodds.signal.limit

import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * A limit order at a price the user picks, for buys and for sells, paper and
 * real: a **resting** order that waits in the book for someone to trade
 * against it.
 *
 * - It is post-only: Kalshi rejects it instead of letting it cross, so it
 *   can never pay the taker fee (resting orders are free on KXBTC15M).
 * - It always has an end: [build] stamps the time it must be gone by, which
 *   is sent to Kalshi as the order's `expiration_time`, so the order dies on
 *   Kalshi's side even if the app is closed. It never rests into the last
 *   [CLOSE_BUFFER_MS] of a window.
 * - Real buys keep the $5 all-in cap; real orders still need the user's tap.
 *
 * What a limit order does not do: it does not get filled first. Orders
 * already resting at that price are ahead of it, and on seven days of
 * recorded books a bid that joined the best price was filled mostly when the
 * price was about to fall through it (docs/scalping-2026-10-09.md, G–I).
 *
 * Pure: no I/O, unit tested.
 */
object LimitOrder {

    /** "Until the window is nearly over": cancel [CLOSE_BUFFER_MS] before the close. */
    const val UNTIL_CLOSE = -1L

    /** Cancel-after choices offered in the editor, milliseconds. */
    val CANCEL_CHOICES_MS: List<Long> = listOf(30_000L, 120_000L, 300_000L, UNTIL_CLOSE)
    const val DEFAULT_CANCEL_MS = 120_000L

    /** A resting order is pulled this long before the window closes. */
    const val CLOSE_BUFFER_MS = 30_000L

    /** Shortest life worth sending. */
    const val MIN_LIFE_MS = 5_000L

    const val MIN_PRICE = 0.001
    const val MAX_PRICE = 0.999
    private const val EPS = 1e-9

    const val WOULD_BUY_NOW = "That price is at or above the ask: it would buy now, not rest. Lower it, or use Buy."
    const val WOULD_SELL_NOW = "That price is at or below the bid: it would sell now, not rest. Raise it, or use Sell."
    const val TOO_LATE = "Too close to the end of the window to rest an order"
    const val NO_QUOTE = "No live bid or ask for this side yet"
    const val BUY_NOTE = "Rests in the book at your price and pays no fee. It fills only if sellers reach it; " +
        "orders already at that price are ahead of yours."
    const val SELL_NOTE = "Rests in the book at your price and pays no fee. It fills only if buyers reach it. " +
        "The position stays open until then."

    /** Best bid and ask of the ticket's own side, with the sizes shown (null = not known). */
    data class Quote(val bid: Double?, val ask: Double?, val bidQty: Double? = null, val askQty: Double? = null)

    /** Kalshi's price step: 1¢ from 10¢ to 90¢, 0.1¢ outside (`tapered_deci_cent`). */
    fun round(price: Double): Double {
        val p = price.coerceIn(MIN_PRICE, MAX_PRICE)
        return if (p > 0.10 - EPS && p < 0.90 + EPS) (p * 100.0).roundToLong() / 100.0 else (p * 1000.0).roundToLong() / 1000.0
    }

    /** The next valid price above ([up]) or below [price]. */
    fun step(price: Double, up: Boolean): Double {
        val p = round(price)
        val tick = if (up) {
            if (p < 0.10 - EPS || p > 0.90 - EPS) 0.001 else 0.01
        } else {
            if (p < 0.10 + EPS || p > 0.90 + EPS) 0.001 else 0.01
        }
        return round(if (up) p + tick else p - tick)
    }

    /**
     * Where the editor starts: a buy one step above the best bid when there
     * is room under the ask, else at the best bid; a sell one step under the
     * best ask when there is room above the bid, else at the best ask.
     */
    fun defaultPrice(isSell: Boolean, quote: Quote): Double? {
        val bid = quote.bid?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
        val ask = quote.ask?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
        return if (!isSell) {
            val b = bid ?: return ask?.let { step(it, up = false) }
            val better = step(b, up = true)
            if (ask == null || better < ask - EPS) better else round(b)
        } else {
            val a = ask ?: return bid?.let { step(it, up = true) }
            val better = step(a, up = false)
            if (bid == null || better > bid + EPS) better else round(a)
        }
    }

    /**
     * True when [ticket] can become a limit order. Two blocks on the ticket do not carry over, because
     * they are about trading at the current price, not about resting at the user's own:
     *  - "no buyers" on a sell (there is no bid to sell into; an offer can still wait), and
     *  - the min-profit-if-win setting on a buy (it disables the app's buy-at-the-ask ticket; a limit
     *    order is the user's own price and size, shown in full before the tap. The editor says so).
     * A closed window or any other block still stops it.
     */
    fun canRest(ticket: TradeTicket): Boolean =
        ticket.blockedReason.isNullOrBlank() ||
            (ticket.isSell && ticket.blockedReason == TicketBuilder.NO_BUYERS) ||
            (!ticket.isSell && ticket.belowMinProfit)

    /** Most contracts a real buy at [price] may hold under the $5 all-in cap (fee counted, as the order path does). */
    fun maxLiveContracts(price: Double, capUsd: Double = LiveOrderSizer.LIVE_ALL_IN_CAP_USD): Int {
        var n = floor(capUsd / price + EPS).toInt()
        while (n > 0 && n * price + KalshiFee.total(n, price) > capUsd + EPS) n--
        return n.coerceAtLeast(0)
    }

    /** When the order must be gone: the chosen life, never later than [CLOSE_BUFFER_MS] before the close. */
    fun expiresAt(nowMs: Long, cancelAfterMs: Long, closeMs: Long?): Long? {
        val latest = closeMs?.let { it - CLOSE_BUFFER_MS }
        val wanted = if (cancelAfterMs == UNTIL_CLOSE) latest ?: return null else nowMs + cancelAfterMs
        val at = if (latest != null) minOf(wanted, latest) else wanted
        return at.takeIf { it - nowMs >= MIN_LIFE_MS }
    }

    /**
     * [ticket] turned into a resting limit order at [price] for [contracts],
     * or the reason it cannot be. [ticket] is the buy or sell ticket the
     * confirm sheet is showing; its side, market and held size are kept.
     * [paper] skips the real-money cap (the paper book has its own cash).
     */
    fun build(
        ticket: TradeTicket,
        price: Double,
        contracts: Int,
        quote: Quote,
        cancelAfterMs: Long,
        closeMs: Long?,
        nowMs: Long,
        paper: Boolean
    ): Result<TradeTicket> {
        fun fail(msg: String) = Result.failure<TradeTicket>(IllegalStateException(msg))
        if (!canRest(ticket)) return fail(ticket.blockedReason ?: "This ticket cannot rest")
        if (!price.isFinite() || price < MIN_PRICE - EPS || price > MAX_PRICE + EPS) return fail("Price must be between 0.1¢ and 99.9¢")
        val p = round(price)
        if (abs(p - price) > 1e-6) return fail("Price ${cents(price)} is between steps: nearest is ${cents(p)}")
        if (contracts < 1) return fail("At least 1 contract")
        val expires = expiresAt(nowMs, cancelAfterMs, closeMs) ?: return fail(TOO_LATE)
        val yes = !ticket.side.equals("NO", ignoreCase = true)
        val yesLimit = if (yes) p else ((1.0 - p) * 10_000.0).roundToLong() / 10_000.0
        if (ticket.isSell) {
            val held = ticket.heldContracts ?: ticket.contracts
            if (contracts > held) return fail("You hold $held: cannot sell $contracts")
            val bid = quote.bid
            if (bid != null && p <= bid + EPS) return fail(WOULD_SELL_NOW)
            val proceeds = contracts * p
            return Result.success(
                ticket.copy(
                    limitPrice = p,
                    yesLimitPrice = yesLimit,
                    contracts = contracts,
                    stakeUsd = proceeds,
                    estimatedFillUsd = proceeds,
                    estimatedAvgFill = p,
                    maxPayoutUsd = proceeds,
                    feeUsd = 0.0,
                    allInUsd = proceeds,
                    reduceOnly = false,
                    blockedReason = null,
                    postOnly = true,
                    restingCancelAfterMs = appCancelAfter(expires, nowMs),
                    expiresAtMs = expires,
                    sizingNote = String.format(
                        Locale.US, "%d contracts offered at %s · $%.2f if filled · no fee", contracts, cents(p), proceeds
                    ),
                    gateNote = SELL_NOTE
                )
            )
        }
        val ask = quote.ask
        if (ask != null && p >= ask - EPS) return fail(WOULD_BUY_NOW)
        if (!paper) {
            val max = maxLiveContracts(p)
            if (max < 1) return fail("One contract at ${cents(p)} is over the $5 cap")
            if (contracts > max) return fail("Over the $5 cap: at ${cents(p)} the most is $max contracts")
        }
        val cost = contracts * p
        return Result.success(
            ticket.copy(
                limitPrice = p,
                yesLimitPrice = yesLimit,
                contracts = contracts,
                stakeUsd = cost,
                estimatedFillUsd = cost,
                estimatedAvgFill = p,
                maxPayoutUsd = contracts.toDouble(),
                feeUsd = 0.0,
                allInUsd = cost,
                profitIfWinUsd = contracts - cost,
                blockedReason = null,
                belowMinProfit = false,
                netEvUsd = null,
                netEvPerContract = null,
                netEdgePp = null,
                visibleContracts = null,
                postOnly = true,
                restingCancelAfterMs = appCancelAfter(expires, nowMs),
                expiresAtMs = expires,
                sizingNote = String.format(
                    Locale.US, "%d contracts bid at %s · $%.2f if filled · no fee", contracts, cents(p), cost
                ),
                gateNote = BUY_NOTE
            )
        )
    }

    /**
     * The app cancels [APP_CANCEL_LEAD_MS] before Kalshi's own expiry, so the cancel normally comes from the
     * app (which then knows how much was unfilled) and Kalshi's expiry is the backstop when the app is closed.
     */
    const val APP_CANCEL_LEAD_MS = 2_000L

    private fun appCancelAfter(expiresAtMs: Long, nowMs: Long): Long =
        (expiresAtMs - nowMs - APP_CANCEL_LEAD_MS).coerceAtLeast(1_000L)

    /** `54¢`, `90.5¢`. */
    fun cents(price: Double): String {
        val c = price * 100.0
        return if (abs(c - c.roundToLong()) < 0.005) "${c.roundToLong()}¢" else String.format(Locale.US, "%.1f¢", c)
    }

    /** `30 s`, `2 min`, `until 30 s before the close`. */
    fun cancelLabel(ms: Long): String = when {
        ms == UNTIL_CLOSE -> "until ${CLOSE_BUFFER_MS / 1000} s before the close"
        ms < 60_000L -> "${ms / 1000} s"
        else -> "${ms / 60_000L} min"
    }
}
