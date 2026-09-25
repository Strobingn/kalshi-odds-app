package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import kotlin.math.abs

/**
 * Home list = one current-window card per watched coin, actionable first.
 * Selection uses existing [MarketLifecycle.currentWindow] / [BetCall.sortKey].
 */
object HomeMarkets {

    fun currentWindowCards(
        markets: List<MarketUiModel>,
        settings: SignalSettings,
        nowMs: Long = System.currentTimeMillis()
    ): List<MarketUiModel> {
        val live = MarketLifecycle.tradable(markets, nowMs)
        val groups = buildList {
            if (settings.watchBtc) add(live.filter { CryptoMarkets.kindFor(it.ticker) == SeriesKind.BTC })
            if (settings.watchEth) add(live.filter { CryptoMarkets.kindFor(it.ticker) == SeriesKind.ETH })
            if (settings.watchSol) add(live.filter { CryptoMarkets.kindFor(it.ticker) == SeriesKind.SOL })
            val extra = live.filter { CryptoMarkets.kindFor(it.ticker) == SeriesKind.CRYPTO }
            if (extra.isNotEmpty()) add(extra)
        }
        return groups.mapNotNull { MarketLifecycle.currentWindow(it) }
    }

    fun ranked(
        markets: List<MarketUiModel>,
        decisions: Map<String, BetCall.Decision>,
        settings: SignalSettings
    ): List<MarketUiModel> = markets.sortedWith(
        compareBy<MarketUiModel> { market ->
            decisions[market.ticker]?.let { BetCall.sortKey(it) } ?: 1
        }
            .thenByDescending { it.passedFilter && !it.muted }
            .thenByDescending {
                if (settings.rankByNetEv) abs(it.netEdgePp ?: it.edgePp ?: 0.0)
                else abs(it.edgePp ?: 0.0)
            }
    )

    fun decisions(
        markets: List<MarketUiModel>,
        ctx: TicketBuilder.Context
    ): Map<String, BetCall.Decision> = markets.associate { it.ticker to BetCall.decide(it, ctx) }

    fun best(
        rankedMarkets: List<MarketUiModel>,
        decisions: Map<String, BetCall.Decision>
    ): Pair<MarketUiModel, BetCall.Decision>? {
        val first = rankedMarkets.firstOrNull() ?: return null
        val call = decisions[first.ticker] ?: return null
        return first to call
    }
}
