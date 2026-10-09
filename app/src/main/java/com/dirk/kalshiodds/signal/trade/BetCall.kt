package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.flip.FlipCheck
import com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy
import com.dirk.kalshiodds.signal.lastminute.LastMinutePhase

/**
 * Single source of truth for the card headline, the ticket side, and
 * the Approve target. 0.3.16: the pick is the last-minute strategy.
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
        val lm = market.lastMinute
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) {
            return when (lm?.phase) {
                LastMinutePhase.FIRED -> fired(market, ctx, lm)
                LastMinutePhase.NO_PLAY -> none(LastMinuteCopy.NO_PLAY)
                LastMinutePhase.WAITING, LastMinutePhase.LIVE -> none(flipReason(market, ctx) ?: LastMinuteCopy.NO_PLAY)
                null -> none(TicketBuilder.MARKET_CLOSED)
            }
        }
        return when (lm?.phase) {
            LastMinutePhase.FIRED -> fired(market, ctx, lm)
            LastMinutePhase.LIVE -> none(flipReason(market, ctx) ?: LastMinuteCopy.TITLE)
            LastMinutePhase.NO_PLAY -> none(LastMinuteCopy.NO_PLAY)
            LastMinutePhase.WAITING -> none(LastMinuteCopy.waiting(lm.startsInMs))
            null -> {
                if (com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired.retired) {
                    return none(com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired.LINE)
                }
                val tau = FlipCheck.secondsLeft(market.closeTimeEpochMs, ctx.nowMs)
                val inFinalMinute = tau != null && tau <= com.dirk.kalshiodds.signal.lastminute.LastMinuteConstants.FINAL_MINUTE_SEC
                if (inFinalMinute) {
                    none(flipReason(market, ctx) ?: LastMinuteCopy.TITLE)
                } else {
                    none(LastMinuteCopy.waiting(waitingMs(market, ctx.nowMs)))
                }
            }
        }
    }

    fun decide(market: MarketUiModel, settings: SignalSettings, nowMs: Long = System.currentTimeMillis()): Decision =
        decide(market, TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = nowMs))

    fun sortKey(decision: Decision): Int = if (decision.isActionable) 0 else 1

    fun qualifies(ticket: TradeTicket, market: MarketUiModel, ctx: TicketBuilder.Context): Boolean {
        if (ticket.kind == TicketKind.LAST_MINUTE) return ticket.canApprove
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

    private fun fired(
        market: MarketUiModel,
        ctx: TicketBuilder.Context,
        lm: com.dirk.kalshiodds.signal.lastminute.LastMinuteSnapshot
    ): Decision {
        val fired = lm.fired
        val liveAsk = fired?.let { TicketBuilder.liveAsk(market, it.side, ctx) ?: it.ask }
        if (fired != null && !FlipCheck.allowsFired(fired, lm.spotUsd ?: market.spotUsd, lm.strikeUsd ?: market.floorStrike, liveAsk)) {
            return none(lm.flip?.noBetLine ?: FlipCheck.evaluateMarket(market, ctx.nowMs)?.noBetLine ?: LastMinuteCopy.TITLE)
        }
        val ticket = TicketBuilder.proposeLastMinute(market, ctx)
            ?: return none(lm.flip?.noBetLine ?: fired?.let { LastMinuteCopy.buyLine(it) } ?: LastMinuteCopy.TITLE)
        return Decision(
            headline = if (ticket.side.equals("NO", true)) Headline.BET_DOWN else Headline.BET_UP,
            side = ticket.side,
            ticket = ticket,
            ask = fired?.ask ?: KalshiPrice.usable(ticket.limitPrice),
            profitIfWinUsd = ticket.profitIfWinUsd,
            allInUsd = ticket.estimatedFillUsd,
            contracts = ticket.contracts,
            noBetReason = null
        )
    }

    private fun flipReason(market: MarketUiModel, ctx: TicketBuilder.Context): String? =
        market.lastMinute?.flip?.noBetLine
            ?: FlipCheck.evaluateMarket(market, ctx.nowMs)?.noBetLine

    private fun waitingMs(market: MarketUiModel, nowMs: Long): Long? {
        val close = market.closeTimeEpochMs ?: return null
        val start = close - 60_000L
        return (start - nowMs).coerceAtLeast(0L)
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
