package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Shadow orders that were built and never submitted.
 * Settled P&L counts only tickets the book could have filled at the
 * intended limit. Unfilled rows stay at zero P&L.
 */
@Serializable
data class ShadowTicket(
    val id: String,
    val ticker: String,
    val side: String,
    val action: String = "buy",
    val bookSide: String,
    val count: Int,
    val limitPrice: Double,
    val yesLimitPrice: Double,
    val clientOrderId: String,
    val createdAtMs: Long,
    val reason: String,
    val payloadJson: String,
    /** Ask size covers [count] at the limit. */
    val depthFill: Boolean = false,
    /** Depth fill and the shadow bankroll can pay the all-in cost. */
    val booked: Boolean = false,
    val unfilledReason: String? = null,
    val stakeUsd: Double = 0.0,
    val feeUsd: Double = 0.0,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null,
    val regimeKey: String? = null,
    val liveSubmitted: Boolean = false
) {
    fun summary(): String {
        val sideLabel = if (side.equals("NO", true)) "DOWN" else "UP"
        val fill = when {
            booked -> "would fill"
            else -> "unfilled"
        }
        return String.format(
            java.util.Locale.US,
            "SHADOW — not submitted · %s %s · %d ct @ %.0f¢ · %s · %s",
            sideLabel,
            ticker,
            count,
            limitPrice * 100.0,
            fill,
            reason
        )
    }
}

@Serializable
data class ShadowBookState(
    val startingUsd: Double = SignalConstants.PAPER_START_USD,
    val cashUsd: Double = SignalConstants.PAPER_START_USD,
    val tickets: List<ShadowTicket> = emptyList(),
    val lifetimeRealizedPnlUsd: Double = 0.0,
    val lastMessage: String? = null,
    val liveSpentUsd: Double = 0.0,
    val liveDayKey: String = "",
    val liveAttemptedIds: List<String> = emptyList(),
    val liveLastError: String? = null
) {
    val bankrollUsd: Double get() = startingUsd + lifetimeRealizedPnlUsd

    fun spentOn(dayKey: String): Double =
        if (liveDayKey == dayKey) liveSpentUsd else 0.0

    fun attempted(clientOrderId: String): Boolean = clientOrderId in liveAttemptedIds
}

class ShadowBook(
    initial: ShadowBookState = ShadowBookState(),
    private val persist: (ShadowBookState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ShadowBookState> = _state.asStateFlow()

    fun snapshot(): ShadowBookState = _state.value

    fun openTickers(): Set<String> = synchronized(lock) {
        _state.value.tickets.filter { !it.settled }.map { it.ticker.uppercase() }.toSet()
    }

    data class Recorded(val ticket: ShadowTicket, val isNew: Boolean)

    /**
     * Keep one open shadow ticket per ticker + side + price band.
     * A repeat tick returns the existing row and does not invent another fill.
     */
    fun record(incoming: ShadowTicket): Recorded = synchronized(lock) {
        val cur = _state.value
        val hit = cur.tickets.firstOrNull {
            !it.settled &&
                it.ticker.equals(incoming.ticker, ignoreCase = true) &&
                it.side.equals(incoming.side, ignoreCase = true) &&
                abs(it.limitPrice - incoming.limitPrice) < PaperAutopilot.PRICE_DELTA
        }
        if (hit != null) return Recorded(hit, false)
        val booked = incoming.booked && incoming.depthFill && incoming.stakeUsd > 0.0
        val row = incoming.copy(
            id = incoming.id.ifBlank { idFactory() },
            booked = booked,
            unfilledReason = when {
                booked -> null
                else -> incoming.unfilledReason ?: "Would not fill at the intended limit"
            },
            createdAtMs = if (incoming.createdAtMs > 0L) incoming.createdAtMs else nowMs()
        )
        val tickets = (listOf(row) + cur.tickets).take(LEDGER_MAX)
        val cash = if (booked) cur.cashUsd - row.stakeUsd else cur.cashUsd
        publish(
            cur.copy(
                cashUsd = cash,
                tickets = tickets,
                lastMessage = row.summary()
            )
        )
        Recorded(row, true)
    }

    fun settle(ticker: String, result: String): List<ShadowTicket> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val changed = mutableListOf<ShadowTicket>()
        synchronized(lock) {
            val cur = _state.value
            var cash = cur.cashUsd
            var added = 0.0
            val next = cur.tickets.map { ticket ->
                if (ticket.settled || !ticket.ticker.equals(ticker, ignoreCase = true)) return@map ticket
                if (!ticket.booked) {
                    return@map ticket.copy(
                        settled = true,
                        outcome = outcome,
                        won = null,
                        pnlUsd = null
                    ).also { changed += it }
                }
                val won = when (outcome) {
                    "void" -> null
                    "yes" -> ticket.side.equals("YES", true)
                    else -> ticket.side.equals("NO", true)
                }
                val payout = when {
                    outcome == "void" -> ticket.stakeUsd
                    won == true -> ticket.count * SignalConstants.CONTRACT_SETTLEMENT_USD
                    else -> 0.0
                }
                val pnl = payout - ticket.stakeUsd
                cash += payout
                added += pnl
                ticket.copy(
                    settled = true,
                    outcome = outcome,
                    won = won,
                    pnlUsd = pnl
                ).also { changed += it }
            }
            if (changed.isEmpty()) return emptyList()
            publish(
                cur.copy(
                    cashUsd = cash,
                    tickets = next,
                    lifetimeRealizedPnlUsd = cur.lifetimeRealizedPnlUsd + added,
                    lastMessage = "Shadow settled $ticker"
                )
            )
        }
        return changed
    }

    fun noteLiveError(message: String) = synchronized(lock) {
        val cur = _state.value
        publish(cur.copy(liveLastError = message, lastMessage = message))
    }

    fun clearLiveError() = synchronized(lock) {
        val cur = _state.value
        if (cur.liveLastError == null) return
        publish(cur.copy(liveLastError = null))
    }

    private fun publish(next: ShadowBookState) {
        _state.value = next
        runCatching { persist(next) }
    }

    companion object {
        const val LEDGER_MAX = 80
    }
}
