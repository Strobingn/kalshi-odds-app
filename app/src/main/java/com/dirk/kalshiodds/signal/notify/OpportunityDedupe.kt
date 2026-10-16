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
        TicketKind.LAST_MINUTE -> "last_minute"
        TicketKind.D3 -> "d3"
        TicketKind.SELL -> "sell"
    }

    fun isOpportunity(ticket: TradeTicket): Boolean {
        if (!ticket.blockedReason.isNullOrBlank()) return false
        if (ticket.contracts <= 0) return false
        return ticket.kind == TicketKind.HUNTER_VALUE ||
            ticket.kind == TicketKind.HUNTER ||
            ticket.kind == TicketKind.LAST_MINUTE ||
            ticket.kind == TicketKind.D3 ||
            (ticket.winTargetUsd != null && ticket.kind != TicketKind.SELL && ticket.kind != TicketKind.LAST_MINUTE)
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
