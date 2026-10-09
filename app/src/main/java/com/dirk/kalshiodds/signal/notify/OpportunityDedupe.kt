package com.dirk.kalshiodds.signal.notify

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket

/**
 * Per-market rate limit for long-shot / hunter notifications.
 * Notifications may open a ticket; they never place an order.
 */
object OpportunityDedupe {

    data class Key(val ticker: String, val kind: String)

    fun keyOf(ticket: TradeTicket): Key =
        Key(ticket.ticker.uppercase(), kindLabel(ticket.kind))

    fun kindLabel(kind: TicketKind): String = when (kind) {
        TicketKind.HUNTER_VALUE -> "longshot"
        TicketKind.HUNTER -> "hunter"
        TicketKind.CONFIGURED -> "wintarget"
        TicketKind.MANUAL -> "manual"
        TicketKind.SELL -> "sell"
        TicketKind.SCALP -> "scalp"
    }

    fun isOpportunity(ticket: TradeTicket): Boolean {
        if (!ticket.blockedReason.isNullOrBlank()) return false
        if (ticket.contracts <= 0) return false
        return ticket.kind == TicketKind.HUNTER_VALUE ||
            ticket.kind == TicketKind.HUNTER ||
            (ticket.winTargetUsd != null && ticket.kind != TicketKind.SELL)
    }

    fun shouldNotify(
        ticker: String,
        kind: String,
        nowMs: Long,
        lastPostedMs: Map<Key, Long>,
        minIntervalMs: Long = SignalConstants.OPPORTUNITY_DEDUPE_MS
    ): Boolean {
        if (ticker.isBlank()) return false
        val last = lastPostedMs[Key(ticker.uppercase(), kind)] ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun shouldNotify(
        ticket: TradeTicket,
        nowMs: Long,
        lastPostedMs: Map<Key, Long>,
        minIntervalMs: Long = SignalConstants.OPPORTUNITY_DEDUPE_MS
    ): Boolean {
        if (!isOpportunity(ticket)) return false
        return shouldNotify(ticket.ticker, kindLabel(ticket.kind), nowMs, lastPostedMs, minIntervalMs)
    }
}
