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
    private val onAttempt: ((com.dirk.kalshiodds.data.local.results.TicketAttemptRow) -> Unit)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val voidHoldMs: Long = VOID_HOLD_MS
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TicketUiState())
    val state: StateFlow<TicketUiState> = _state.asStateFlow()

    private val voidedAtMs = mutableMapOf<String, Long>()
    private val announcedVoidIds = mutableSetOf<String>()

    /** Times [WINDOW_CLOSED_NOTICE] was raised for a newly voided ticket. */
    var windowClosedNoticeCount: Int = 0
        private set

    fun snapshot(): TicketUiState = _state.value

    /** Process start / ViewModel init. Leaves Idle. Never places. */
    fun onStart() {
        _state.value = TicketUiState(phase = TicketPhase.Idle)
        voidedAtMs.clear()
        announcedVoidIds.clear()
        windowClosedNoticeCount = 0
    }

    /**
     * Refresh proposed tickets from the latest scan. Leaves submitting /
     * submitted / working orders alone. Never places.
     *
     * [liveTickers]: when set, hunter cards on a closed window are dropped
     * and manual / sell cards are voided with [WINDOW_CLOSED] — never remapped
     * onto the next contract and never submitted. A voided manual / sell is
     * kept briefly so Approve stays off and a one-time notice can show, then
     * removed on the next refresh after [voidHoldMs] (default 5s).
     */
    fun replaceProposals(tickets: List<TradeTicket>, liveTickers: Set<String>? = null) {
        _state.update { cur ->
            val now = nowMs()
            val submitting = cur.phase as? TicketPhase.Submitting
            val incomingManuals = tickets.filter { it.isManualOrSell }
            val incomingAuto = tickets.filterNot { it.isManualOrSell }
            val preservedManuals = cur.proposals.filter { t ->
                t.isManualOrSell &&
                    incomingManuals.none { n -> ticketKey(n) == ticketKey(t) } &&
                    submitting?.ticket?.id != t.id
            }.map { markClosed(it, liveTickers, now) }
            val manuals = incomingManuals.map { markClosed(it, liveTickers, now) } + preservedManuals
            val remapped = preserveIds(
                cur.proposals.filterNot { it.isManualOrSell },
                incomingAuto
            )
                .map { markClosed(it, liveTickers, now) }
                .filter { liveTickers == null || it.ticker in liveTickers }
            val keptManuals = manuals.filterNot { dropVoided(it, now) }
            val merged = (keptManuals + remapped)
                .filterNot { dropVoided(it, now) }
                .distinctBy { ticketKey(it) }
            forgetDropped(cur.proposals, merged)
            val lastError = nextLastError(cur, merged, submitting != null)
            if (submitting != null) {
                return@update cur.copy(
                    proposals = keepSubmitting(merged, submitting.ticket),
                    lastError = cur.lastError
                )
            }
            val awaitingPhase = cur.phase as? TicketPhase.AwaitingApprove
            val awaiting = awaitingPhase?.ticket
            val confirmId = awaitingPhase?.clientOrderId
            val liveAwaiting = awaiting?.takeIf { ticket ->
                !isWindowClosedReason(ticket.blockedReason) &&
                    (liveTickers == null || ticket.ticker in liveTickers) &&
                    merged.any { it.id == ticket.id || ticketKey(it) == ticketKey(ticket) }
            }
            val phase = when {
                liveAwaiting != null && merged.any { it.id == liveAwaiting.id } -> {
                    val fresh = merged.first { it.id == liveAwaiting.id }
                    confirmPhase(fresh, merged.filterNot { it.id == fresh.id }, confirmId)
                }
                liveAwaiting != null && liveAwaiting.isManualOrSell -> {
                    val fresh = merged.firstOrNull { ticketKey(it) == ticketKey(liveAwaiting) } ?: liveAwaiting
                    confirmPhase(fresh, merged.filterNot { it.id == fresh.id }, confirmId)
                }
                merged.isEmpty() -> TicketPhase.Idle
                else -> TicketPhase.Proposed(merged)
            }
            cur.copy(
                phase = phase,
                proposals = merged,
                lastError = lastError
            )
        }
    }

    /**
     * Mark open manual / sell tickets on [tickers] as [WINDOW_CLOSED]. Approve
     * stays off; [approve] will not place. Already-submitting orders are left
     * alone. Already-voided tickets are not re-announced; they drop after
     * [voidHoldMs] on the next [replaceProposals].
     */
    fun voidTickers(tickers: Set<String>, reason: String = WINDOW_CLOSED) {
        if (tickers.isEmpty()) return
        _state.update { cur ->
            val now = nowMs()
            val submitting = cur.phase as? TicketPhase.Submitting
            val submittingId = submitting?.ticket?.id
            fun mark(t: TradeTicket): TradeTicket {
                if (t.id == submittingId) return t
                if (!t.isManualOrSell) return t
                if (t.ticker !in tickers) return t
                return markVoided(t, reason, now)
            }
            val proposals = cur.proposals.map(::mark).filterNot { dropVoided(it, now) && it.id != submittingId }
            forgetDropped(cur.proposals, proposals)
            val phase = when (val p = cur.phase) {
                is TicketPhase.AwaitingApprove -> {
                    val ticket = mark(p.ticket)
                    val others = p.others.map(::mark).filterNot { dropVoided(it, now) }
                    if (p.ticket.ticker in tickers && p.ticket.isManualOrSell) {
                        val next = (listOf(ticket) + others).filterNot { dropVoided(it, now) }
                            .distinctBy { ticketKey(it) }
                        if (next.isEmpty()) TicketPhase.Idle else TicketPhase.Proposed(next)
                    } else {
                        confirmPhase(ticket, others, p.clientOrderId)
                    }
                }
                is TicketPhase.Proposed -> {
                    if (proposals.isEmpty()) TicketPhase.Idle else TicketPhase.Proposed(proposals)
                }
                is TicketPhase.Submitting -> p
                else -> p
            }
            val lastError = if (submitting != null) {
                cur.lastError
            } else {
                nextLastError(cur.copy(proposals = proposals), proposals, submitting = false)
            }
            cur.copy(
                phase = phase,
                proposals = if (submitting != null) keepSubmitting(proposals, submitting.ticket) else proposals,
                lastError = lastError
            )
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
            val others = cur.proposals.filterNot {
                ticketKey(it) == ticketKey(ticket) || isWindowClosedReason(it.blockedReason)
            }
            val next = listOf(ticket) + others
            cur.copy(
                phase = confirmPhase(ticket, others),
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
                        confirmPhase(nextTicket, next.filterNot { it.id == ticketId }, p.clientOrderId)
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
            voidedAtMs.remove(ticketId)
            announcedVoidIds.remove(ticketId)
            val phase = if (next.isEmpty()) TicketPhase.Idle else TicketPhase.Proposed(next)
            cur.copy(phase = phase, proposals = next)
        }
    }

    /** Open the confirm sheet. Does **not** place. */
    fun openApprove(ticketId: String): Boolean {
        val ticket = _state.value.proposals.firstOrNull { it.id == ticketId } ?: return false
        val others = _state.value.proposals.filterNot { it.id == ticketId }
        _state.update {
            it.copy(phase = confirmPhase(ticket, others), lastError = null)
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
            val reason = when {
                isWindowClosedReason(ticket.blockedReason) -> WINDOW_CLOSED_NOTICE
                else -> ticket.blockedReason?.takeIf { it.isNotBlank() }
                    ?: "Ticket cannot be approved — Approve stays off (no silent skip)"
            }
            _state.update { it.copy(lastError = reason) }
            return _state.value
        }
        if (cur.phase is TicketPhase.Submitting) return cur

        val clientOrderId = (cur.phase as? TicketPhase.AwaitingApprove)
            ?.clientOrderId
            ?.takeIf { it.isNotBlank() }
            ?: idFactory()
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

    private fun confirmPhase(
        ticket: TradeTicket,
        others: List<TradeTicket>,
        existingId: String? = null
    ): TicketPhase.AwaitingApprove {
        val id = existingId?.takeIf { it.isNotBlank() } ?: idFactory()
        return TicketPhase.AwaitingApprove(ticket, others, id)
    }

    fun failSoft(message: String) {
        _state.update { it.copy(lastError = message) }
    }

    val placementCount: Int get() = _state.value.placementCount

    private fun markClosed(ticket: TradeTicket, liveTickers: Set<String>?, now: Long): TradeTicket {
        if (liveTickers == null || ticket.ticker in liveTickers) return ticket
        if (!ticket.isManualOrSell) return ticket
        return markVoided(ticket, WINDOW_CLOSED, now)
    }

    private fun markVoided(ticket: TradeTicket, reason: String, now: Long): TradeTicket {
        if (ticket.id !in voidedAtMs) voidedAtMs[ticket.id] = now
        if (ticket.blockedReason == reason) {
            return ticket.copy(
                gateNote = ticket.gateNote ?: WINDOW_CLOSED_NOTICE,
                sizingNote = if (isWindowClosedReason(ticket.sizingNote)) WINDOW_CLOSED_NOTICE else ticket.sizingNote
            )
        }
        return ticket.copy(
            blockedReason = reason,
            gateNote = WINDOW_CLOSED_NOTICE,
            sizingNote = WINDOW_CLOSED_NOTICE
        )
    }

    private fun dropVoided(ticket: TradeTicket, now: Long): Boolean {
        if (!ticket.isManualOrSell) return false
        if (!isWindowClosedReason(ticket.blockedReason)) return false
        val at = voidedAtMs[ticket.id] ?: return false
        return now - at >= voidHoldMs
    }

    private fun forgetDropped(before: List<TradeTicket>, after: List<TradeTicket>) {
        val keep = after.map { it.id }.toSet()
        before.map { it.id }.filter { it !in keep }.forEach { id ->
            voidedAtMs.remove(id)
        }
    }

    private fun keepSubmitting(merged: List<TradeTicket>, submitting: TradeTicket): List<TradeTicket> {
        val without = merged.filterNot { it.id == submitting.id }
        return (listOf(submitting) + without).distinctBy { ticketKey(it) }
    }

    private fun nextLastError(
        cur: TicketUiState,
        merged: List<TradeTicket>,
        submitting: Boolean
    ): String? {
        if (submitting) return cur.lastError
        val cleaned = cur.lastError?.takeUnless { stalePageError(it) }
        val newly = merged.filter { ticket ->
            isWindowClosedReason(ticket.blockedReason) && ticket.id !in announcedVoidIds
        }
        if (newly.isNotEmpty()) {
            announcedVoidIds.addAll(newly.map { it.id })
            windowClosedNoticeCount += newly.size
            return WINDOW_CLOSED_NOTICE
        }
        val stillHolding = merged.any { isWindowClosedReason(it.blockedReason) }
        if (stillHolding && isWindowClosedError(cleaned)) return WINDOW_CLOSED_NOTICE
        return cleaned?.takeUnless { isWindowClosedError(it) }
    }

    companion object {
        const val WINDOW_CLOSED = TicketBuilder.WINDOW_CLOSED
        const val WINDOW_CLOSED_NOTICE = "That window closed. Nothing was sent."
        const val VOID_HOLD_MS = 5_000L

        fun ticketKey(t: TradeTicket): String =
            "${t.ticker}|${t.side}|${t.kind}|${t.stakeUsd}"

        fun isWindowClosedReason(reason: String?): Boolean =
            reason == WINDOW_CLOSED || reason == WINDOW_CLOSED_NOTICE

        fun isWindowClosedError(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return isWindowClosedReason(message.trim())
        }

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
            return LastOrderErrorStore.redact(raw).take(4_000)
        }
    }
}

private val TradeTicket.isManualOrSell: Boolean
    get() = kind == TicketKind.MANUAL || kind == TicketKind.SELL
