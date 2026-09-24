package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import java.util.UUID

/**
 * Builds approve-gated tickets from scored markets. Pure / local —
 * never calls Kalshi and never submits an order.
 */
object TicketBuilder {

    data class Context(
        val settings: SignalSettings,
        val alertsPaused: Boolean,
        val books: Map<String, BookLevelSnapshot> = emptyMap(),
        val idFactory: () -> String = { UUID.randomUUID().toString() },
        val nowMs: Long = System.currentTimeMillis()
    )

    fun proposeAll(markets: List<MarketUiModel>, ctx: Context): List<TradeTicket> {
        if (!ctx.settings.ticketsEnabled) return emptyList()
        val hunter = markets.mapNotNull { proposeHunter(it, ctx) }
        val configured = markets.mapNotNull { propose(it, ctx) }
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

    /** User-tapped Buy. Sizes at the touch even when payout < $25. */
    fun proposeManual(market: MarketUiModel, side: String, ctx: Context): TradeTicket? {
        if (!ctx.settings.ticketsEnabled) return null
        val want = side.uppercase().let { if (it == "NO") "NO" else "YES" }
        val stake = PayoutGate.clipStake(
            ctx.settings.ticketStakeUsd.coerceAtLeast(SignalConstants.HUNTER_STAKE_USD)
        )
        return buildTicket(
            market = market,
            side = want,
            ctx = ctx,
            stakeUsd = stake,
            minPayoutUsd = SignalConstants.CONTRACT_SETTLEMENT_USD,
            kind = TicketKind.MANUAL,
            requireGates = false
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
        if (requireGates) {
            if (!market.passedFilter) return null
            if (market.muted) return null
            if (ctx.alertsPaused) return null
        }
        val ask = bestAsk(market, side) ?: return null
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
            yesLimitPrice = yesLimit.coerceIn(0.01, 0.99),
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

    fun bestAsk(market: MarketUiModel, side: String): Double? {
        return if (side == "YES") {
            market.yesAsk
                ?: market.noBid?.let { (1.0 - it).coerceIn(0.01, 0.99) }
        } else {
            market.noAsk
                ?: market.yesBid?.let { (1.0 - it).coerceIn(0.01, 0.99) }
        }
    }

    /**
     * Ask levels for the chosen outcome, cheapest first, in that side's dollars.
     * YES asks = local NO bids at `(1 − noPrice)`. NO asks = local YES bids at
     * `(1 − yesPrice)`.
     */
    fun askLevels(market: MarketUiModel, side: String, book: BookLevelSnapshot?): List<Pair<Double, Double>> {
        if (book != null && !book.isEmpty()) {
            return if (side == "YES") {
                book.no.map { (noPx, size) -> (1.0 - noPx).coerceIn(0.0, 1.0) to size }
            } else {
                book.yes.map { (yesPx, size) -> (1.0 - yesPx).coerceIn(0.0, 1.0) to size }
            }
        }
        return emptyList()
    }

    fun quotedSize(market: MarketUiModel, side: String, book: BookLevelSnapshot?): Double? {
        if (book != null && !book.isEmpty()) {
            val levels = askLevels(market, side, book)
            val ask = bestAsk(market, side) ?: return null
            return levels.filter { it.first <= ask + 1e-9 }.sumOf { it.second }
        }
        return listOfNotNull(market.volume, market.openInterest, market.liquidityDollars).maxOrNull()
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
