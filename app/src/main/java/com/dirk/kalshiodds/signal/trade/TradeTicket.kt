package com.dirk.kalshiodds.signal.trade

enum class TicketKind {
    /** Settings stake (default $5) \u2192 \u2265$100 max payout. */
    CONFIGURED,
    /** Automatic hunter: $1 stake \u2192 \u2265$25 max payout. */
    HUNTER,
    /** Long-shot hunter: ask \u2264 ~20\u00a2 and AI/fair beats implied after fees. */
    HUNTER_VALUE,
    /** User tapped Buy on a market card / hero. */
    MANUAL,
    /** Sell / reduce a held YES or NO position. IoC reduce-only at the bid. */
    SELL,
    /** Winning strategy: cheap sides in the right time window. */
    WINNING_STRATEGY
}

/**
 * One proposed (or working) approve-gated limit ticket.
 * Never submitted unless [TicketSession.approve] is called with this [id].
 */
data class TradeTicket(
    val id: String,
    val ticker: String,
    /** Outcome side the user is buying: YES or NO. */
    val side: String,
    /**
     * V2 single-book side. `bid` = buy YES; `ask` = sell YES (= buy NO at 1\u2212p).
     */
    val bookSide: String,
    val stakeUsd: Double,
    /** Price paid per chosen-side contract (0.001\u20130.999, including sub-cent). */
    val limitPrice: Double,
    /** YES-leg price sent to POST /portfolio/events/orders. */
    val yesLimitPrice: Double,
    val contracts: Int,
    val estimatedFillUsd: Double,
    val maxPayoutUsd: Double,
    val estimatedAvgFill: Double,
    val netEvUsd: Double? = null,
    val netEvPerContract: Double? = null,
    val netEdgePp: Double? = null,
    val title: String? = null,
    val sizingNote: String,
    val gateNote: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val kind: TicketKind = TicketKind.CONFIGURED,
    /**
     * Non-null when the card is informational only (closed market or empty
     * ask). Approve stays disabled and [TicketSession.approve] will not place.
     */
    val blockedReason: String? = null,
    val reduceOnly: Boolean = false,
    val heldContracts: Int? = null,
    val paperOnly: Boolean = false,
    val feeUsd: Double = 0.0,
    val allInUsd: Double = 0.0
) {
    val isSell: Boolean get() = kind == TicketKind.SELL
    val isBuy: Boolean get() = !isSell
    val isLive: Boolean get() = !paperOnly
    val isManual: Boolean get() = kind == TicketKind.MANUAL
    val isHunter: Boolean get() = kind == TicketKind.HUNTER
    val isHunterValue: Boolean get() = kind == TicketKind.HUNTER_VALUE
    val isWinningStrategy: Boolean get() = kind == TicketKind.WINNING_STRATEGY

    /**
     * True for a buy that would close an opposite position (sell YES to close
     * NO, or sell NO to close YES).
     */
    fun closesOpposite(): Boolean = isSell && heldContracts != null

    /** Copy with a new kind (useful when converting paper to live). */
    fun withKind(newKind: TicketKind): TradeTicket = copy(kind = newKind)

    /** Copy with paper-only flag. */
    fun asPaper(): TradeTicket = copy(paperOnly = true, kind = kind)

    /** Display label for the button / card header. */
    fun label(): String = when (kind) {
        TicketKind.MANUAL -> if (paperOnly) "Paper ${side.uppercase()}" else "LIVE ${side.uppercase()}"
        TicketKind.HUNTER -> "AI ${side.uppercase()}"
        TicketKind.HUNTER_VALUE -> "Long-shot ${side.uppercase()}"
        TicketKind.CONFIGURED -> if (paperOnly) "Paper ${side.uppercase()}" else "LIVE ${side.uppercase()}"
        TicketKind.SELL -> "Sell ${side.uppercase()}"
        TicketKind.WINNING_STRATEGY -> "Strategy ${side.uppercase()}"
    }

    /** One-line description of the ticket source. */
    fun source(): String = when (kind) {
        TicketKind.MANUAL -> if (paperOnly) "Paper" else "Live"
        TicketKind.HUNTER -> "AI Hunter"
        TicketKind.HUNTER_VALUE -> "Long-shot"
        TicketKind.CONFIGURED -> "Configured"
        TicketKind.SELL -> "Sell"
        TicketKind.WINNING_STRATEGY -> "Winning Strategy"
    }
}
