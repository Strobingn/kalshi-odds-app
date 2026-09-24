package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Isolated paper book. Never calls Kalshi. $100 start / $5 per AI fill.
 */
@Serializable
data class PaperFill(
    val id: String,
    val ticker: String,
    val side: String,
    val stakeUsd: Double,
    val contracts: Int,
    val limitPrice: Double,
    val source: String,
    val createdAtMs: Long,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null,
    val note: String
) {
    val displaySide: String get() = side.uppercase()
}

@Serializable
data class PaperArchive(
    val archivedAtMs: Long,
    val startingUsd: Double,
    val cashUsd: Double,
    val fills: List<PaperFill>,
    val note: String = "paper reset"
)

@Serializable
data class PaperBookState(
    val startingUsd: Double = SignalConstants.PAPER_START_USD,
    val cashUsd: Double = SignalConstants.PAPER_START_USD,
    val fills: List<PaperFill> = emptyList(),
    val lastMessage: String? = null,
    val archived: List<PaperArchive> = emptyList()
) {
    val openStakeUsd: Double get() = fills.filter { !it.settled }.sumOf { it.stakeUsd }
    val realizedPnlUsd: Double get() = fills.mapNotNull { it.pnlUsd }.sum()
    val equityUsd: Double get() = cashUsd + openStakeUsd
    val openCount: Int get() = fills.count { !it.settled }
}

class PaperBook(
    initial: PaperBookState = PaperBookState(),
    private val persist: (PaperBookState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PaperBookState> = _state.asStateFlow()

    fun snapshot(): PaperBookState = _state.value

    fun openTickers(): Set<String> = synchronized(lock) {
        _state.value.fills.filter { !it.settled }.map { it.ticker.uppercase() }.toSet()
    }

    fun reset() {
        synchronized(lock) {
            val cur = _state.value
            val archive = PaperArchive(
                archivedAtMs = nowMs(),
                startingUsd = cur.startingUsd,
                cashUsd = cur.cashUsd,
                fills = cur.fills,
                note = "Paper book reset — ledger archived"
            )
            publish(
                PaperBookState(
                    lastMessage = "Paper book reset to $100 — prior run archived",
                    archived = cur.archived + archive
                )
            )
        }
    }

    fun hydrate(next: PaperBookState) {
        synchronized(lock) {
            _state.value = next
        }
    }

    /**
     * Auto-log a $5 paper fill when an AI hunter / configured ticket would trade.
     * Manual live tickets are ignored — those need an explicit Paper tap.
     */
    fun considerTicket(ticket: TradeTicket, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        if (!ticket.canApprove) return null
        if (ticket.kind == TicketKind.MANUAL || ticket.kind == TicketKind.SELL) return null
        val source = if (ticket.kind == TicketKind.HUNTER) "AI hunter" else "AI ticket"
        return fill(
            ticker = ticket.ticker,
            side = ticket.side,
            limitPrice = ticket.limitPrice,
            source = source,
            note = "Paper $5 · ${ticket.kind.name.lowercase()} signal · never sent to Kalshi"
        )
    }

    fun considerAlert(alert: SignalAlert, ask: Double?, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        val px = ask?.coerceIn(0.01, 0.99) ?: return null
        return fill(
            ticker = alert.ticker,
            side = alert.predictedSide,
            limitPrice = px,
            source = "AI signal",
            note = alert.reason.ifBlank { "LiveCall / Dip Hunter signal" }
        )
    }

    /** User tapped Paper on a ticket. Still never hits Kalshi. */
    fun manualFill(ticket: TradeTicket): PaperFill? {
        if (!ticket.canPaper) return null
        if (ticket.isSell) return sell(ticket)
        return fill(
            ticker = ticket.ticker,
            side = ticket.side,
            limitPrice = ticket.limitPrice,
            source = "manual paper",
            note = "Paper $5 from ticket · never sent to Kalshi"
        )
    }

    /**
     * Simulated sell of an open paper fill at the ticket's bid. Never hits Kalshi.
     */
    fun sell(ticket: TradeTicket): PaperFill? {
        if (!ticket.canPaper || !ticket.isSell) return null
        val want = if (ticket.side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(ticket.limitPrice) ?: return null
        synchronized(lock) {
            val cur = _state.value
            val open = cur.fills.firstOrNull {
                !it.settled &&
                    it.ticker.equals(ticket.ticker, ignoreCase = true) &&
                    it.side.equals(want, ignoreCase = true)
            } ?: run {
                publish(cur.copy(lastMessage = "Paper sell skip ${ticket.ticker} — no open $want fill"))
                return null
            }
            val qty = min(ticket.contracts, open.contracts).coerceAtLeast(0)
            if (qty <= 0) return null
            val proceeds = qty * px
            val cost = qty * open.limitPrice
            val pnl = proceeds - cost
            val remaining = open.contracts - qty
            val sold = open.copy(
                settled = remaining <= 0,
                contracts = if (remaining <= 0) open.contracts else qty,
                outcome = "sell",
                won = pnl >= 0.0,
                pnlUsd = pnl,
                note = "Paper sell $qty ct @ ${String.format(java.util.Locale.US, "%.1f¢", px * 100)} · never sent to Kalshi"
            )
            val leftover = if (remaining > 0) {
                open.copy(
                    contracts = remaining,
                    stakeUsd = remaining * open.limitPrice,
                    note = open.note
                )
            } else {
                null
            }
            val nextFills = buildList {
                leftover?.let { add(it) }
                add(sold)
                cur.fills.filterNot { it.id == open.id }.forEach { add(it) }
            }.take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd + proceeds,
                    fills = nextFills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER SELL %s %s · %d ct @ %.0f¢ · %+.2f · never Kalshi",
                        sold.displaySide,
                        sold.ticker,
                        qty,
                        px * 100,
                        pnl
                    )
                )
            )
            return sold
        }
    }

    fun settle(ticker: String, result: String): List<PaperFill> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val changed = mutableListOf<PaperFill>()
        synchronized(lock) {
            val cur = _state.value
            var cash = cur.cashUsd
            val nextFills = cur.fills.map { fill ->
                if (fill.settled || !fill.ticker.equals(ticker, ignoreCase = true)) return@map fill
                val won = when (outcome) {
                    "void" -> null
                    "yes" -> fill.side.equals("YES", true)
                    else -> fill.side.equals("NO", true)
                }
                val payout = when {
                    outcome == "void" -> fill.stakeUsd
                    won == true -> fill.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD
                    else -> 0.0
                }
                val pnl = payout - fill.stakeUsd
                cash += payout
                fill.copy(
                    settled = true,
                    outcome = outcome,
                    won = won,
                    pnlUsd = pnl
                ).also { changed += it }
            }
            if (changed.isEmpty()) return emptyList()
            val msg = changed.last().let { f ->
                when {
                    f.outcome == "void" -> "Paper void ${f.ticker} — stake returned"
                    f.won == true -> String.format(
                        java.util.Locale.US,
                        "Paper WIN %s %s  %+.2f",
                        f.displaySide,
                        f.ticker,
                        f.pnlUsd ?: 0.0
                    )
                    else -> String.format(
                        java.util.Locale.US,
                        "Paper LOSS %s %s  %+.2f",
                        f.displaySide,
                        f.ticker,
                        f.pnlUsd ?: 0.0
                    )
                }
            }
            publish(cur.copy(cashUsd = cash, fills = nextFills, lastMessage = msg))
        }
        return changed
    }

    fun settleFromLog(entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>) {
        entries.forEach { e ->
            val o = e.outcome ?: return@forEach
            settle(e.ticker, o)
        }
    }

    private fun fill(
        ticker: String,
        side: String,
        limitPrice: Double,
        source: String,
        note: String
    ): PaperFill? {
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = limitPrice.coerceIn(0.01, 0.99)
        synchronized(lock) {
            val cur = _state.value
            if (cur.fills.any { it.ticker.equals(ticker, ignoreCase = true) }) return null
            val contracts = floor(SignalConstants.PAPER_STAKE_USD / px).toInt()
            if (contracts < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — ask too high for a $5 clip"))
                return null
            }
            val stake = contracts * px
            if (cur.cashUsd + 1e-9 < stake) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — need $100 reset (cash ${fmt(cur.cashUsd)})"))
                return null
            }
            val row = PaperFill(
                id = idFactory(),
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = contracts,
                limitPrice = px,
                source = source,
                createdAtMs = nowMs(),
                note = note
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake,
                    fills = fills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER %s %s · $%.2f · %d ct @ %.0f¢ · %s",
                        row.displaySide,
                        row.ticker,
                        row.stakeUsd,
                        row.contracts,
                        row.limitPrice * 100,
                        source
                    )
                )
            )
            return row
        }
    }

    private fun publish(next: PaperBookState) {
        _state.value = next
        runCatching { persist(next) }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "$%.2f", v)
}
