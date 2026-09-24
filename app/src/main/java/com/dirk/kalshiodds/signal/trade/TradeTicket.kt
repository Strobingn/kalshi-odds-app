package com.dirk.kalshiodds.signal.trade

enum class TicketKind {
    /** Settings stake (default $5) → ≥$100 max payout. */
    CONFIGURED,
    /** Automatic hunter: $1 stake → ≥$25 max payout. */
    HUNTER,
    /** User tapped Buy on a market card / hero. */
    MANUAL,
    /** Sell / reduce a held YES or NO position. Reduce-only V2 limit. */
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
    /** Price paid per chosen-side contract (0.01–0.99). */
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
    /** Documented V2 `reduce_only` — cap the order at the current position. */
    val reduceOnly: Boolean = false,
    val heldContracts: Int? = null,
    /** Shown when a buy would net against an existing position. */
    val closeNote: String? = null,
    /** Paper-originated sell — never sent to Kalshi. */
    val paperOnly: Boolean = false
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
)

sealed class TicketPhase {
    data object Idle : TicketPhase()

    data class Proposed(val tickets: List<TradeTicket>) : TicketPhase()

    data class AwaitingApprove(val ticket: TradeTicket, val others: List<TradeTicket>) : TicketPhase()

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
