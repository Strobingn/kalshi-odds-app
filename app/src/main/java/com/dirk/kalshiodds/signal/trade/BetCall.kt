package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.DirectionSanity

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
        automaticBlockReason(market, ctx)?.let { return none(it) }

        // A manual ticket is solely the result of an explicit user Buy tap.
        // It may deliberately bypass automated filters, so it must never be
        // promoted into the app's BET UP/BET DOWN recommendation.
        val tickets = TicketBuilder.proposeAll(listOf(market), ctx)
            .filter { !it.isSell && it.kind != TicketKind.MANUAL }
            .distinctBy { "${it.side.uppercase()}|${it.kind}" }
        val actionable = tickets.filter { qualifies(it, market, ctx) }
        val preferred = TicketBuilder.resolveSide(market)
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
            ?: tickets.firstOrNull()?.gateNote
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

    /**
     * Recommendation-only gates. They deliberately do not apply to a manual
     * Buy tap or to paper-autopilot: a headline labelled BET must be a
     * currently executable, model-qualified opportunity.
     */
    private fun automaticBlockReason(market: MarketUiModel, ctx: TicketBuilder.Context): String? = when {
        finalWindowOpposesEstablishedDirection(market, ctx.nowMs) ->
            "NO BET — final-window move is materially against the settled direction"
        !market.passedFilter -> market.skipReason ?: "NO BET — market did not clear the signal filter"
        market.muted -> market.muteReason ?: "NO BET — market is muted"
        ctx.alertsPaused -> "NO BET — signal alerts are paused"
        !market.uncertaintyPassed -> "NO BET — model uncertainty is too high"
        market.tapeConflict -> market.tapeConflictNote ?: "NO BET — model and market direction disagree"
        !market.modelEdgeQualified -> "NO BET — imported model did not clear fees and confidence margin"
        ctx.books[market.ticker]?.isEmpty() != false ->
            "NO BET — waiting for a verified live order-book snapshot"
        else -> null
    }

    /**
     * At the end of a crypto window, do not fade a spot price that is already
     * far beyond the target. The same gap is used by [DirectionSanity] to
     * establish the side; four gaps is its existing strong-confirmation bar.
     */
    private fun finalWindowOpposesEstablishedDirection(market: MarketUiModel, nowMs: Long): Boolean {
        val closeMs = market.closeTimeEpochMs ?: return false
        if (closeMs - nowMs !in 0L..FINAL_WINDOW_MS) return false
        val distance = market.spotVsTargetUsd?.takeIf { it.isFinite() } ?: return false
        val strike = market.floorStrike?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        if (kotlin.math.abs(distance) < DirectionSanity.gapUsd(strike) * STRONG_DIRECTION_GAP_MULTIPLIER) {
            return false
        }
        val establishedSide = if (distance > 0.0) "YES" else "NO"
        val modelSide = TicketBuilder.resolveSide(market) ?: return false
        return !modelSide.equals(establishedSide, ignoreCase = true)
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

    private const val FINAL_WINDOW_MS = 60_000L
    private const val STRONG_DIRECTION_GAP_MULTIPLIER = 4.0
}
