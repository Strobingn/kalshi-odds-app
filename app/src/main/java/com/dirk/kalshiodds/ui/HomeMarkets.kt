package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import kotlin.math.abs

/**
 * Home list = one Bitcoin card from [CryptoMarkets.DEFAULT_SERIES].
 * The slot is keyed by series and shows only the current window
 * (`open_time <= now < close_time`). A missing listing keeps the slot
 * with [NEXT_WINDOW_LOADING]. Never sorted by edge — [ranked] / [best]
 * feed "This window" only.
 */
object HomeMarkets {
    val CARD_SERIES: List<String> = CryptoMarkets.DEFAULT_SERIES
    const val NEXT_WINDOW_LOADING = "Next window loading"

    data class CoinCard(
        val series: String,
        val market: MarketUiModel?
    ) {
        val loading: Boolean get() = market == null
        val key: String get() = series
    }

    fun coinCards(
        markets: List<MarketUiModel>,
        nowMs: Long = System.currentTimeMillis()
    ): List<CoinCard> = CARD_SERIES.map { series ->
        val pool = markets.filter {
            CryptoMarkets.inferSeries(it.ticker).equals(series, ignoreCase = true)
        }
        CoinCard(series, MarketLifecycle.currentOpenWindow(pool, nowMs))
    }

    @Suppress("UNUSED_PARAMETER")
    fun currentWindowCards(
        markets: List<MarketUiModel>,
        settings: SignalSettings,
        nowMs: Long = System.currentTimeMillis()
    ): List<MarketUiModel> = coinCards(markets, nowMs).mapNotNull { it.market }

    /**
     * Rank for the "This window" summary only. The Bitcoin card stays
     * in [CARD_SERIES] order and is never reordered by this key.
     */
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
