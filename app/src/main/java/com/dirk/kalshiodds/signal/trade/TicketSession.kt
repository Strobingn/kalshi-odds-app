package com.dirk.kalshiodds.signal.trade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Approve-gated ticket state machine.
 *
 * **Non-negotiable:** [placeOrder] is invoked only from [approve] after an
 * explicit in-app Approve for **that** ticket id. [onStart], [replaceProposals],
 * [openApprove], [dismiss], and [failSoft] never submit.
 *
 * There is no background auto-fire, no set-and-forget loop, and no order
 * on process start ([TicketPhase.Idle]).
 */
class TicketSession(
    private val placeOrder: suspend (ticket: TradeTicket, clientOrderId: String) -> Result<PlacedOrder>,
    private val cancelOrder: suspend (order: PlacedOrder) -> Result<PlacedOrder> = { Result.success(it) },
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val onAttempt: ((com.dirk.kalshiodds.data.local.results.TicketAttemptRow) -> Unit)? = null
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TicketUiState())
    val state: StateFlow<TicketUiState> = _state.asStateFlow()

    fun snapshot(): TicketUiState = _state.value

    /** Process start / ViewModel init. Leaves Idle. Never places. */
    fun onStart() {
        _state.value = TicketUiState(phase = TicketPhase.Idle)
    }

    /**
     * Refresh proposed tickets from the latest scan. Leaves submitting /
     * submitted / working orders alone. Never places.
     *
     * [liveTickers]: when set, drop hunter / manual cards whose ticker is
     * no longer tradable (expired 15m window). Incoming [tickets] may include
     * remapped manuals for the current live window.
     */
    fun replaceProposals(tickets: List<TradeTicket>, liveTickers: Set<String>? = null) {
        _state.update { cur ->
            val incomingManuals = tickets.filter { it.kind == TicketKind.MANUAL || it.kind == TicketKind.SELL }
            val incomingAuto = tickets.filterNot { it.kind == TicketKind.MANUAL || it.kind == TicketKind.SELL }
            val preservedManuals = cur.proposals.filter { t ->
                (t.kind == TicketKind.MANUAL || t.kind == TicketKind.SELL) &&
                    incomingManuals.none { n -> ticketKey(n) == ticketKey(t) } &&
                    (liveTickers == null || t.ticker in liveTickers)
            }
            val manuals = incomingManuals + preservedManuals
            val remapped = preserveIds(
                cur.proposals.filterNot { it.kind == TicketKind.MANUAL || it.kind == TicketKind.SELL },
                incomingAuto
            )
                .filter { liveTickers == null || it.ticker in liveTickers }
            val merged = (manuals + remapped).distinctBy { ticketKey(it) }
            val cleanedError = cur.lastError?.takeUnless { stalePageError(it) }
            if (cur.phase is TicketPhase.Submitting) {
                return@update cur.copy(proposals = merged, lastError = cleanedError)
            }
            val awaiting = (cur.phase as? TicketPhase.AwaitingApprove)?.ticket
            val liveAwaiting = awaiting?.takeIf { ticket ->
                (liveTickers == null || ticket.ticker in liveTickers) &&
                    merged.any { it.id == ticket.id || ticketKey(it) == ticketKey(ticket) }
            }
            val phase = when {
                liveAwaiting != null && merged.any { it.id == liveAwaiting.id } -> {
                    val fresh = merged.first { it.id == liveAwaiting.id }
                    TicketPhase.AwaitingApprove(fresh, merged.filterNot { it.id == fresh.id })
                }
                liveAwaiting != null &&
                    (liveAwaiting.kind == TicketKind.MANUAL || liveAwaiting.kind == TicketKind.SELL) -> {
                    val fresh = merged.firstOrNull { ticketKey(it) == ticketKey(liveAwaiting) } ?: liveAwaiting
                    TicketPhase.AwaitingApprove(fresh, merged.filterNot { it.id == fresh.id })
                }
                merged.isEmpty() -> TicketPhase.Idle
                else -> TicketPhase.Proposed(merged)
            }
            cur.copy(phase = phase, proposals = merged, lastError = cleanedError)
        }
    }

    private fun preserveIds(existing: List<TradeTicket>, incoming: List<TradeTicket>): List<TradeTicket> {
        val byKey = existing.associateBy { ticketKey(it) }
        return incoming.map { t ->
            val old = byKey[ticketKey(t)]
            if (old != null) t.copy(id = old.id) else t
        }
    }

    /**
     * User tapped Buy. Queues the ticket and opens the confirm sheet.
     * Never places — only [approve] may call the trade client.
     */
    fun addManual(ticket: TradeTicket) {
        _state.update { cur ->
            val others = cur.proposals.filterNot { ticketKey(it) == ticketKey(ticket) }
            val next = listOf(ticket) + others
            cur.copy(
                phase = TicketPhase.AwaitingApprove(ticket, others),
                proposals = next,
                lastError = null
            )
        }
    }

    /**
     * Update a proposed / awaiting ticket (sell count/price) without placing.
     */
    fun revise(ticketId: String, transform: (TradeTicket) -> TradeTicket): Boolean {
        var changed = false
        _state.update { cur ->
            val found = cur.proposals.firstOrNull { it.id == ticketId } ?: return@update cur
            val nextTicket = transform(found)
            if (nextTicket == found) return@update cur
            changed = true
            val next = cur.proposals.map { if (it.id == ticketId) nextTicket else it }
            val phase = when (val p = cur.phase) {
                is TicketPhase.AwaitingApprove ->
                    if (p.ticket.id == ticketId) {
                        TicketPhase.AwaitingApprove(nextTicket, next.filterNot { it.id == ticketId })
                    } else {
                        p
                    }
                else -> p
            }
            cur.copy(proposals = next, phase = phase)
        }
        return changed
    }

    fun dismiss(ticketId: String) {
        _state.update { cur ->
            val next = cur.proposals.filterNot { it.id == ticketId }
            val phase = if (next.isEmpty()) TicketPhase.Idle else TicketPhase.Proposed(next)
            cur.copy(phase = phase, proposals = next)
        }
    }

    /** Open the confirm sheet. Does **not** place. */
    fun openApprove(ticketId: String): Boolean {
        val ticket = _state.value.proposals.firstOrNull { it.id == ticketId } ?: return false
        val others = _state.value.proposals.filterNot { it.id == ticketId }
        _state.update {
            it.copy(phase = TicketPhase.AwaitingApprove(ticket, others), lastError = null)
        }
        return true
    }

    fun cancelApprove() {
        _state.update { cur ->
            val tickets = cur.proposals
            cur.copy(
                phase = if (tickets.isEmpty()) TicketPhase.Idle else TicketPhase.Proposed(tickets)
            )
        }
    }

    /**
     * The only entry that may call [placeOrder]. Requires [ticketId] to match
     * a currently proposed / awaiting ticket. Wrong id, Idle, or already
     * submitting → no-op, no placement.
     */
    suspend fun approve(ticketId: String): TicketUiState = mutex.withLock {
        val cur = _state.value
        val ticket = when (val p = cur.phase) {
            is TicketPhase.AwaitingApprove -> p.ticket.takeIf { it.matchesApproval(ticketId) }
            is TicketPhase.Proposed -> p.tickets.firstOrNull { it.matchesApproval(ticketId) }
            else -> null
        } ?: run {
            _state.update {
                it.copy(lastError = "Approve ignored — no matching ticket (orders are never auto-fired)")
            }
            return _state.value
        }
        if (!ticket.canApprove) {
            val reason = ticket.blockedReason?.takeIf { it.isNotBlank() }
                ?: "Ticket cannot be approved — Approve stays off (no silent skip)"
            _state.update { it.copy(lastError = reason) }
            return _state.value
        }
        if (cur.phase is TicketPhase.Submitting) return cur

        val clientOrderId = idFactory()
        _state.update {
            it.copy(
                phase = TicketPhase.Submitting(ticket, clientOrderId),
                lastError = null
            )
        }
        val result = runCatching { placeOrder(ticket, clientOrderId) }.getOrElse { Result.failure(it) }
        runCatching {
            onAttempt?.invoke(
                com.dirk.kalshiodds.data.local.results.TicketAttemptRow(
                    ticker = ticket.ticker,
                    side = ticket.side,
                    stakeUsd = ticket.stakeUsd,
                    approved = true,
                    result = result.fold(
                        onSuccess = { ack -> ack.error ?: ack.orderId ?: "submitted" },
                        onFailure = { err -> humanError(err) }
                    ),
                    createdAtMs = System.currentTimeMillis(),
                    clientOrderId = clientOrderId,
                    note = "Approve-gated — never unsupervised"
                )
            )
        }
        val next = result.fold(
            onSuccess = { ack ->
                val working = cur.working + ack
                TicketUiState(
                    phase = TicketPhase.Submitted(ack, cur.proposals.filterNot { it.id == ticket.id }),
                    proposals = cur.proposals.filterNot { it.id == ticket.id },
                    working = working,
                    lastError = ack.error,
                    placementCount = cur.placementCount + 1
                )
            },
            onFailure = { err ->
                val msg = humanError(err)
                TicketUiState(
                    phase = TicketPhase.Failed(ticket, msg, cur.proposals),
                    proposals = cur.proposals,
                    working = cur.working,
                    lastError = msg,
                    placementCount = cur.placementCount
                )
            }
        )
        _state.value = next
        return next
    }

    suspend fun cancelWorking(orderId: String): TicketUiState = mutex.withLock {
        val cur = _state.value
        val order = cur.working.firstOrNull { it.orderId == orderId } ?: return cur
        val result = runCatching { cancelOrder(order) }.getOrElse { Result.failure(it) }
        result.fold(
            onSuccess = { updated ->
                _state.update {
                    it.copy(
                        phase = TicketPhase.Cancelled(updated, it.proposals),
                        working = it.working.filterNot { w -> w.orderId == orderId } + updated.copy(
                            error = updated.error ?: "cancelled"
                        ),
                        lastError = null
                    )
                }
            },
            onFailure = { err ->
                _state.update { it.copy(lastError = humanError(err)) }
            }
        )
        return _state.value
    }

    fun failSoft(message: String) {
        _state.update { it.copy(lastError = message) }
    }

    val placementCount: Int get() = _state.value.placementCount

    companion object {
        fun ticketKey(t: TradeTicket): String =
            "${t.ticker}|${t.side}|${t.kind}|${t.stakeUsd}"

        fun stalePageError(message: String): Boolean {
            val lower = message.lowercase()
            return lower.startsWith("no ask to size") ||
                lower.contains("no ask to size a limit")
        }

        fun humanError(err: Throwable): String {
            val raw = err.message?.trim().orEmpty()
            if (raw.isBlank()) return "Order failed — try again or check Settings keys"
            val lower = raw.lowercase()
            // Never echo PEM / key material if a caller leaked it into a message.
            if (lower.contains("begin") && lower.contains("private")) {
                return "Order failed — credential error (secrets not logged)"
            }
            if (lower.contains("deprecated_v1_order_endpoint") ||
                (lower.contains("http 410") && lower.contains("v2"))
            ) {
                return raw.take(240)
            }
            return LastOrderErrorStore.redact(raw).take(2_000)
        }
    }
}
