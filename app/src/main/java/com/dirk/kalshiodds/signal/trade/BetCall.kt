package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings

/**
 * Single source of truth for the card headline, the ticket side, and
 * the Approve target. Never a separate heuristic.
 */
object BetCall {

    enum class Headline { BET_UP, BET_DOWN, NO_BET }

    data class Decision(
        val headline: Headline,
        val side: String?,
        val ticket: TradeTicket?,
        val ask: Double?,
        val profitIfWinUsd: Double?,
        val allInUsd: Double?,
        val contracts: Int,
        val noBetReason: String?
    ) {
        val isActionable: Boolean get() = headline != Headline.NO_BET && ticket?.canApprove == true
        val label: String get() = when (headline) {
            Headline.BET_UP -> "BET UP"
            Headline.BET_DOWN -> "BET DOWN"
            Headline.NO_BET -> "NO BET"
        }
    }

    fun decide(
        market: MarketUiModel,
        ctx: TicketBuilder.Context
    ): Decision {
        if (!ctx.settings.ticketsEnabled) {
            return none("Trade tickets are off in Settings")
        }
        if (ctx.settings.isSittingOut()) {
            return none(ctx.settings.autoTuneNote.ifBlank { "The model hasn't beaten Kalshi's prices in testing." })
        }
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) {
            return none(TicketBuilder.MARKET_CLOSED)
        }
        TicketBuilder.entryBlockReason(market, ctx.settings)?.let { return none(it) }
        // EV at the ask picks the side (or no side) whenever the engine has a fair.
        val ev = TicketBuilder.evDecision(market, ctx.settings.feeRate, ctx.settings.ticketStakeUsd)
        if (ev != null && ev.side == null) return none(ev.reason)
        val proposed = TicketBuilder.proposeAll(listOf(market), ctx).filter { !it.isSell }
        val manuals = listOf("YES", "NO").mapNotNull { TicketBuilder.proposeManual(market, it, ctx) }
        val all = (proposed + manuals).distinctBy { "${it.side.uppercase()}|${it.kind}" }
        // Only the EV side can be the call — never the other side's ticket.
        val tickets = if (ev != null) all.filter { it.side.equals(ev.side, true) } else all
        val actionable = tickets.filter { qualifies(it, market, ctx) }
        val preferred = ev?.side ?: TicketBuilder.resolveSide(market, ctx.settings.feeRate, ctx.settings.ticketStakeUsd)
        val chosen = actionable.firstOrNull { preferred != null && it.side.equals(preferred, true) }
            ?: actionable.maxByOrNull { it.profitIfWinUsd ?: 0.0 }
        if (chosen != null) {
            return Decision(
                headline = if (chosen.side.equals("NO", true)) Headline.BET_DOWN else Headline.BET_UP,
                side = chosen.side,
                ticket = chosen,
                ask = KalshiPrice.usable(chosen.limitPrice) ?: TicketBuilder.bestAsk(market, chosen.side, ctx),
                profitIfWinUsd = chosen.profitIfWinUsd,
                allInUsd = chosen.estimatedFillUsd,
                contracts = chosen.contracts,
                noBetReason = null
            )
        }
        val blocked = tickets.firstOrNull { it.blockedReason != null }
        val reason = blocked?.blockedReason
            ?: tickets.firstOrNull()?.gateNote?.takeIf { ev == null }
            ?: "No side clears edge after fees, the $5 all-in cap, and the min-profit setting"
        return none(reason)
    }

    fun decide(market: MarketUiModel, settings: SignalSettings, nowMs: Long = System.currentTimeMillis()): Decision =
        decide(market, TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = nowMs))

    fun sortKey(decision: Decision): Int = if (decision.isActionable) 0 else 1

    fun qualifies(ticket: TradeTicket, market: MarketUiModel, ctx: TicketBuilder.Context): Boolean {
        if (!ticket.canApprove) return false
        val ask = KalshiPrice.usable(ticket.limitPrice) ?: return false
        if (com.dirk.kalshiodds.signal.engine.QuoteSanity.isPlaceholder(ask)) return false
        val bid = if (ticket.side.equals("NO", true)) market.noBid else market.yesBid
        if (com.dirk.kalshiodds.signal.engine.QuoteSanity.isCrossed(bid, ask)) return false
        return ticket.modelEdge || TicketBuilder.modelBeatsImplied(
            ticket.modelChance,
            ticket.impliedChance,
            ctx.settings.feeRate,
            stakeUsd = ctx.settings.ticketStakeUsd
        )
    }

    private fun none(reason: String) = Decision(
        headline = Headline.NO_BET,
        side = null,
        ticket = null,
        ask = null,
        profitIfWinUsd = null,
        allInUsd = null,
        contracts = 0,
        noBetReason = reason
    )
}
