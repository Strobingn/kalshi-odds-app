package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import kotlin.math.abs

/**
 * Home list = BTC, ETH and SOL 15m cards (0.3.39 restores the pre-0.3.14 three-coin Home),
 * filtered by the coin selector. Each slot is keyed by series and shows only that coin's window
 * (`open_time <= now < close_time`). A missing listing keeps the slot
 * with [NEXT_WINDOW_LOADING]. Never sorted by edge — [ranked] / [best]
 * feed "This window" only.
 */
object HomeMarkets {
    val CARD_SERIES: List<String> = CryptoMarkets.DEFAULT_SERIES
    const val NEXT_WINDOW_LOADING = "Next window loading"

    /** Home coin selector. ALL shows every coin; the others show one coin's 15m and daily views. */
    enum class Coin(val label: String, val fifteen: String?, val daily: String?) {
        ALL("All", null, null),
        BTC("BTC", com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTC, com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTCD),
        ETH("ETH", com.dirk.kalshiodds.data.api.KalshiApi.SERIES_ETH, com.dirk.kalshiodds.data.api.KalshiApi.SERIES_ETHD),
        SOL("SOL", com.dirk.kalshiodds.data.api.KalshiApi.SERIES_SOL, com.dirk.kalshiodds.data.api.KalshiApi.SERIES_SOLD);

        fun showsSeries(series: String): Boolean =
            this == ALL || series.equals(fifteen, ignoreCase = true) || series.equals(daily, ignoreCase = true)
    }

    fun coinCards(
        markets: List<MarketUiModel>,
        nowMs: Long,
        coin: Coin
    ): List<CoinCard> = coinCards(markets, nowMs).filter { coin.showsSeries(it.series) }

    /** Daily series to show for [coin], in Home order. */
    fun dailySeries(coin: Coin): List<String> =
        CryptoMarkets.DAILY_SERIES.filter { coin.showsSeries(it) }

    /**
     * The [count] daily 5 PM strikes nearest 50¢ for [series]. Only quotes whose ticker belongs
     * to [series] are used, so one coin's daily prices can never show under another coin.
     */
    fun dailyRows(
        quotesBySeries: Map<String, List<com.dirk.kalshiodds.signal.d3.D3Quote>>,
        series: String,
        count: Int = 5
    ): List<com.dirk.kalshiodds.signal.d3.D3Quote> =
        quotesBySeries[series].orEmpty()
            .filter { CryptoMarkets.inferSeries(it.ticker).equals(series, ignoreCase = true) }
            .filter { it.yesAsk != null || it.noAsk != null }
            .sortedBy { q ->
                val mid = listOfNotNull(q.yesBid, q.yesAsk).takeIf { it.isNotEmpty() }?.average() ?: 0.5
                abs(mid - 0.5)
            }
            .take(count)
            .sortedBy { it.strikeUsd ?: Double.MAX_VALUE }

    /** A buyable market built only from this daily quote's own prices (no cross-coin data). */
    fun dailyMarket(q: com.dirk.kalshiodds.signal.d3.D3Quote): MarketUiModel {
        val kind = CryptoMarkets.kindFor(q.ticker)
        val label = when (kind) {
            com.dirk.kalshiodds.domain.SeriesKind.ETH -> "Ethereum"
            com.dirk.kalshiodds.domain.SeriesKind.SOL -> "Solana"
            else -> "Bitcoin"
        }
        val mid = listOfNotNull(q.yesBid, q.yesAsk).takeIf { it.isNotEmpty() }?.average()
        return MarketUiModel(
            ticker = q.ticker,
            title = q.title,
            subtitle = q.subtitle,
            floorStrike = q.strikeUsd,
            yesBid = q.yesBid,
            yesAsk = q.yesAsk,
            noBid = q.noBid,
            noAsk = q.noAsk,
            yesAskSize = q.yesAskSize,
            lastPrice = null,
            yesProbabilityPercent = mid?.let { it * 100.0 },
            noProbabilityPercent = mid?.let { 100.0 - it * 100.0 },
            spreadDollars = if (q.yesBid != null && q.yesAsk != null) q.yesAsk - q.yesBid else null,
            volume = null,
            volume24h = null,
            closeTimeLocal = null,
            closeTimeEpochMs = q.closeTimeEpochMs,
            status = q.status,
            seriesLabel = "$label daily 5 PM"
        )
    }

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
