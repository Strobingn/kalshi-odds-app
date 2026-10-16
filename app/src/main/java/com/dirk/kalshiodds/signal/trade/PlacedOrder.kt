package com.dirk.kalshiodds.signal.trade

/**
 * Represents a placed order from Kalshi.
 * This is the response from a successful create-order call.
 *
 * Note: This is used for both live orders and paper trading tracking.
 * For paper trading, orderId may be null and clientOrderId is used as the primary identifier.
 */
data class PlacedOrder(
    /** The original ticket that was submitted */
    val ticket: TradeTicket,
    /** Client-supplied order ID (used for idempotency) */
    val clientOrderId: String,
    /** Kalshi's order ID (null for paper orders) */
    val orderId: String? = null,
    /** Number of contracts filled so far */
    val fillCount: Double = 0.0,
    /** Number of contracts remaining to be filled */
    val remainingCount: Double,
    /** Average price at which contracts were filled */
    val averageFillPrice: Double? = null,
    /** Timestamp when order was placed (milliseconds since epoch) */
    val placedAtMs: Long = System.currentTimeMillis(),
    /** Error message if order failed or was cancelled */
    val error: String? = null
) {
    /** Total contracts in the order */
    val totalCount: Double get() = fillCount + remainingCount
    
    /** Check if order is fully filled */
    val isFilled: Boolean get() = remainingCount <= 0
    
    /** Check if order has any fills */
    val hasFills: Boolean get() = fillCount > 0
    
    /** Check if order was cancelled */
    val isCancelled: Boolean get() = error?.contains("cancelled") == true
    
    /** Get the side being traded */
    val side: String get() = ticket.side
    
    /** Get the ticker */
    val ticker: String get() = ticket.ticker
    
    /** Check if this is a paper order (no Kalshi order ID) */
    val isPaper: Boolean get() = orderId == null
}
