package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings

/**
 * Single source of truth for the card headline, the ticket side, and
 * the Approve target. Never a separate heuristic.
 *
 * Kalshi data only: BET UP / BET DOWN comes only from the clear-lead rule,
 * which reads Kalshi's own prices. Everything else is NO BET.
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
        // Kalshi data only (since 1.9.6): the only call is the clear-lead rule, read from Kalshi's prices.
        // The AI model's chance comes from an outside Bitcoin feed, so it no longer makes calls.
        clearLeadCall(market, ctx)?.let { return it }
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) {
            return none(TicketBuilder.MARKET_CLOSED)
        }
        return none(NO_RULE)
    }

    /**
     * BET UP / BET DOWN from the clear-lead rule, or null when the rule is
     * not on for [market] or its ticket cannot be approved right now.
     */
    fun clearLeadCall(market: MarketUiModel, ctx: TicketBuilder.Context): Decision? {
        val ticket = TicketBuilder.proposeClearLead(market, ctx) ?: return null
        val ask = KalshiPrice.usable(ticket.limitPrice) ?: return null
        if (com.dirk.kalshiodds.signal.engine.QuoteSanity.isPlaceholder(ask)) return null
        val bid = if (ticket.side.equals("NO", true)) market.noBid else market.yesBid
        if (com.dirk.kalshiodds.signal.engine.QuoteSanity.isCrossed(bid, ask)) return null
        return Decision(
            headline = if (ticket.side.equals("NO", true)) Headline.BET_DOWN else Headline.BET_UP,
            side = ticket.side,
            ticket = ticket,
            ask = ask,
            profitIfWinUsd = ticket.profitIfWinUsd,
            allInUsd = ticket.estimatedFillUsd,
            contracts = ticket.contracts,
            noBetReason = null
        )
    }

    fun decide(market: MarketUiModel, settings: SignalSettings, nowMs: Long = System.currentTimeMillis()): Decision =
        decide(market, TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = nowMs))

    const val NO_RULE =
        "No bet: the clear-lead rule (Kalshi prices UP or DOWN at 80–90¢, minutes 3–10) is not on right now."

    fun sortKey(decision: Decision): Int = if (decision.isActionable) 0 else 1

    fun qualifies(ticket: TradeTicket, market: MarketUiModel, ctx: TicketBuilder.Context): Boolean {
        if (!ticket.canApprove) return false
        val ask = KalshiPrice.usable(ticket.limitPrice) ?: return false
        if (com.dirk.kalshiodds.signal.engine.QuoteSanity.isPlaceholder(ask)) return false
        if (TicketBuilder.isLongShotPrice(ask)) return false
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
