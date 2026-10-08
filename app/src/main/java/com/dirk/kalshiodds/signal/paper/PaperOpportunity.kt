package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.TicketBuilder

/**
 * Research-only paper opportunity. Unlike [TicketBuilder], this does not use
 * the live $5 approval cap or the live minimum-profit display rule. It still
 * requires a current executable touch, visible quantity, and exact fee.
 */
object PaperOpportunity {
    data class Candidate(
        val ticker: String,
        val side: String,
        val fairSideProbability: Double,
        val ask: Double,
        val visibleContracts: Int,
        val feePerContractUsd: Double,
        val expectedNetPerContractUsd: Double,
        val source: String
    )

    fun best(market: MarketUiModel, context: TicketBuilder.Context): Candidate? {
        val fairYes = market.aiYesPercent?.div(100.0)?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: return null
        return listOfNotNull(
            candidate(market, "YES", fairYes, context),
            candidate(market, "NO", 1.0 - fairYes, context)
        ).maxByOrNull { it.expectedNetPerContractUsd }
            ?.takeIf { it.expectedNetPerContractUsd > 0.0 }
    }

    fun all(markets: List<MarketUiModel>, context: TicketBuilder.Context): List<Candidate> =
        markets.mapNotNull { best(it, context) }

    private fun candidate(
        market: MarketUiModel,
        side: String,
        fairSideProbability: Double,
        context: TicketBuilder.Context
    ): Candidate? {
        val ask = KalshiPrice.usable(TicketBuilder.bestAsk(market, side, context)) ?: return null
        val visible = TicketBuilder.quotedSize(market, side, context.books[market.ticker])
            ?.toInt()?.takeIf { it > 0 } ?: return null
        val fee = LiveOrderSizer.feeUsd(1, ask, context.settings.feeRate)
        val expected = fairSideProbability - ask - fee
        return Candidate(
            ticker = market.ticker,
            side = side,
            fairSideProbability = fairSideProbability,
            ask = ask,
            visibleContracts = visible,
            feePerContractUsd = fee,
            expectedNetPerContractUsd = expected,
            source = "residual paper research"
        )
    }
}
