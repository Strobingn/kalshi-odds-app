package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.model.MarketTick
import java.util.UUID

/**
 * Builds approve-gated tickets from scored markets. Pure / local —
 * never calls Kalshi and never submits an order.
 */
object TicketBuilder {

    const val MARKET_CLOSED = "Market closed"

    fun noSellers(side: String): String =
        "No sellers on ${side.uppercase()} right now"

    data class Context(
        val settings: SignalSettings,
        val alertsPaused: Boolean,
        val books: Map<String, BookLevelSnapshot> = emptyMap(),
        val ticks: Map<String, MarketTick> = emptyMap(),
        val idFactory: () -> String = { UUID.randomUUID().toString() },
        val nowMs: Long = System.currentTimeMillis()
    )

    fun proposeAll(markets: List<MarketUiModel>, ctx: Context): List<TradeTicket> {
        if (!ctx.settings.ticketsEnabled) return emptyList()
        val live = MarketLifecycle.tradable(markets, ctx.nowMs)
        val hunter = live.mapNotNull { proposeHunter(it, ctx) }
        val configured = live.mapNotNull { propose(it, ctx) }
        return (hunter + configured)
            .distinctBy { "${it.kind}|${it.ticker}|${it.side}" }
            .sortedByDescending { it.maxPayoutUsd }
    }

    /**
     * $1 → ≥$25 hunter. Quality gates do **not** hide a cheap print —
     * detection is automatic, execution is still Approve-only.
     */
    fun proposeHunter(market: MarketUiModel, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        val preferred = resolveSide(market)
        val sides = listOfNotNull(preferred, "YES", "NO").distinct()
        return sides.firstNotNullOfOrNull { side ->
            buildTicket(
                market = market,
                side = side,
                ctx = ctx,
                stakeUsd = SignalConstants.HUNTER_STAKE_USD,
                minPayoutUsd = SignalConstants.HUNTER_MIN_PAYOUT_USD,
                kind = TicketKind.HUNTER,
                requireGates = false
            )
        }
    }

    fun propose(market: MarketUiModel, ctx: Context): TradeTicket? {
        val settings = ctx.settings
        if (!settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        val side = resolveSide(market) ?: return null
        val stake = PayoutGate.clipStake(settings.ticketStakeUsd)
        return buildTicket(
            market = market,
            side = side,
            ctx = ctx,
            stakeUsd = stake,
            minPayoutUsd = SignalConstants.DEFAULT_MIN_PAYOUT_USD,
            kind = TicketKind.CONFIGURED,
            requireGates = settings.ticketRespectGates
        )
    }

    /**
     * User-tapped Buy. Always returns a card when tickets are on: a sized
     * ticket, or a blocked "Market closed" / "No sellers on YES" card.
     * Never places. Page-level failSoft is not used for missing asks.
     */
    fun proposeManual(market: MarketUiModel, side: String, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        val want = side.uppercase().let { if (it == "NO") "NO" else "YES" }
        val stake = PayoutGate.clipStake(
            ctx.settings.ticketStakeUsd.coerceAtLeast(SignalConstants.HUNTER_STAKE_USD)
        )
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) {
            return blocked(market, want, ctx, MARKET_CLOSED, stake)
        }
        val ask = bestAsk(market, want, ctx)
        if (ask == null) {
            return blocked(market, want, ctx, noSellers(want), stake)
        }
        return buildTicket(
            market = market,
            side = want,
            ctx = ctx,
            stakeUsd = stake,
            minPayoutUsd = SignalConstants.CONTRACT_SETTLEMENT_USD,
            kind = TicketKind.MANUAL,
            requireGates = false
        ) ?: blocked(market, want, ctx, noSellers(want), stake)
    }

    private fun buildTicket(
        market: MarketUiModel,
        side: String,
        ctx: Context,
        stakeUsd: Double,
        minPayoutUsd: Double,
        kind: TicketKind,
        requireGates: Boolean
    ): TradeTicket? {
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        if (requireGates) {
            if (!market.passedFilter) return null
            if (market.muted) return null
            if (ctx.alertsPaused) return null
        }
        val ask = bestAsk(market, side, ctx) ?: return null
        val levels = askLevels(market, side, ctx.books[market.ticker])
        val quoted = quotedSize(market, side, ctx.books[market.ticker])
        val sizing = PayoutGate.evaluate(
            stakeUsd = stakeUsd,
            bestAsk = ask,
            askLevels = levels,
            quotedSize = quoted,
            minPayoutUsd = minPayoutUsd
        )
        if (!sizing.ok) return null

        val yesLimit = if (side == "YES") sizing.limitPrice else (1.0 - sizing.limitPrice)
        val bookSide = if (side == "YES") "bid" else "ask"
        val netPer = market.netEvDollars
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = bookSide,
            stakeUsd = stakeUsd,
            limitPrice = sizing.limitPrice,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            contracts = sizing.contracts,
            estimatedFillUsd = sizing.estimatedFillUsd,
            maxPayoutUsd = sizing.maxPayoutUsd,
            estimatedAvgFill = sizing.estimatedAvgFill,
            netEvUsd = netPer?.let { it * sizing.contracts },
            netEvPerContract = netPer,
            netEdgePp = market.netEdgePp,
            title = market.title,
            sizingNote = sizing.reason,
            gateNote = when (kind) {
                TicketKind.HUNTER -> "Hunter $1 → ≥$25 · Approve still required"
                TicketKind.MANUAL -> "Manual buy · Approve still required"
                TicketKind.CONFIGURED -> gateSummary(market, ctx)
            },
            createdAtMs = ctx.nowMs,
            kind = kind
        )
    }

    private fun blocked(
        market: MarketUiModel,
        side: String,
        ctx: Context,
        reason: String,
        stakeUsd: Double
    ): TradeTicket {
        val bookSide = if (side == "YES") "bid" else "ask"
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = bookSide,
            stakeUsd = stakeUsd,
            limitPrice = 0.0,
            yesLimitPrice = 0.0,
            contracts = 0,
            estimatedFillUsd = 0.0,
            maxPayoutUsd = 0.0,
            estimatedAvgFill = 0.0,
            title = market.title,
            sizingNote = reason,
            gateNote = if (reason == MARKET_CLOSED) {
                "This window is over — no live order will be sent"
            } else {
                "Approve stays off until sellers show up"
            },
            createdAtMs = ctx.nowMs,
            kind = TicketKind.MANUAL,
            blockedReason = reason
        )
    }

    fun resolveSide(market: MarketUiModel): String? {
        val primary = market.primaryHeroSide?.uppercase()
        if (primary == "YES" || primary == "NO") return primary
        val predicted = market.predictedSide?.uppercase()
        if (predicted == "YES" || predicted == "NO") return predicted
        val net = market.netEdgePp ?: market.edgePp ?: return null
        return when {
            net > 0 -> "YES"
            net < 0 -> "NO"
            else -> null
        }
    }

    fun bestAsk(market: MarketUiModel, side: String): Double? =
        bestAsk(market, side, Context(settings = SignalSettings(), alertsPaused = false))

    fun bestAsk(market: MarketUiModel, side: String, ctx: Context): Double? {
        val book = ctx.books[market.ticker]
        val tick = ctx.ticks[market.ticker]
        val fromQuote = if (side == "YES") {
            KalshiPrice.usable(market.yesAsk)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.noBid)
        } else {
            KalshiPrice.usable(market.noAsk)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.yesBid)
        }
        val fromTick = if (side == "YES") {
            KalshiPrice.usable(tick?.yesAsk)
        } else {
            KalshiPrice.impliedAskFromOppositeBid(tick?.yesBid)
        }
        val fromBook = bookAsk(side, book)
        return listOfNotNull(fromQuote, fromTick, fromBook).minOrNull()
    }

    /**
     * Ask levels for the chosen outcome, cheapest first, in that side's dollars.
     * YES asks = local NO bids at `(1 − noPrice)`. NO asks = local YES bids at
     * `(1 − yesPrice)`.
     */
    fun askLevels(market: MarketUiModel, side: String, book: BookLevelSnapshot?): List<Pair<Double, Double>> {
        if (book != null && !book.isEmpty()) {
            return if (side == "YES") {
                book.no.mapNotNull { (noPx, size) ->
                    KalshiPrice.impliedAskFromOppositeBid(noPx)?.let { it to size }
                }
            } else {
                book.yes.mapNotNull { (yesPx, size) ->
                    KalshiPrice.impliedAskFromOppositeBid(yesPx)?.let { it to size }
                }
            }
        }
        return emptyList()
    }

    fun quotedSize(market: MarketUiModel, side: String, book: BookLevelSnapshot?): Double? {
        if (book != null && !book.isEmpty()) {
            val levels = askLevels(market, side, book)
            val ask = bookAsk(side, book)
                ?: bestAsk(market, side)
                ?: return levels.sumOf { it.second }.takeIf { it > 0.0 }
            return levels.filter { it.first <= ask + 1e-9 }.sumOf { it.second }.takeIf { it > 0.0 }
        }
        if (side == "YES") {
            market.yesAskSize?.takeIf { it > 0.0 }?.let { return it }
        }
        return listOfNotNull(market.volume, market.openInterest, market.liquidityDollars).maxOrNull()
    }

    private fun bookAsk(side: String, book: BookLevelSnapshot?): Double? {
        if (book == null || book.isEmpty()) return null
        return if (side == "YES") {
            book.no.maxByOrNull { it.first }?.first?.let { KalshiPrice.impliedAskFromOppositeBid(it) }
        } else {
            book.yes.maxByOrNull { it.first }?.first?.let { KalshiPrice.impliedAskFromOppositeBid(it) }
        }
    }

    private fun gateSummary(market: MarketUiModel, ctx: Context): String {
        val bits = buildList {
            add(if (market.passedFilter) "skip filter cleared" else "skip filter off for tickets")
            add(if (!market.muted) "not muted" else "mute bypassed")
            add(if (!ctx.alertsPaused) "alerts live" else "streak-pause bypassed")
            market.rlNote?.let { add("$it — ticket still uses configured stake") }
            market.conformalSet?.let { add("conformal $it") }
            market.pathSurvive?.let {
                add(String.format(java.util.Locale.US, "P(edge) %.0f%%", it * 100.0))
            }
        }
        return bits.joinToString(" · ")
    }
}
