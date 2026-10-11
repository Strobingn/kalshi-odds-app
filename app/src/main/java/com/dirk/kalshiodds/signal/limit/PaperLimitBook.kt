package com.dirk.kalshiodds.signal.limit

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One paper limit order waiting in the (real) book. Never a real order. */
data class PaperLimitOrder(
    val id: String,
    /** The resting ticket from [LimitOrder.build]. */
    val ticket: TradeTicket,
    /** Contracts shown at that price when it was placed (the assumed size when the book did not show it). */
    val queueAhead: Double,
    val queueKnown: Boolean,
    val placedAtMs: Long,
    val expiresAtMs: Long,
    /** Contracts that must still trade at the price before the whole order is filled (queue + own size). */
    val needed: Double
) {
    /** Contracts still ahead of the order, by the paper rule. */
    val stillAhead: Double get() = (needed - ticket.contracts).coerceAtLeast(0.0)
}

/**
 * Paper limit orders, filled only by **real public trades**.
 *
 * A resting order fills when a trade prints *through* its price, or when the
 * contracts ahead of it plus its own size have traded *at* its price. The
 * contracts ahead start as the size the live book shows at that price and can
 * never be more than the size shown there later (orders that join later are
 * behind it). Without the book the queue is taken as [unknownQueue]. The whole
 * order fills at once, at its own price, with no fee, into the paper book.
 *
 * A sell of UP at p is the same resting order as a bid for DOWN at 1 − p, so
 * every order is tracked as a bid on one side.
 *
 * Orders live in memory: after an app restart the prints in between were not
 * seen, so they are dropped rather than guessed. Paper only: no trade client,
 * no credentials.
 */
class PaperLimitBook(
    private val paper: PaperBook,
    private val levels: (ticker: String) -> BookLevelSnapshot? = { null },
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val unknownQueue: Double = 3_500.0
) {
    private val lock = Any()
    private val _state = MutableStateFlow<List<PaperLimitOrder>>(emptyList())
    val state: StateFlow<List<PaperLimitOrder>> = _state.asStateFlow()

    /** The last thing that happened, for the ticket card. */
    @Volatile var lastMessage: String? = null
        private set

    fun snapshot(): List<PaperLimitOrder> = _state.value

    /** Tickers with a resting paper order (so the trade feed keeps watching them). */
    fun openTickers(): Set<String> = _state.value.map { it.ticket.ticker }.toSet()

    /** Rest [ticket] (from [LimitOrder.build]) on paper. Returns a message for the user. */
    fun place(ticket: TradeTicket, nowMs: Long): Result<PaperLimitOrder> {
        fun fail(msg: String): Result<PaperLimitOrder> {
            lastMessage = msg
            return Result.failure(IllegalStateException(msg))
        }
        if (!ticket.postOnly) return fail("Not a limit order")
        if (ticket.contracts < 1) return fail("At least 1 contract")
        val expires = ticket.expiresAtMs ?: return fail(LimitOrder.TOO_LATE)
        if (!CryptoMarkets.isLiveTicker(ticket.ticker)) return fail("Paper limit orders are Bitcoin only")
        synchronized(lock) {
            val state = paper.snapshot()
            val open = state.fills.firstOrNull { !it.settled && it.ticker.equals(ticket.ticker, ignoreCase = true) }
            if (ticket.isSell) {
                val side = normal(ticket.side)
                if (open == null || !open.side.equals(side, ignoreCase = true) || open.contracts < ticket.contracts) {
                    return fail("No open paper ${label(side)} position of ${ticket.contracts} contracts to sell")
                }
                if (_state.value.any { it.ticket.isSell && it.ticket.ticker == ticket.ticker }) {
                    return fail("A paper limit sell is already resting on this window")
                }
            } else {
                if (open != null) return fail("The paper book already holds a position on this window")
                if (_state.value.any { !it.ticket.isSell && it.ticket.ticker == ticket.ticker }) {
                    return fail("A paper limit buy is already resting on this window")
                }
                val cost = ticket.contracts * ticket.limitPrice
                if (cost > state.cashUsd + 1e-9) {
                    return fail(String.format(Locale.US, "Paper cash $%.2f does not cover $%.2f", state.cashUsd, cost))
                }
            }
            val (bidSide, bidPrice) = asBid(ticket)
            val shown = shownAt(ticket.ticker, bidSide, bidPrice)
            val queue = shown ?: unknownQueue
            val order = PaperLimitOrder(
                id = idFactory(),
                ticket = ticket,
                queueAhead = queue,
                queueKnown = shown != null,
                placedAtMs = nowMs,
                expiresAtMs = expires,
                needed = queue + ticket.contracts
            )
            _state.value = _state.value + order
            lastMessage = String.format(
                Locale.US,
                "PAPER limit %s %s · %d ct @ %s resting · %s ahead · never Kalshi",
                if (ticket.isSell) "sell" else "buy",
                label(ticket.side),
                ticket.contracts,
                LimitOrder.cents(ticket.limitPrice),
                if (shown != null) String.format(Locale.US, "%,.0f", queue) else String.format(Locale.US, "about %,.0f (book not shown)", queue)
            )
            return Result.success(order)
        }
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        val order = _state.value.firstOrNull { it.id == id } ?: return false
        _state.value = _state.value.filterNot { it.id == id }
        lastMessage = "PAPER limit ${describe(order.ticket)} cancelled, unfilled"
        true
    }

    /** A public trade: [takerSide] "yes" = the taker bought YES at [yesPrice]. Returns the orders it filled. */
    fun onTrade(ticker: String, takerSide: String?, yesPrice: Double?, contracts: Double?, nowMs: Long): List<PaperLimitOrder> {
        if (_state.value.none { it.ticket.ticker == ticker }) return emptyList()
        val takerYes = when (takerSide?.lowercase(Locale.US)) {
            "yes" -> true
            "no" -> false
            else -> return emptyList()
        }
        val p = yesPrice?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return emptyList()
        val c = contracts?.takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
        val filled = ArrayList<PaperLimitOrder>()
        synchronized(lock) {
            val next = ArrayList<PaperLimitOrder>(_state.value.size)
            for (o in _state.value) {
                if (o.ticket.ticker != ticker || nowMs > o.expiresAtMs) { next += o; continue }
                val (bidSide, bidPrice) = asBid(o.ticket)
                val bidYes = bidSide == "YES"
                // A bid is hit by a taker selling that side, i.e. buying the other one.
                if (takerYes == bidYes) { next += o; continue }
                val sidePrice = if (bidYes) p else 1.0 - p
                val through = sidePrice < bidPrice - TICK_EPS
                val at = abs(sidePrice - bidPrice) <= TICK_EPS
                val needed = if (at) o.needed - c else o.needed
                if (through || needed <= 1e-9) {
                    if (book(o)) filled += o else next += o.copy(needed = needed.coerceAtLeast(0.0))
                } else {
                    next += o.copy(needed = needed)
                }
            }
            _state.value = next
        }
        return filled
    }

    /**
     * Time passing: expire orders, and tighten the queue from the live book
     * (the contracts ahead can never be more than the size now shown at the
     * price; none once the best bid has moved below it).
     */
    fun onClock(ticker: String, nowMs: Long) {
        if (_state.value.none { it.ticket.ticker == ticker }) return
        synchronized(lock) {
            val next = ArrayList<PaperLimitOrder>(_state.value.size)
            for (o in _state.value) {
                if (o.ticket.ticker != ticker) { next += o; continue }
                if (nowMs >= o.expiresAtMs) {
                    lastMessage = "PAPER limit ${describe(o.ticket)} expired unfilled"
                    continue
                }
                val (bidSide, bidPrice) = asBid(o.ticket)
                val bound = boundAt(ticker, bidSide, bidPrice)
                next += if (bound != null && bound + o.ticket.contracts < o.needed) o.copy(needed = bound + o.ticket.contracts) else o
            }
            _state.value = next
        }
    }

    /** The window closed or settled: nothing can fill any more. */
    fun closeTicker(ticker: String) = synchronized(lock) {
        if (_state.value.any { it.ticket.ticker == ticker }) {
            _state.value = _state.value.filterNot { it.ticket.ticker == ticker }
            lastMessage = "PAPER limit orders on the closed window were cancelled, unfilled"
        }
    }

    fun clear() = synchronized(lock) { _state.value = emptyList() }

    /** Put the fill into the paper book. False when the book refuses it (the order then stays). */
    private fun book(o: PaperLimitOrder): Boolean {
        val t = o.ticket
        val ok = if (t.isSell) {
            paper.sell(t) != null
        } else {
            paper.explicitFill(
                ticker = t.ticker,
                side = normal(t.side),
                limitPrice = t.limitPrice,
                wantContracts = t.contracts,
                source = "paper limit",
                note = "Paper limit buy · rested and filled by a real trade · no fee · never sent to Kalshi",
                maker = true
            ).ok
        }
        if (ok) lastMessage = "PAPER limit ${describe(t)} FILLED · no fee · never Kalshi"
        return ok
    }

    /** Contracts resting where [ticket] would wait at [price]; null when the book is not shown. */
    fun queueAhead(ticket: TradeTicket, price: Double): Double? {
        val (side, p) = asBid(ticket.copy(limitPrice = price))
        return shownAt(ticket.ticker, side, p)
    }

    /** Size shown at the price on that side's bids; 0 when the book is there and the level is empty; null without a book. */
    private fun shownAt(ticker: String, bidSide: String, price: Double): Double? {
        val b = runCatching { levels(ticker) }.getOrNull()?.takeIf { !it.isEmpty() } ?: return null
        val side = if (bidSide == "YES") b.yes else b.no
        return side.firstOrNull { abs(it.first - price) <= TICK_EPS }?.second?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    /** Upper bound on the contracts ahead from the book now, or null when it says nothing. */
    private fun boundAt(ticker: String, bidSide: String, price: Double): Double? {
        val b = runCatching { levels(ticker) }.getOrNull()?.takeIf { !it.isEmpty() } ?: return null
        val side = if (bidSide == "YES") b.yes else b.no
        if (side.isEmpty()) return null
        side.firstOrNull { abs(it.first - price) <= TICK_EPS }?.second?.let { return it.coerceAtLeast(0.0) }
        // No orders at our price: whoever was there has gone.
        return 0.0
    }

    companion object {
        const val TICK_EPS = 5e-4

        fun normal(side: String): String = if (side.equals("NO", ignoreCase = true)) "NO" else "YES"
        fun label(side: String): String = if (normal(side) == "NO") "DOWN" else "UP"

        /** The order as a resting bid: (side whose bids it joins, price in that side's dollars). */
        fun asBid(ticket: TradeTicket): Pair<String, Double> {
            val side = normal(ticket.side)
            if (!ticket.isSell) return side to ticket.limitPrice
            val other = if (side == "YES") "NO" else "YES"
            return other to ((1.0 - ticket.limitPrice) * 10_000.0).roundToLong() / 10_000.0
        }

        fun describe(t: TradeTicket): String =
            "${if (t.isSell) "sell" else "buy"} ${label(t.side)} ${t.contracts} ct @ ${LimitOrder.cents(t.limitPrice)}"
    }
}
