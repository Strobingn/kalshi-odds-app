package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import java.util.UUID

/**
 * Builds approve-gated tickets from scored markets. Pure / local —
 * never calls Kalshi and never submits an order.
 */
object TicketBuilder {

    data class Context(
        val settings: SignalSettings,
        val alertsPaused: Boolean,
        val books: Map<String, LocalOrderBook> = emptyMap(),
        val idFactory: () -> String = { UUID.randomUUID().toString() },
        val nowMs: Long = System.currentTimeMillis()
    )

    fun proposeAll(markets: List<MarketUiModel>, ctx: Context): List<TradeTicket> {
        if (!ctx.settings.ticketsEnabled) return emptyList()
        return markets.mapNotNull { propose(it, ctx) }
            .sortedByDescending { it.maxPayoutUsd }
    }

    fun propose(market: MarketUiModel, ctx: Context): TradeTicket? {
        val settings = ctx.settings
        if (!settings.ticketsEnabled) return null

        if (settings.ticketRespectGates) {
            if (!market.passedFilter) return null
            if (market.muted) return null
            if (ctx.alertsPaused) return null
        }

        val side = resolveSide(market) ?: return null
        val ask = bestAsk(market, side) ?: return null
        val levels = askLevels(market, side, ctx.books[market.ticker])
        val quoted = quotedSize(market, side, ctx.books[market.ticker])
        val stake = PayoutGate.clipStake(settings.ticketStakeUsd)
        val sizing = PayoutGate.evaluate(
            stakeUsd = stake,
            bestAsk = ask,
            askLevels = levels,
            quotedSize = quoted,
            minPayoutUsd = SignalConstants.DEFAULT_MIN_PAYOUT_USD
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
            stakeUsd = stake,
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
            gateNote = gateSummary(market, ctx),
            createdAtMs = ctx.nowMs
        )
    }

    fun resolveSide(market: MarketUiModel): String? {
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
    fun askLevels(market: MarketUiModel, side: String, book: LocalOrderBook?): List<Pair<Double, Double>> {
        if (book != null && !book.isEmpty()) {
            return if (side == "YES") {
                book.noLevels().map { (noPx, size) -> (1.0 - noPx).coerceIn(0.0, 1.0) to size }
            } else {
                book.yesLevels().map { (yesPx, size) -> (1.0 - yesPx).coerceIn(0.0, 1.0) to size }
            }
        }
        return emptyList()
    }

    fun quotedSize(market: MarketUiModel, side: String, book: LocalOrderBook?): Double? {
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
        }
        return bits.joinToString(" · ")
    }
}
