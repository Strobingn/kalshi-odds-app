package com.dirk.kalshiodds.domain

/**
 * Picks the currently open 15m contract per series from a REST listing.
 *
 * Official listing: GET /markets?series_ticker=KXBTC15M|KXETH15M|KXSOL15M&status=open
 * https://docs.kalshi.com/api-reference/market/get-markets
 *
 * `status=open` matches response `status=active`. Past [MarketUiModel.closeTimeEpochMs]
 * the contract is not tradable even if Kalshi has not flipped the status yet.
 */
object ActiveMarketResolver {
    const val GRACE_AFTER_CLOSE_MS = 3_000L
    const val RETRY_MS = 5_000L

    fun forSeries(
        series: String,
        markets: List<MarketUiModel>,
        nowMs: Long
    ): MarketUiModel? {
        val live = MarketLifecycle.tradable(markets, nowMs).filter {
            CryptoMarkets.inferSeries(it.ticker).equals(series, ignoreCase = true)
        }
        return MarketLifecycle.currentWindow(live)
    }

    fun activeBySeries(
        markets: List<MarketUiModel>,
        series: Collection<String>,
        nowMs: Long
    ): Map<String, MarketUiModel> = series
        .mapNotNull { s -> forSeries(s, markets, nowMs)?.let { s to it } }
        .toMap()

    fun tickers(
        markets: List<MarketUiModel>,
        series: Collection<String> = CryptoMarkets.DEFAULT_SERIES,
        nowMs: Long
    ): Set<String> = activeBySeries(markets, series, nowMs).values.map { it.ticker }.toSet()

    fun nextWakeMs(
        active: Collection<MarketUiModel>,
        retrying: Collection<String>,
        nowMs: Long,
        graceAfterCloseMs: Long = GRACE_AFTER_CLOSE_MS,
        retryMs: Long = RETRY_MS
    ): Long? {
        val closes = active.mapNotNull { it.closeTimeEpochMs }.minOrNull()?.plus(graceAfterCloseMs)
        val retryAt = if (retrying.isNotEmpty()) nowMs + retryMs else null
        return listOfNotNull(closes, retryAt).minOrNull()
    }
}
