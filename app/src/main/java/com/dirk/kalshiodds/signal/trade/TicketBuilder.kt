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
    const val WINDOW_CLOSED = "Window closed"
    const val NO_BUYERS = "No buyers right now; this position can't be sold"
    const val SELL_IOC_NOTE = "Sells at the current bid. Leftover size is canceled."

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
        val scalps = live.mapNotNull { proposeScalp(it, ctx) }
        return (hunter + value + configured + scalps)
            .distinctBy { "${it.kind}|${it.ticker}|${it.side}" }
            .sortedByDescending { it.maxPayoutUsd }
    }

    /**
     * Aggressive scalp: any time in the window, buy the side the live spot
     * return confirms (up -> YES, down -> NO), join the bid (20-99c entries).
     * Backtested on 7 days of live 15m markets: the spot-confirmed side wins
     * OOS +0.88/bet (n=7516) vs +0.47 for favorite-only - the spot direction
     * is the edge, not the favorite label (docs/edge-research-2026-10-08.md).
     * Approve still required.
     */
    fun proposeScalp(market: MarketUiModel, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        val ret = market.spotReturn1m ?: return null
        val side = when {
            ret > 0.0 -> "YES"
            ret < 0.0 -> "NO"
            else -> return null
        }
        return buildScalpTicket(market, side, ctx)
    }

    private fun buildScalpTicket(market: MarketUiModel, side: String, ctx: Context): TradeTicket? {
        val ask = bestAsk(market, side, ctx) ?: return null
        val bid = freshBestBid(market, side, ctx)
        val tick = KalshiPrice.MIN_TICK_DOLLARS
        val entry = when {
            bid == null -> ask
            bid < ask - tick -> bid
            else -> (ask - tick).coerceAtLeast(tick)
        }
        if (entry < SCALP_MIN_ENTRY || entry > SCALP_MAX_ENTRY) return null
        val live = LiveOrderSizer.size(entry, SignalConstants.LIVE_ALL_IN_CAP_USD, ctx.settings.feeRate)
        if (!live.ok) return null
        val minProfit = ctx.settings.minProfitIfWinUsd
        val belowMin = LiveOrderSizer.belowMinProfit(live.profitIfWinUsd, minProfit)
        val yesLimit = if (side == "YES") live.price else (1.0 - live.price)
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = if (side == "YES") "bid" else "ask",
            stakeUsd = live.allInUsd,
            limitPrice = live.price,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            contracts = live.count,
            estimatedFillUsd = live.allInUsd,
            maxPayoutUsd = live.count * SignalConstants.CONTRACT_SETTLEMENT_USD,
            estimatedAvgFill = live.price,
            title = market.title,
            sizingNote = String.format(
                java.util.Locale.US,
                "%d ct @ %.1f¢ · join bid · all-in $%.2f (fee $%.2f)",
                live.count, live.price * 100.0, live.allInUsd, live.feeUsd
            ),
            gateNote = String.format(
                java.util.Locale.US,
                "Scalp · spot moving %s · join bid · hold to close · Approve still required",
                if (side == "YES") "UP" else "DOWN"
            ),
            createdAtMs = ctx.nowMs,
            kind = TicketKind.SCALP,
            blockedReason = if (belowMin) {
                LiveOrderSizer.belowMinProfitMessage(live.profitIfWinUsd, minProfit)
            } else null,
            impliedChance = live.price,
            modelChance = null,
            fairChance = market.digitalFairPp?.div(100.0),
            profitIfWinUsd = live.profitIfWinUsd,
            feeUsd = live.feeUsd,
            allInUsd = live.allInUsd,
            belowMinProfit = belowMin,
            minProfitIfWinUsd = minProfit,
            winTargetUsd = minProfit,
            winTargetCapped = true,
            winTargetNote = String.format(
                java.util.Locale.US,
                "≤$5 all-in · wins $%.2f",
                live.profitIfWinUsd
            )
        )
    }

    /** Cheap hunter tickets still require positive modeled value after costs. */
    fun proposeHunter(market: MarketUiModel, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        return rankedValueSides(market, ctx).firstNotNullOfOrNull { side ->
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
     * **and** AI/fair beats implied by fees + margin. Sized at the $5 all-in
     * live cap. Approve still required.
     */
    fun proposeHunterValue(market: MarketUiModel, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        return rankedValueSides(market, ctx).firstNotNullOfOrNull { side ->
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

    /** Rebuild the same kind of ticket (live $5 cap, not bankroll win-target). */
    fun resizeForBankroll(ticket: TradeTicket, market: MarketUiModel, ctx: Context): TradeTicket {
        if (ticket.isSell) return ticket
        return when (ticket.kind) {
            TicketKind.HUNTER -> proposeHunter(market, ctx)
            TicketKind.HUNTER_VALUE -> proposeHunterValue(market, ctx)
            TicketKind.SCALP -> proposeScalp(market, ctx)
            TicketKind.MANUAL -> proposeManual(market, ticket.side, ctx)
            TicketKind.CONFIGURED -> propose(market, ctx)
            TicketKind.SELL -> ticket
        } ?: ticket
    }

    fun propose(market: MarketUiModel, ctx: Context): TradeTicket? {
        val settings = ctx.settings
        if (!settings.ticketsEnabled) return null
        if (!MarketLifecycle.isTradable(market, ctx.nowMs)) return null
        val stake = PayoutGate.clipStake(settings.ticketStakeUsd)
        return rankedValueSides(market, ctx).firstNotNullOfOrNull { side ->
            buildTicket(
                market = market,
                side = side,
                ctx = ctx,
                stakeUsd = stake,
                minPayoutUsd = SignalConstants.DEFAULT_MIN_PAYOUT_USD,
                kind = TicketKind.CONFIGURED,
                requireGates = settings.ticketRespectGates
            )
        }
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
     * Sell / reduce [heldContracts] of [side] at the **fresh** best bid.
     * V2 `reduce_only` + IoC. Never places. [limitPrice] is clipped so it
     * cannot exceed the live bid — never a stale or higher quote.
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
        val fresh = freshBestBid(market, want, ctx)
        if (fresh == null) {
            return blocked(market, want, ctx, NO_BUYERS, 0.0).copy(
                kind = TicketKind.SELL,
                reduceOnly = true,
                heldContracts = held,
                paperOnly = paperOnly,
                gateNote = NO_BUYERS
            )
        }
        val requested = KalshiPrice.usable(limitPrice)
        val bid = if (requested != null) minOf(requested, fresh) else fresh
        val qty = (count ?: held).coerceIn(1, held)
        return sellTicket(
            market = market,
            side = want,
            held = held,
            qty = qty,
            bid = bid,
            ctx = ctx,
            paperOnly = paperOnly
        )
    }

    /**
     * Re-price a sell at a usable bid (already clipped to the fresh book).
     * Empty / 0 bid blocks Approve.
     */
    fun applySellQuote(
        ticket: TradeTicket,
        count: Int,
        bid: Double?,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): TradeTicket {
        val held = (ticket.heldContracts ?: ticket.contracts).coerceAtLeast(1)
        val usable = KalshiPrice.usable(bid)
        if (usable == null) {
            return ticket.copy(
                contracts = 0,
                limitPrice = 0.0,
                yesLimitPrice = 0.0,
                stakeUsd = 0.0,
                estimatedFillUsd = 0.0,
                maxPayoutUsd = 0.0,
                estimatedAvgFill = 0.0,
                feeUsd = 0.0,
                allInUsd = 0.0,
                blockedReason = NO_BUYERS,
                sizingNote = NO_BUYERS,
                gateNote = NO_BUYERS
            )
        }
        return pricedSell(ticket, count.coerceIn(1, held), usable, feeRate)
    }

    private fun sellTicket(
        market: MarketUiModel,
        side: String,
        held: Int,
        qty: Int,
        bid: Double,
        ctx: Context,
        paperOnly: Boolean
    ): TradeTicket {
        val feeRate = ctx.settings.feeRate
        val fee = KalshiFee.total(qty, bid, feeRate)
        val proceeds = (qty * bid - fee).coerceAtLeast(0.0)
        val yesLimit = if (side == "YES") bid else (1.0 - bid)
        val bookSide = if (side == "YES") "ask" else "bid"
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = bookSide,
            stakeUsd = proceeds,
            limitPrice = bid,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            contracts = qty,
            estimatedFillUsd = proceeds,
            maxPayoutUsd = proceeds,
            estimatedAvgFill = bid,
            title = market.title,
            sizingNote = sellSizing(qty, side, bid),
            gateNote = SELL_IOC_NOTE,
            createdAtMs = ctx.nowMs,
            kind = TicketKind.SELL,
            reduceOnly = true,
            heldContracts = held,
            paperOnly = paperOnly,
            feeUsd = fee,
            allInUsd = proceeds
        )
    }

    private fun pricedSell(
        ticket: TradeTicket,
        qty: Int,
        bid: Double,
        feeRate: Double
    ): TradeTicket {
        val fee = KalshiFee.total(qty, bid, feeRate)
        val proceeds = (qty * bid - fee).coerceAtLeast(0.0)
        val yesLimit = if (ticket.side.equals("NO", true)) 1.0 - bid else bid
        return ticket.copy(
            contracts = qty,
            limitPrice = bid,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            stakeUsd = proceeds,
            estimatedFillUsd = proceeds,
            maxPayoutUsd = proceeds,
            estimatedAvgFill = bid,
            feeUsd = fee,
            allInUsd = proceeds,
            blockedReason = null,
            sizingNote = sellSizing(qty, ticket.side, bid),
            gateNote = SELL_IOC_NOTE
        )
    }

    private fun sellSizing(qty: Int, side: String, bid: Double): String =
        "$qty ct · sell $side @ ${String.format(java.util.Locale.US, "%.1f¢", bid * 100.0)} · at the bid"

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
        // Automatic tickets post as makers: rest at the current best bid on
        // the buy side instead of crossing the spread at the ask. Backtest on
        // 17k minutes of live KXBTC/KXETH/KXSOL 15m data shows taker entries
        // lose the ~1c spread + fee on every fill, while maker entries keep
        // it. MANUAL keeps the old taker-at-ask behavior (user tapped Buy).
        // A crossed or missing book must never fabricate a cheap entry: the
        // resting price is clipped below the executable ask.
        val entryPrice = if (kind == TicketKind.MANUAL) {
            ask
        } else {
            val bid = freshBestBid(market, side, ctx)
            val tick = KalshiPrice.MIN_TICK_DOLLARS
            when {
                bid == null -> ask
                bid < ask - tick -> bid
                else -> (ask - tick).coerceAtLeast(tick)
            }
        }
        // For automatic suggestions, the actual $5 clip must fit at the
        // quoted touch. The payout check below can require a much smaller
        // $1 clip, so passing it alone does not make the displayed size real.
        val liveBook = ctx.books[market.ticker]?.takeIf { !it.isEmpty() }
        val live = if (kind != TicketKind.MANUAL && liveBook != null) {
            LiveOrderSizer.sizeWithinDepth(
                entryPrice,
                kotlin.math.floor((quoted ?: 0.0) + 1e-9).toInt(),
                SignalConstants.LIVE_ALL_IN_CAP_USD,
                ctx.settings.feeRate
            )
        } else {
            LiveOrderSizer.size(entryPrice, SignalConstants.LIVE_ALL_IN_CAP_USD, ctx.settings.feeRate)
        }
        if (!live.ok) {
            return if (kind == TicketKind.MANUAL) {
                blocked(market, side, ctx, live.refusedReason ?: "Cannot size a $5 live order", stakeUsd)
            } else {
                null
            }
        }
        val payoutCheck = PayoutGate.evaluate(
            stakeUsd = stakeUsd,
            bestAsk = ask,
            askLevels = levels,
            quotedSize = quoted,
            minPayoutUsd = minPayoutUsd
        )
        if (kind == TicketKind.CONFIGURED && !payoutCheck.ok) return null
        if (kind == TicketKind.HUNTER && !payoutCheck.ok) return null

        val model01 = modelProb(market, side)
        val implied = live.price
        val netPer = model01?.let { it - live.allInUsd / live.count }
        val edge = netPer != null && netPer > AUTO_VALUE_MARGIN
        // All automatic tickets need a buffer above the entry price and
        // the fee for the actual $5 clip. A cheap payoff is not itself edge.
        if (kind != TicketKind.MANUAL && !edge) return null

        val yesLimit = if (side == "YES") live.price else (1.0 - live.price)
        val bookSide = if (side == "YES") "bid" else "ask"
        val minProfit = ctx.settings.minProfitIfWinUsd
        val belowMin = LiveOrderSizer.belowMinProfit(live.profitIfWinUsd, minProfit)
        val blockedReason = if (belowMin) {
            LiveOrderSizer.belowMinProfitMessage(live.profitIfWinUsd, minProfit)
        } else {
            null
        }
        return TradeTicket(
            id = ctx.idFactory(),
            ticker = market.ticker,
            side = side,
            bookSide = bookSide,
            stakeUsd = live.allInUsd,
            limitPrice = live.price,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            contracts = live.count,
            estimatedFillUsd = live.allInUsd,
            maxPayoutUsd = live.count * SignalConstants.CONTRACT_SETTLEMENT_USD,
            estimatedAvgFill = live.price,
            netEvUsd = netPer?.let { it * live.count },
            netEvPerContract = netPer,
            netEdgePp = netPer?.times(100.0),
            title = market.title,
            sizingNote = String.format(
                java.util.Locale.US,
                "%d ct @ %.1f¢ · all-in $%.2f (fee $%.2f) · profit if win $%.2f · $5 cap",
                live.count,
                live.price * 100.0,
                live.allInUsd,
                live.feeUsd,
                live.profitIfWinUsd
            ),
            gateNote = when (kind) {
                TicketKind.HUNTER ->
                    "Hunter print ($1 can settle ≥$25) · live size is the $5 all-in cap · Approve still required"
                TicketKind.HUNTER_VALUE -> longShotNote(
                    ctx.settings.longShotMaxAsk,
                    implied,
                    model01,
                    minProfit
                )
                TicketKind.MANUAL ->
                    "Manual buy · $5 all-in cap including fees · Approve still required"
                TicketKind.CONFIGURED -> gateSummary(market, ctx)
                TicketKind.SCALP -> "Scalp · spot confirms the favorite · Approve still required"
                TicketKind.SELL -> SELL_IOC_NOTE
            },
            createdAtMs = ctx.nowMs,
            kind = kind,
            blockedReason = blockedReason,
            impliedChance = implied,
            modelChance = model01,
            fairChance = market.digitalFairPp?.div(100.0),
            modelEdge = edge,
            profitIfWinUsd = live.profitIfWinUsd,
            feeUsd = live.feeUsd,
            allInUsd = live.allInUsd,
            belowMinProfit = belowMin,
            minProfitIfWinUsd = minProfit,
            winTargetUsd = minProfit,
            winTargetCapped = true,
            winTargetNote = String.format(
                java.util.Locale.US,
                "≤$5 all-in · min profit $%.0f · wins $%.2f",
                minProfit,
                live.profitIfWinUsd
            ),
            bankrollSource = ctx.bankrollSource,
            bankrollUsd = bankroll,
            visibleContracts = quoted?.toInt()
        )
    }

    fun modelProb(market: MarketUiModel, side: String): Double? {
        val yes = market.importedModelPp?.div(100.0)
            ?: market.aiYesPercent?.div(100.0)
            ?: market.digitalFairPp?.div(100.0)
        if (yes == null || !yes.isFinite()) return null
        return if (side == "NO") 1.0 - yes else yes
    }

    fun modelBeatsImplied(
        model: Double?,
        implied: Double?,
        feeRate: Double,
        margin: Double = 0.03,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Boolean {
        val m = model?.takeIf { it.isFinite() } ?: return false
        val p = implied?.takeIf { it.isFinite() } ?: return false
        val fee = KalshiFee.perContract(p, feeRate, stakeUsd)
        return m > p + fee + margin
    }

    const val AUTO_VALUE_MARGIN = 0.08

    /** Aggressive scalp entries: spot-confirmed side, 20-99c. */
    const val SCALP_MIN_ENTRY = 0.20
    const val SCALP_MAX_ENTRY = 0.99


    /** Rank the two actual buys independently; a hero direction is not an order price. */
    private fun rankedValueSides(market: MarketUiModel, ctx: Context): List<String> =
        listOf("YES", "NO").mapNotNull { side ->
            val probability = modelProb(market, side) ?: return@mapNotNull null
            if (probability !in 0.0..1.0) return@mapNotNull null
            val ask = bestAsk(market, side, ctx) ?: return@mapNotNull null
            val liveBook = ctx.books[market.ticker]?.takeIf { !it.isEmpty() }
            val clip = if (liveBook != null) {
                LiveOrderSizer.sizeWithinDepth(
                    ask,
                    kotlin.math.floor((quotedSize(market, side, liveBook) ?: 0.0) + 1e-9).toInt(),
                    SignalConstants.LIVE_ALL_IN_CAP_USD,
                    ctx.settings.feeRate
                )
            } else {
                LiveOrderSizer.size(ask, SignalConstants.LIVE_ALL_IN_CAP_USD, ctx.settings.feeRate)
            }
            if (!clip.ok) return@mapNotNull null
            side to (probability - clip.allInUsd / clip.count)
        }.sortedByDescending { it.second }.map { it.first }

    fun longShotNote(
        maxAsk: Double,
        implied: Double?,
        model: Double?,
        targetProfitUsd: Double
    ): String {
        val cap = String.format(java.util.Locale.US, "%.0f¢", maxAsk.coerceIn(0.05, 0.40) * 100.0)
        val mkt = implied?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        val ai = model?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100.0) } ?: "—"
        return "Long-shot · ask ≤ $cap · market $mkt · AI $ai · $5 all-in · min profit \$${fmt(targetProfitUsd)} · Approve still required"
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
            gateNote = when (reason) {
                MARKET_CLOSED, WINDOW_CLOSED -> "This window is over — no live order will be sent"
                NO_BUYERS -> NO_BUYERS
                else -> "Approve stays off until sellers show up"
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
        val fromQuote = quoteBid(market, side)
        val fromTick = tickBid(tick, side)
        val fromBook = bookBid(book, side)
        return listOfNotNull(fromQuote, fromTick, fromBook).maxOrNull()
    }

    /**
     * Live sell bid: order book first, then the latest tick, then the
     * REST quote. Never takes the max of a stale higher quote and the
     * current book — that is what priced Dirk's 50 YES at 0.2¢ while
     * the book bid was 0.1¢.
     */
    fun freshBestBid(market: MarketUiModel, side: String, ctx: Context): Double? {
        bookBid(ctx.books[market.ticker], side)?.let { return it }
        tickBid(ctx.ticks[market.ticker], side)?.let { return it }
        return quoteBid(market, side)
    }

    private fun quoteBid(market: MarketUiModel, side: String): Double? =
        if (side == "YES") {
            KalshiPrice.usable(market.yesBid)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.noAsk)
        } else {
            KalshiPrice.usable(market.noBid)
                ?: KalshiPrice.impliedAskFromOppositeBid(market.yesAsk)
        }

    private fun tickBid(tick: MarketTick?, side: String): Double? {
        if (tick == null) return null
        return if (side == "YES") {
            KalshiPrice.usable(tick.yesBid)
        } else {
            KalshiPrice.usable(tick.noBid)
                ?: KalshiPrice.impliedAskFromOppositeBid(tick.yesAsk)
        }
    }

    private fun bookBid(book: BookLevelSnapshot?, side: String): Double? {
        if (book == null || book.isEmpty()) return null
        return if (side == "YES") {
            book.yes.maxByOrNull { it.first }?.first?.let { KalshiPrice.usable(it) }
        } else {
            book.no.maxByOrNull { it.first }?.first?.let { KalshiPrice.usable(it) }
        }
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
        // Prefer the live book to a tick or a REST quote. Taking the minimum
        // across observations at different times can fabricate a cheap ask.
        // A book with no sellers on this side must not fall back to an older
        // quote that suggests a fill is available.
        if (book != null && !book.isEmpty()) return bookAsk(side, book)
        return fromTick ?: fromQuote
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
