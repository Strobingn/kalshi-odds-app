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
    const val NO_BUYERS = "No buyers right now"

    fun noSellers(side: String): String =
        "No sellers on ${side.uppercase()} right now"

    fun closeNote(side: String, held: Int): String {
        val label = if (side.equals("NO", true)) "DOWN" else "UP"
        return "This will close $held of your $label shares"
    }

    data class Context(
        val settings: SignalSettings,
        val alertsPaused: Boolean,
        val books: Map<String, BookLevelSnapshot> = emptyMap(),
        val ticks: Map<String, MarketTick> = emptyMap(),
        val positions: List<LivePosition> = emptyList(),
        val idFactory: () -> String = { UUID.randomUUID().toString() },
        val nowMs: Long = System.currentTimeMillis(),
        val bankrollUsd: Double? = null,
        val bankrollSource: String? = null
    ) {
        fun heldOpposite(ticker: String, buySide: String): Int {
            val opposite = if (buySide == "NO") "YES" else "NO"
            val pos = positions.firstOrNull {
                it.ticker.equals(ticker, true) && it.side.equals(opposite, true)
            } ?: return 0
            return PositionParser.heldContracts(pos)
        }
    }

    fun proposeAll(markets: List<MarketUiModel>, ctx: Context): List<TradeTicket> {
        if (!ctx.settings.ticketsEnabled) return emptyList()
        if (ctx.settings.isSittingOut()) return emptyList()
        val live = MarketLifecycle.tradable(markets, ctx.nowMs)
        val hunter = live.mapNotNull { proposeHunter(it, ctx) }
        val value = live.mapNotNull { proposeHunterValue(it, ctx) }
        val configured = live.mapNotNull { propose(it, ctx) }
        return (hunter + value + configured)
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

    /**
     * Long-shot hunter: ask ≤ [SignalSettings.longShotMaxAsk] (default 20¢)
     * **and** AI/fair beats implied by fees + margin. Sized by win-target
     * (default $50 profit), not a fixed $1 stake. Approve still required.
     */
    fun proposeHunterValue(market: MarketUiModel, ctx: Context): TradeTicket? {
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
                minPayoutUsd = SignalConstants.DEFAULT_WIN_TARGET_USD,
                kind = TicketKind.HUNTER_VALUE,
                requireGates = false
            )
        }
    }

    /** Rebuild the same kind of ticket against a different bankroll (paper vs live). */
    fun resizeForBankroll(ticket: TradeTicket, market: MarketUiModel, ctx: Context): TradeTicket {
        if (ticket.isSell || !ctx.settings.winTargetEnabled) return ticket
        return when (ticket.kind) {
            TicketKind.HUNTER -> proposeHunter(market, ctx)
            TicketKind.HUNTER_VALUE -> proposeHunterValue(market, ctx)
            TicketKind.MANUAL -> proposeManual(market, ticket.side, ctx)
            TicketKind.CONFIGURED -> propose(market, ctx)
            TicketKind.SELL -> ticket
        } ?: ticket
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
        val built = buildTicket(
            market = market,
            side = want,
            ctx = ctx,
            stakeUsd = stake,
            minPayoutUsd = SignalConstants.CONTRACT_SETTLEMENT_USD,
            kind = TicketKind.MANUAL,
            requireGates = false
        ) ?: blocked(market, want, ctx, noSellers(want), stake)
        val heldOpposite = ctx.heldOpposite(market.ticker, want)
        return if (heldOpposite > 0) {
            built.copy(closeNote = closeNote(if (want == "YES") "NO" else "YES", heldOpposite))
        } else {
            built
        }
    }

    /**
     * Sell / reduce [heldContracts] of [side] at the best bid (user may
     * override count/price). V2 `reduce_only` is set. Never places.
     */
    fun proposeSell(
        market: MarketUiModel,
        side: String,
        heldContracts: Int,
        ctx: Context,
        count: Int? = null,
        limitPrice: Double? = null,
        paperOnly: Boolean = false
    ): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        val want = side.uppercase().let { if (it == "NO") "NO" else "YES" }
        val held = heldContracts.coerceAtLeast(0)
        if (held <= 0) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) {
            return blocked(market, want, ctx, MARKET_CLOSED, 0.0).copy(
                kind = TicketKind.SELL,
                reduceOnly = true,
                heldContracts = held,
                paperOnly = paperOnly
            )
        }
        val bid = KalshiPrice.usable(limitPrice) ?: bestBid(market, want, ctx)
        if (bid == null) {
            return blocked(market, want, ctx, NO_BUYERS, 0.0).copy(
                kind = TicketKind.SELL,
                reduceOnly = true,
                heldContracts = held,
                paperOnly = paperOnly
            )
        }
        val qty = (count ?: held).coerceIn(1, held)
        val yesLimit = if (want == "YES") bid else (1.0 - bid)
        val bookSide = if (want == "YES") "ask" else "bid"
        val proceeds = qty * bid
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = want,
            bookSide = bookSide,
            stakeUsd = proceeds,
            limitPrice = bid,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            contracts = qty,
            estimatedFillUsd = proceeds,
            maxPayoutUsd = proceeds,
            estimatedAvgFill = bid,
            title = market.title,
            sizingNote = "$qty ct · sell ${want} @ ${String.format(java.util.Locale.US, "%.1f¢", bid * 100.0)} · reduce-only",
            gateNote = "Sell GTC limit · Approve still required · never flips the other side",
            createdAtMs = ctx.nowMs,
            kind = TicketKind.SELL,
            reduceOnly = true,
            heldContracts = held,
            paperOnly = paperOnly
        )
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
        if (kind == TicketKind.HUNTER) {
            val hunterMax = PayoutGate.maxLimitForPayout(
                SignalConstants.HUNTER_STAKE_USD,
                SignalConstants.HUNTER_MIN_PAYOUT_USD
            )
            if (hunterMax != null && ask > hunterMax + 1e-9) return null
        }
        if (kind == TicketKind.HUNTER_VALUE) {
            val maxAsk = ctx.settings.longShotMaxAsk.coerceIn(0.05, 0.40)
            if (ask > maxAsk + 1e-9) return null
        }
        if (kind == TicketKind.CONFIGURED) {
            val configuredMax = PayoutGate.maxLimitForPayout(stakeUsd, minPayoutUsd)
            if (configuredMax != null && ask > configuredMax + 1e-9) return null
        }
        val levels = askLevels(market, side, ctx.books[market.ticker])
        val quoted = quotedSize(market, side, ctx.books[market.ticker])
        val bankroll = ctx.bankrollUsd ?: ctx.settings.bankrollUsd
        val winTargetOn = kind != TicketKind.SELL &&
            (ctx.settings.winTargetEnabled || kind == TicketKind.HUNTER_VALUE)
        val win = if (winTargetOn) {
            WinTargetSizer.size(
                askLevels = levels.ifEmpty { listOf(ask to (quoted ?: 500.0)) },
                targetProfitUsd = ctx.settings.winTargetUsd,
                bankrollUsd = bankroll,
                bankrollPct = ctx.settings.winTargetBankrollPct,
                absCapUsd = ctx.settings.winTargetAbsCapUsd,
                feeRate = ctx.settings.feeRate,
                fallbackAsk = ask
            )
        } else {
            null
        }
        val sizing = if (win != null && win.contracts > 0) {
            PayoutGate.Sizing(
                ok = true,
                contracts = win.contracts,
                limitPrice = win.vwap,
                estimatedFillUsd = win.stakeUsd,
                maxPayoutUsd = win.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD,
                estimatedAvgFill = win.vwap,
                fillableContracts = win.fillableContracts,
                reason = win.note
            )
        } else {
            PayoutGate.evaluate(
                stakeUsd = stakeUsd,
                bestAsk = ask,
                askLevels = levels,
                quotedSize = quoted,
                minPayoutUsd = minPayoutUsd
            )
        }
        if (!sizing.ok) return null

        val model01 = modelProb(market, side)
        val implied = sizing.estimatedAvgFill
        val edge = modelBeatsImplied(model01, implied, ctx.settings.feeRate)
        if (kind == TicketKind.HUNTER_VALUE && !edge) return null

        val yesLimit = if (side == "YES") sizing.limitPrice else (1.0 - sizing.limitPrice)
        val bookSide = if (side == "YES") "bid" else "ask"
        val netPer = market.netEvDollars
        val stake = if (win != null && win.contracts > 0) win.stakeUsd else stakeUsd
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = bookSide,
            stakeUsd = stake,
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
                TicketKind.HUNTER -> {
                    val sized = if (winTargetOn) " · sized to win \$${fmt(ctx.settings.winTargetUsd)}" else ""
                    "Hunter $1 → ≥$25$sized · Approve still required"
                }
                TicketKind.HUNTER_VALUE -> longShotNote(
                    ctx.settings.longShotMaxAsk,
                    implied,
                    model01,
                    ctx.settings.winTargetUsd
                )
                TicketKind.MANUAL -> {
                    val sized = if (winTargetOn) " · sized to win \$${fmt(ctx.settings.winTargetUsd)}" else ""
                    "Manual buy$sized · Approve still required"
                }
                TicketKind.CONFIGURED -> gateSummary(market, ctx)
                TicketKind.SELL -> "Sell GTC limit · Approve still required · reduce-only"
            },
            createdAtMs = ctx.nowMs,
            kind = kind,
            impliedChance = implied,
            modelChance = model01,
            fairChance = market.digitalFairPp?.div(100.0),
            modelEdge = edge,
            profitIfWinUsd = win?.profitIfWin ?: KalshiFee.netProfit(sizing.contracts, sizing.estimatedAvgFill, ctx.settings.feeRate),
            winTargetUsd = if (winTargetOn) ctx.settings.winTargetUsd else null,
            winTargetCapped = win?.capped == true,
            winTargetNote = win?.note,
            bankrollSource = ctx.bankrollSource,
            bankrollUsd = bankroll
        )
    }

    fun modelProb(market: MarketUiModel, side: String): Double? {
        val yes = market.importedModelPp?.div(100.0)
            ?: market.aiYesPercent?.div(100.0)
            ?: market.digitalFairPp?.div(100.0)
        if (yes == null || !yes.isFinite()) return null
        return if (side == "NO") 1.0 - yes else yes
    }

    fun modelBeatsImplied(model: Double?, implied: Double?, feeRate: Double, margin: Double = 0.03): Boolean {
        val m = model?.takeIf { it.isFinite() } ?: return false
        val p = implied?.takeIf { it.isFinite() } ?: return false
        val fee = KalshiFee.perContract(p, feeRate)
        return m > p + fee + margin
    }

    fun longShotNote(
        maxAsk: Double,
        implied: Double?,
        model: Double?,
        targetProfitUsd: Double
    ): String {
        val cap = String.format(java.util.Locale.US, "%.0f¢", maxAsk.coerceIn(0.05, 0.40) * 100.0)
        val mkt = implied?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val ai = model?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        return "Long-shot · ask ≤ $cap · market $mkt · AI $ai · sized to win \$${fmt(targetProfitUsd)} · Approve still required"
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)

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

    fun bestBid(market: MarketUiModel, side: String): Double? =
        bestBid(market, side, Context(settings = SignalSettings(), alertsPaused = false))

    fun bestBid(market: MarketUiModel, side: String, ctx: Context): Double? {
        val book = ctx.books[market.ticker]
        val tick = ctx.ticks[market.ticker]
        val fromQuote = if (side == "YES") {
            KalshiPrice.usable(market.yesBid)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.noAsk)
        } else {
            KalshiPrice.usable(market.noBid)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.yesAsk)
        }
        val fromTick = if (side == "YES") {
            KalshiPrice.usable(tick?.yesBid)
        } else {
            KalshiPrice.impliedAskFromOppositeBid(tick?.yesAsk)
        }
        val fromBook = if (book == null || book.isEmpty()) {
            null
        } else if (side == "YES") {
            book.yes.maxByOrNull { it.first }?.first?.let { KalshiPrice.usable(it) }
        } else {
            book.no.maxByOrNull { it.first }?.first?.let { KalshiPrice.usable(it) }
        }
        return listOfNotNull(fromQuote, fromTick, fromBook).maxOrNull()
    }

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
