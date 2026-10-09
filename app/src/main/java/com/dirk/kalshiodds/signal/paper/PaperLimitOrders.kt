package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 0.3.43 PAPER limit orders (manual tickets and scalp strategies). Never calls Kalshi, never places a live order.
 *
 * Fill rule (owner override, 2026-10-09): a paper limit fills at EXACTLY the limit price as soon as the market
 * reaches it — buy limit L fills when best ask ≤ L, sell limit L fills when best bid ≥ L — up to the displayed
 * depth at or better than L. Partial fills by depth. No price improvement, no slippage, no trade-through wait.
 * NOTE: touch fills are slightly optimistic vs real queue priority (a real resting order waits behind the queue).
 *
 * Fees: the part that fills immediately on submit is a taker fill (Kalshi taker fee); anything that fills later
 * while resting is a maker fill (series maker fee — $0 for series not on Kalshi's Maker Fees table).
 * Unfilled remainder auto-cancels at window close.
 */
@Serializable
data class PaperOrder(
    val id: String,
    val ticker: String,
    /** YES (UP) or NO (DOWN). */
    val side: String,
    /** BUY or SELL. */
    val action: String,
    val limitPrice: Double,
    val quantity: Int,
    val filledQty: Int = 0,
    val takerQty: Int = 0,
    val makerQty: Int = 0,
    val feesUsd: Double = 0.0,
    val status: String = STATUS_OPEN,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val closeTimeMs: Long? = null,
    /** "manual" or the scalp strategy/variant id (tracked separately in its stats). */
    val source: String = SOURCE_MANUAL,
    val note: String = ""
) {
    val remaining: Int get() = (quantity - filledQty).coerceAtLeast(0)
    val isOpen: Boolean get() = status == STATUS_OPEN && remaining > 0
    val isBuy: Boolean get() = action.equals(BUY, true)
    val displaySide: String get() = if (side.equals("NO", true)) "DOWN" else "UP"

    companion object {
        const val BUY = "BUY"
        const val SELL = "SELL"
        const val STATUS_OPEN = "open"
        const val STATUS_FILLED = "filled"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_EXPIRED = "expired"
        const val SOURCE_MANUAL = "manual"
        const val TOUCH_FILL_NOTE = "Paper limit: fills at exactly your limit when the market reaches it, up to displayed depth — slightly optimistic vs real queue priority."
    }
}

/** Best price + displayed depth at-or-better for one side of one market (prices in dollars, sizes in contracts). */
data class PaperSideQuote(
    val bestAsk: Double?,
    val askDepthAtOrBelow: (Double) -> Double,
    val bestBid: Double?,
    val bidDepthAtOrAbove: (Double) -> Double
) {
    companion object {
        /** Top-of-book only (depth known for the best level). */
        fun top(bestAsk: Double?, askSize: Double?, bestBid: Double?, bidSize: Double?) = PaperSideQuote(
            bestAsk = bestAsk,
            askDepthAtOrBelow = { l -> if (bestAsk != null && bestAsk <= l + 1e-9) askSize ?: 0.0 else 0.0 },
            bestBid = bestBid,
            bidDepthAtOrAbove = { l -> if (bestBid != null && bestBid >= l - 1e-9) bidSize ?: 0.0 else 0.0 }
        )

        /**
         * From the live book (YES bids / NO bids). For [side] YES: asks are 1 − NO bids; for NO: asks are 1 − YES bids.
         * Depth sums every level at or better than the limit.
         */
        fun fromBook(side: String, yesBids: List<Pair<Double, Double>>, noBids: List<Pair<Double, Double>>): PaperSideQuote {
            val own = if (side.equals("NO", true)) noBids else yesBids
            val other = if (side.equals("NO", true)) yesBids else noBids
            val asks = other.filter { it.second > 0.0 }.map { (1.0 - it.first) to it.second }
            val bids = own.filter { it.second > 0.0 }
            return PaperSideQuote(
                bestAsk = asks.minOfOrNull { it.first },
                askDepthAtOrBelow = { l -> asks.filter { it.first <= l + 1e-9 }.sumOf { it.second } },
                bestBid = bids.maxOfOrNull { it.first },
                bidDepthAtOrAbove = { l -> bids.filter { it.first >= l - 1e-9 }.sumOf { it.second } }
            )
        }
    }
}

object PaperLimitFill {
    /** Contracts that fill at the limit right now (touch rule, depth-capped). */
    fun touchFillQty(limit: Double, best: Double?, depth: Double?, remaining: Int, buy: Boolean): Int {
        if (remaining <= 0 || best == null) return 0
        val reached = if (buy) best <= limit + 1e-9 else best >= limit - 1e-9
        if (!reached) return 0
        val d = kotlin.math.floor((depth ?: 0.0) + 1e-9).toInt()
        return minOf(remaining, d).coerceAtLeast(0)
    }

    fun fillQty(order: PaperOrder, q: PaperSideQuote): Int =
        if (order.isBuy) touchFillQty(order.limitPrice, q.bestAsk, q.askDepthAtOrBelow(order.limitPrice), order.remaining, true)
        else touchFillQty(order.limitPrice, q.bestBid, q.bidDepthAtOrAbove(order.limitPrice), order.remaining, false)

    /** Taker fee on immediate fills, series maker fee on resting fills. */
    fun fee(contracts: Int, price: Double, ticker: String, maker: Boolean): Double =
        if (maker) KalshiFee.makerFee(contracts, price, CryptoMarkets.inferSeries(ticker))
        else KalshiFee.takerFee(contracts, price)
}

interface PaperOrderPersistence {
    fun loadPaperOrders(): List<PaperOrder>
    fun upsertPaperOrder(order: PaperOrder)
}

class InMemoryPaperOrderPersistence : PaperOrderPersistence {
    private val rows = LinkedHashMap<String, PaperOrder>()
    override fun loadPaperOrders(): List<PaperOrder> = synchronized(rows) { rows.values.toList() }
    override fun upsertPaperOrder(order: PaperOrder) { synchronized(rows) { rows[order.id] = order } }
}

/** Executes a paper fill against the paper ledger; returns contracts actually filled (cash / position capped). */
interface PaperFillSink {
    fun buy(order: PaperOrder, qty: Int, feeUsd: Double): Int
    fun sell(order: PaperOrder, qty: Int, feeUsd: Double): Int
    fun held(ticker: String, side: String): Int
}

class PaperBookFillSink(private val book: PaperBook) : PaperFillSink {
    override fun buy(order: PaperOrder, qty: Int, feeUsd: Double): Int =
        book.limitBuyFill(order.ticker, order.side, qty, order.limitPrice, feeUsd,
            String.format(Locale.US, "Paper limit buy %s @ %.0f¢ · %s", order.displaySide, order.limitPrice * 100, PaperOrder.TOUCH_FILL_NOTE))
    override fun sell(order: PaperOrder, qty: Int, feeUsd: Double): Int =
        book.limitSellFill(order.ticker, order.side, qty, order.limitPrice, feeUsd)
    override fun held(ticker: String, side: String): Int = book.openContracts(ticker, side)
}

/**
 * Open paper limit orders: submit (immediate taker part + resting remainder), edit price/qty, cancel,
 * fill on every quote (maker), auto-cancel at window close. Persisted (paper_orders table).
 */
class PaperOrderBook(
    private val store: PaperOrderPersistence = InMemoryPaperOrderPersistence(),
    private val sink: PaperFillSink? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _orders = MutableStateFlow(store.loadPaperOrders().sortedByDescending { it.createdAtMs })
    val orders: StateFlow<List<PaperOrder>> = _orders.asStateFlow()

    fun open(): List<PaperOrder> = _orders.value.filter { it.isOpen }
    fun openFor(source: String): List<PaperOrder> = open().filter { it.source == source }

    data class Result(val order: PaperOrder?, val message: String)

    fun submit(
        ticker: String,
        side: String,
        action: String,
        limitPrice: Double,
        quantity: Int,
        closeTimeMs: Long?,
        quote: PaperSideQuote?,
        source: String = PaperOrder.SOURCE_MANUAL
    ): Result = synchronized(lock) {
        val now = nowMs()
        val px = com.dirk.kalshiodds.domain.KalshiPrice.usable(limitPrice) ?: return Result(null, "Paper limit: unusable price")
        if (quantity <= 0) return Result(null, "Paper limit: quantity must be ≥ 1")
        if (closeTimeMs != null && now >= closeTimeMs) return Result(null, "Paper limit: window already closed")
        val want = if (side.equals("NO", true) || side.equals("DOWN", true)) "NO" else "YES"
        val act = if (action.equals(PaperOrder.SELL, true)) PaperOrder.SELL else PaperOrder.BUY
        if (act == PaperOrder.SELL && sink != null && sink.held(ticker, want) <= 0) {
            return Result(null, "Paper sell limit: no open ${if (want == "NO") "DOWN" else "UP"} position on $ticker")
        }
        var order = PaperOrder(
            id = idFactory(), ticker = ticker.uppercase(), side = want, action = act, limitPrice = px,
            quantity = quantity, createdAtMs = now, closeTimeMs = closeTimeMs, source = source,
            note = PaperOrder.TOUCH_FILL_NOTE
        )
        if (quote != null) order = fill(order, quote, maker = false, now)
        save(order)
        val msg = String.format(
            Locale.US, "PAPER %s LIMIT %s %s %d @ %.0f¢ · filled %d now (taker), %d resting · never Kalshi",
            act, order.displaySide, order.ticker, quantity, px * 100, order.filledQty, order.remaining
        )
        Result(order, msg)
    }

    /** Edit price and/or quantity of an open order (quantity cannot drop below what already filled). */
    fun edit(id: String, limitPrice: Double?, quantity: Int?, quote: PaperSideQuote? = null): Result = synchronized(lock) {
        val cur = _orders.value.firstOrNull { it.id == id } ?: return Result(null, "Paper order not found")
        if (!cur.isOpen) return Result(cur, "Paper order is ${cur.status}")
        val px = limitPrice?.let { com.dirk.kalshiodds.domain.KalshiPrice.usable(it) ?: return Result(cur, "Paper limit: unusable price") } ?: cur.limitPrice
        val qty = (quantity ?: cur.quantity).coerceAtLeast(cur.filledQty)
        var next = cur.copy(limitPrice = px, quantity = qty, updatedAtMs = nowMs())
        if (next.remaining <= 0) next = next.copy(status = PaperOrder.STATUS_FILLED)
        // An edit that crosses the market fills immediately → taker.
        if (quote != null && next.isOpen) next = fill(next, quote, maker = false, nowMs())
        save(next)
        Result(next, String.format(Locale.US, "Paper order edited: %d @ %.0f¢", next.quantity, next.limitPrice * 100))
    }

    fun cancel(id: String, reason: String = PaperOrder.STATUS_CANCELLED): Result = synchronized(lock) {
        val cur = _orders.value.firstOrNull { it.id == id } ?: return Result(null, "Paper order not found")
        if (!cur.isOpen) return Result(cur, "Paper order is ${cur.status}")
        val next = cur.copy(status = reason, updatedAtMs = nowMs())
        save(next)
        Result(next, "Paper order ${if (reason == PaperOrder.STATUS_EXPIRED) "expired at window close" else "cancelled"} (${cur.filledQty}/${cur.quantity} filled)")
    }

    /** Resting orders on [ticker] fill at their limit when the market reaches it (maker). Then expire at close. */
    fun onQuote(ticker: String, quoteFor: (side: String) -> PaperSideQuote?, atMs: Long = nowMs()): List<PaperOrder> = synchronized(lock) {
        val changed = ArrayList<PaperOrder>()
        for (o in open().filter { it.ticker.equals(ticker, true) }) {
            if (o.closeTimeMs != null && atMs >= o.closeTimeMs) {
                changed += o.copy(status = PaperOrder.STATUS_EXPIRED, updatedAtMs = atMs)
                continue
            }
            val q = quoteFor(o.side) ?: continue
            val next = fill(o, q, maker = true, atMs)
            if (next != o) changed += next
        }
        changed.forEach { save(it) }
        changed
    }

    /** Auto-cancel every unfilled remainder whose window has closed. */
    fun expire(atMs: Long = nowMs()): List<PaperOrder> = synchronized(lock) {
        val out = open().filter { it.closeTimeMs != null && atMs >= it.closeTimeMs }
            .map { it.copy(status = PaperOrder.STATUS_EXPIRED, updatedAtMs = atMs) }
        out.forEach { save(it) }
        out
    }

    private fun fill(o: PaperOrder, q: PaperSideQuote, maker: Boolean, atMs: Long): PaperOrder {
        var n = PaperLimitFill.fillQty(o, q)
        if (n <= 0) return o
        val fee = PaperLimitFill.fee(n, o.limitPrice, o.ticker, maker)
        if (sink != null) {
            n = if (o.isBuy) sink.buy(o, n, fee) else sink.sell(o, n, fee)
            if (n <= 0) return o
        }
        val paidFee = PaperLimitFill.fee(n, o.limitPrice, o.ticker, maker)
        val filled = o.filledQty + n
        return o.copy(
            filledQty = filled,
            takerQty = o.takerQty + if (maker) 0 else n,
            makerQty = o.makerQty + if (maker) n else 0,
            feesUsd = o.feesUsd + paidFee,
            status = if (filled >= o.quantity) PaperOrder.STATUS_FILLED else PaperOrder.STATUS_OPEN,
            updatedAtMs = atMs
        )
    }

    private fun save(o: PaperOrder) {
        runCatching { store.upsertPaperOrder(o) }
        val cur = _orders.value
        val i = cur.indexOfFirst { it.id == o.id }
        _orders.value = if (i >= 0) cur.toMutableList().also { it[i] = o } else listOf(o) + cur
    }
}
