package com.dirk.kalshiodds.signal.trade

enum class TicketKind { CONFIGURED, HUNTER, HUNTER_VALUE, MANUAL, SELL, WINNING_STRATEGY }

/** One proposed (or working) approve-gated limit ticket. */
data class TradeTicket(
    val id: String,
    val ticker: String,
    val side: String,
    val bookSide: String,
    val stakeUsd: Double,
    val limitPrice: Double,
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
    val blockedReason: String? = null,
    val reduceOnly: Boolean = false,
    val heldContracts: Int? = null,
    val closeNote: String? = null,
    val paperOnly: Boolean = false,
    val impliedChance: Double? = null,
    val modelChance: Double? = null,
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
    val bankrollSource: String? = null,
    val bankrollUsd: Double? = null,
    val visibleContracts: Int? = null,
    /** Market close time used by paper time/settlement safeguards. */
    val closeTimeMs: Long? = null
) {
    val displaySide: String get() = side.uppercase()
    val isSell: Boolean get() = kind == TicketKind.SELL
    val isBuy: Boolean get() = !isSell
    val isLive: Boolean get() = !paperOnly
    val isManual: Boolean get() = kind == TicketKind.MANUAL
    val isHunter: Boolean get() = kind == TicketKind.HUNTER
    val isHunterValue: Boolean get() = kind == TicketKind.HUNTER_VALUE
    val isWinningStrategy: Boolean get() = kind == TicketKind.WINNING_STRATEGY
    val isManualOrSell: Boolean get() = isManual || isSell

    fun closesOpposite(): Boolean = isSell && heldContracts != null
    fun withKind(newKind: TicketKind): TradeTicket = copy(kind = newKind)
    fun asPaper(): TradeTicket = copy(paperOnly = true)
    val potentialGainUsd: Double get() = (maxPayoutUsd - stakeUsd).coerceAtLeast(0.0)
    val canApprove: Boolean get() = blockedReason.isNullOrBlank() && contracts > 0 && !paperOnly
    val canPaper: Boolean get() = blockedReason.isNullOrBlank() && contracts > 0
    fun matchesApproval(ticketId: String): Boolean = ticketId == id

    fun label(): String = when (kind) {
        TicketKind.MANUAL -> if (paperOnly) "Paper ${side.uppercase()}" else "LIVE ${side.uppercase()}"
        TicketKind.HUNTER -> "AI ${side.uppercase()}"
        TicketKind.HUNTER_VALUE -> "Long-shot ${side.uppercase()}"
        TicketKind.CONFIGURED -> if (paperOnly) "Paper ${side.uppercase()}" else "LIVE ${side.uppercase()}"
        TicketKind.SELL -> "Sell ${side.uppercase()}"
        TicketKind.WINNING_STRATEGY -> "Strategy ${side.uppercase()}"
    }

    fun source(): String = when (kind) {
        TicketKind.MANUAL -> if (paperOnly) "Paper" else "Live"
        TicketKind.HUNTER -> "AI Hunter"
        TicketKind.HUNTER_VALUE -> "Long-shot"
        TicketKind.CONFIGURED -> "Configured"
        TicketKind.SELL -> "Sell"
        TicketKind.WINNING_STRATEGY -> "Winning Strategy"
    }
}

sealed class TicketPhase {
    data object Idle : TicketPhase()
    data class Proposed(val tickets: List<TradeTicket>) : TicketPhase()
    data class AwaitingApprove(
        val ticket: TradeTicket,
        val others: List<TradeTicket> = emptyList(),
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
            Idle, is Submitting -> emptyList()
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
