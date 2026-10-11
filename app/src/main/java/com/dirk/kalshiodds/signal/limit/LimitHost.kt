package com.dirk.kalshiodds.signal.limit

import com.dirk.kalshiodds.signal.trade.TradeTicket

/**
 * What the limit-order editor needs from the app. The editor never places
 * anything itself: [place] is called only from its confirm buttons.
 */
interface LimitHost {
    /** Live best bid / ask (and sizes) of the ticket's side. */
    fun quote(ticket: TradeTicket): LimitOrder.Quote

    /** Contracts already resting at [price] where this order would wait; null when the book is not shown. */
    fun queueAhead(ticket: TradeTicket, price: Double): Double?

    /** Why this order cannot be placed right now, or null when it can. */
    fun check(ticket: TradeTicket, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean): String?

    /** Place it: on the paper book when [paper], otherwise a real post-only order on Kalshi. */
    fun place(ticketId: String, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean)

    fun cancelPaper(orderId: String)

    /** True when the live trade feed is on (paper limit orders fill only from it). */
    val tradeFeedOn: Boolean
}
