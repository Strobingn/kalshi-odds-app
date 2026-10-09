package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.ui.HomeMarkets

/**
 * Dedupes ticket-session errors before they are written to
 * [LastOrderErrorStore]. Window / market lifecycle notices are never
 * order errors — only real placement or Test-connection failures persist.
 */
object LastOrderErrorOnce {

    fun isNotAnOrderError(message: String?): Boolean {
        val err = message?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (TicketSession.isWindowClosedError(err)) return true
        if (err.equals(TicketBuilder.WINDOW_CLOSED, ignoreCase = true)) return true
        if (err.equals(TicketBuilder.MARKET_CLOSED, ignoreCase = true)) return true
        if (err.equals(HomeMarkets.NEXT_WINDOW_LOADING, ignoreCase = true)) return true
        return false
    }

    /** Persisted Settings value from an older build that must be wiped on start. */
    fun shouldClearPersisted(message: String?): Boolean {
        val err = message?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return err == TicketBuilder.WINDOW_CLOSED || err == TicketSession.WINDOW_CLOSED_NOTICE
    }

    fun accept(previous: String?, incoming: String?): String? {
        val err = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (err.startsWith("PAPER ", ignoreCase = true)) return null
        if (isNotAnOrderError(err)) return null
        if (err == previous) return null
        return err
    }
}
