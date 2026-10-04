package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.d3.D3Copy
import com.dirk.kalshiodds.signal.d3.D3Snapshot
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.RestingOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlin.math.abs

/**
 * Pure state for the Real Money tab. Rendering lives in [RealMoneyScreen].
 * Nothing here places an order.
 */
object RealMoneyTab {
    const val TITLE = "Real Money"
    const val ADD_KEY = "Add Kalshi API key"
    const val EXPLAINER =
        "Real orders only go out after you tap Approve and then confirm REAL MONEY. " +
            "Each order is capped at $10. Nothing is ever placed automatically."

    const val DEMO_TITLE = "Kalshi demo environment"
    const val DEMO_EXPLANATION =
        "On sends orders to Kalshi's play-money demo. Off uses the live Kalshi exchange and real cash."
    const val PAPER_TITLE = "Paper trading"
    const val PAPER_EXPLANATION =
        "On records simulated fills in the $1,000 paper book. Off stops those paper fills. " +
            "Neither setting places a real order."

    data class Account(
        val keySaved: Boolean,
        val keyValid: Boolean,
        val liveEnvironment: Boolean,
        val environmentLabel: String,
        val balanceLabel: String,
        val showAddKey: Boolean
    )

    data class Switch(
        val id: String,
        val title: String,
        val explanation: String,
        val on: Boolean
    )

    data class Row(val id: String, val line: String)

    data class Page(
        val account: Account,
        val switches: List<Switch>,
        val pending: List<Row>,
        val d3Status: String,
        val d3Tickets: List<Row>,
        val orders: List<String>,
        val positions: List<String>,
        val fills: List<String>,
        val realPnlLabel: String,
        val explainer: String = EXPLAINER
    )

    fun of(
        liveKeySaved: Boolean,
        demoEnvironment: Boolean,
        cashUsd: Double?,
        paperOn: Boolean,
        proposals: List<TradeTicket>,
        d3: D3Snapshot,
        working: List<PlacedOrder> = emptyList(),
        resting: List<RestingOrder> = emptyList(),
        positions: List<LivePosition> = emptyList()
    ): Page {
        val live = !demoEnvironment
        val valid = liveKeySaved && live && cashUsd != null
        val account = Account(
            keySaved = liveKeySaved,
            keyValid = valid,
            liveEnvironment = live,
            environmentLabel = if (live) "Live Kalshi" else "Demo environment — not real money",
            balanceLabel = when {
                !liveKeySaved -> "No Kalshi API key saved"
                cashUsd != null && live -> String.format(Locale.US, "Real cash $%.2f", cashUsd)
                cashUsd != null -> String.format(Locale.US, "Demo balance $%.2f", cashUsd)
                live -> "Key saved — balance not loaded yet"
                else -> "Demo is on — turn it off for real cash"
            },
            showAddKey = !liveKeySaved
        )
        val pendingTickets = proposals.filter { it.kind != TicketKind.D3 && it.canApprove }
        val d3Tickets = proposals.filter { it.kind == TicketKind.D3 && it.canApprove }
        val orderLines = buildList {
            resting.forEach { add(orderLine(it)) }
            working.forEach { add(workingLine(it)) }
        }
        val positionLines = positions.map { positionLine(it) }
        val fillLines = positions.mapNotNull { fillLine(it) }
        val pnl = positions.mapNotNull { it.realizedPnlUsd }.sum()
        return Page(
            account = account,
            switches = listOf(
                Switch("demo", DEMO_TITLE, DEMO_EXPLANATION, demoEnvironment),
                Switch("paper", PAPER_TITLE, PAPER_EXPLANATION, paperOn)
            ),
            pending = pendingTickets.map { Row(it.id, ticketLine(it)) },
            d3Status = "${D3Copy.TITLE}. ${D3Copy.phaseLine(d3)} ${d3.todayLine}".trim(),
            d3Tickets = d3Tickets.map { Row(it.id, ticketLine(it)) },
            orders = orderLines,
            positions = positionLines,
            fills = fillLines,
            realPnlLabel = String.format(Locale.US, "Realized P&L (Kalshi, not paper) %+.2f", pnl)
        )
    }

    private fun ticketLine(ticket: TradeTicket): String {
        val side = if (ticket.side.equals("NO", true)) "DOWN" else "UP"
        return String.format(
            Locale.US,
            "%s %s · %d ct @ %.0f¢ · $%.2f",
            side,
            ticket.ticker,
            ticket.contracts,
            ticket.limitPrice * 100.0,
            ticket.stakeUsd
        )
    }

    private fun orderLine(order: RestingOrder): String {
        val side = if (order.side.equals("NO", true)) "DOWN" else "UP"
        val px = order.price?.let { String.format(Locale.US, "%.0f¢", it * 100.0) } ?: "—"
        return "Open $side ${order.ticker} · ${order.remaining.toInt()} left @ $px · ${order.status}"
    }

    private fun workingLine(order: PlacedOrder): String {
        val side = if (order.ticket.side.equals("NO", true)) "DOWN" else "UP"
        return "Working $side ${order.ticket.ticker} · order ${order.orderId ?: "pending"}"
    }

    private fun positionLine(position: LivePosition): String {
        val side = if (position.side.equals("NO", true)) "DOWN" else "UP"
        return String.format(
            Locale.US,
            "Position %s %s · %.0f ct",
            side,
            position.ticker,
            position.contracts
        )
    }

    private fun fillLine(position: LivePosition): String? {
        val pnl = position.realizedPnlUsd ?: return null
        if (abs(pnl) < 1e-9 && position.contracts > 0.0) return null
        val side = if (position.side.equals("NO", true)) "DOWN" else "UP"
        return String.format(Locale.US, "Settled %s %s · real %+.2f", side, position.ticker, pnl)
    }
}
