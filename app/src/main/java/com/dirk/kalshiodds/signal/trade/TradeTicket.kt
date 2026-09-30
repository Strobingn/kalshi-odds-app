package com.dirk.kalshiodds.signal.trade

enum class TicketKind {
    /** Settings stake (default $10) → ≥$100 max payout. */
    CONFIGURED,
    /** Last-minute strategy fire — one per window. */
    LAST_MINUTE,
    /** D3 daily 5 PM favourite — post-only maker bid. */
    D3,
    /** Automatic hunter: $1 stake → ≥$25 max payout. */
    HUNTER,
    /** Long-shot hunter: ask ≤ ~20¢ and AI/fair beats implied after fees. */
    HUNTER_VALUE,
    /** User tapped Buy on a market card / hero. */
    MANUAL,
    /** Sell / reduce a held YES or NO position. IoC reduce-only at the bid. */
    SELL
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
     * V2 single-book side. `bid` = buy YES; `ask` = sell YES (= buy NO at 1−p).
     */
    val bookSide: String,
    val stakeUsd: Double,
    /** Price paid per chosen-side contract (0.001–0.999, including sub-cent). */
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
    /**
     * Documented V2 `reduce_only` — cap the order at the current position.
     * Official schema requires `time_in_force=immediate_or_cancel` with this flag.
     */
    val reduceOnly: Boolean = false,
    val heldContracts: Int? = null,
    /** Shown when a buy would net against an existing position. */
    val closeNote: String? = null,
    /** Paper-originated sell — never sent to Kalshi. */
    val paperOnly: Boolean = false,
    val impliedChance: Double? = null,
    val modelChance: Double? = null,
    /** Model confidence 0–1 from [com.dirk.kalshiodds.domain.MarketUiModel.aiConfidence]. */
    val modelConfidence: Double? = null,
    val fairChance: Double? = null,
    val modelEdge: Boolean = false,
    val profitIfWinUsd: Double? = null,
    val feeUsd: Double? = null,
    val allInUsd: Double? = null,
    val belowMinProfit: Boolean = false,
    val minProfitIfWinUsd: Double? = null,
    val winTargetUsd: Double? = null,
    val winTargetCapped: Boolean = false,
    val winTargetNote: String? = null,
    /** `live` = Kalshi cash, `paper` = paper equity, `settings` = advisory bankroll. */
    val bankrollSource: String? = null,
    val bankrollUsd: Double? = null,
    /** Visible contracts at/under the limit (book or quoted size). */
    val visibleContracts: Int? = null,
    /** V2 `post_only` — D3 resting maker bids. */
    val postOnly: Boolean = false
) {
    val displaySide: String get() = side.uppercase()

    val isSell: Boolean get() = kind == TicketKind.SELL

    val potentialGainUsd: Double get() = (maxPayoutUsd - stakeUsd).coerceAtLeast(0.0)

    val canApprove: Boolean get() = blockedReason.isNullOrBlank() && contracts > 0 && !paperOnly

    val canPaper: Boolean get() = blockedReason.isNullOrBlank() && contracts > 0

    fun matchesApproval(ticketId: String): Boolean = ticketId == id
}

data class PlacedOrder(
    val ticket: TradeTicket,
    val clientOrderId: String,
    val orderId: String?,
    val fillCount: Double,
    val remainingCount: Double,
    val averageFillPrice: Double?,
    val placedAtMs: Long,
    val error: String? = null
) {
    val filledContracts: Int
        get() = kotlin.math.floor(fillCount + 1e-9).toInt().coerceAtLeast(0)

    val unfilledContracts: Int
        get() = (ticket.contracts - filledContracts).coerceAtLeast(0)

    /** IoC reduce-only sells never rest — leftover size is canceled. */
    val isResting: Boolean
        get() = !ticket.isSell && remainingCount > 1e-9 && orderId != null

    fun fillSummary(): String {
        val wanted = ticket.contracts
        val filled = filledContracts
        val leftover = unfilledContracts
        return when {
            filled <= 0 ->
                "Sold 0 of $wanted contracts. Nothing filled — no leftover order."
            leftover <= 0 ->
                "Sold $filled of $wanted contracts."
            else ->
                "Sold $filled of $wanted contracts. $leftover didn't fill — no leftover order."
        }
    }
}

sealed class TicketPhase {
    data object Idle : TicketPhase()

    data class Proposed(val tickets: List<TradeTicket>) : TicketPhase()

    data class AwaitingApprove(
        val ticket: TradeTicket,
        val others: List<TradeTicket> = emptyList(),
        /** One client_order_id for this confirm sheet — reused on double-tap. */
        val clientOrderId: String = ""
    ) : TicketPhase()

    data class Submitting(val ticket: TradeTicket, val clientOrderId: String) : TicketPhase()

    data class Submitted(val order: PlacedOrder, val proposals: List<TradeTicket> = emptyList()) : TicketPhase()

    data class Failed(val ticket: TradeTicket, val error: String, val proposals: List<TradeTicket> = emptyList()) : TicketPhase()

    data class Cancelled(val order: PlacedOrder, val proposals: List<TradeTicket> = emptyList()) : TicketPhase()

    val proposedTickets: List<TradeTicket>
        get() = when (this) {
            is Proposed -> tickets
            is AwaitingApprove -> listOf(ticket) + others
            is Submitted -> proposals
            is Failed -> proposals
            is Cancelled -> proposals
            else -> emptyList()
        }

    val workingOrder: PlacedOrder?
        get() = when (this) {
            is Submitted -> order
            is Cancelled -> order
            else -> null
        }
}

data class TicketUiState(
    val phase: TicketPhase = TicketPhase.Idle,
    val proposals: List<TradeTicket> = emptyList(),
    val working: List<PlacedOrder> = emptyList(),
    val lastError: String? = null,
    val placementCount: Int = 0
)
