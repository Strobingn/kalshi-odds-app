package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.parseCloseEpochMs

/**
 * Fetches KXBTCD 17:00 ET markets, series fee metadata, and trades for
 * held / resting tickers only. Pages conservatively.
 */
class D3MarketClient(
    private val resolveApi: () -> KalshiApi
) {
    suspend fun loadSchedule(): D3Fees.Schedule {
        val series = resolveApi().getSeries(D3Constants.SERIES).series
        return D3Fees.fromSeries(
            feeType = series?.feeType,
            feeMultiplier = series?.feeMultiplier,
            makerMultiplier = series?.makerFeeMultiplier
        )
    }

    suspend fun loadFivePmQuotes(): List<D3Quote> {
        val api = resolveApi()
        val out = ArrayList<D3Quote>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = api.getMarkets(
                seriesTicker = D3Constants.SERIES,
                status = "open",
                limit = 200,
                cursor = cursor
            )
            pages += 1
            for (m in resp.markets) {
                val q = m.toD3Quote() ?: continue
                if (D3Window.closeIsFivePmEt(q.closeTimeEpochMs)) out += q
            }
            cursor = resp.cursor?.takeIf { it.isNotBlank() }
        } while (cursor != null && pages < 4 && out.size < 80)
        return out
    }

    suspend fun loadTrades(ticker: String, minTsMs: Long? = null): List<D3TradePrint> {
        val resp = resolveApi().getTrades(ticker = ticker, limit = 100, minTs = minTsMs?.div(1000L))
        return resp.trades.mapNotNull { t ->
            val px = KalshiPrice.parseDollars(t.yesPriceDollars)
                ?: t.yesPrice?.let { if (it > 1.0) it / 100.0 else it }?.let { KalshiPrice.usable(it) }
                ?: return@mapNotNull null
            val count = t.count?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
            val ts = t.createdTimeTs?.let { if (it < 10_000_000_000L) it * 1000L else it }
                ?: parseCloseEpochMs(t.createdTime)
                ?: 0L
            D3TradePrint(
                ticker = t.ticker ?: ticker,
                yesPrice = px,
                count = count,
                createdAtMs = ts
            )
        }
    }
}

fun MarketDto.toD3Quote(): D3Quote? {
    if (!D3Constants.isTicker(ticker)) return null
    return D3Quote(
        ticker = ticker,
        eventTicker = eventTicker,
        title = title.orEmpty().ifBlank { ticker },
        subtitle = yesSubTitle,
        strikeUsd = floorStrike,
        yesBid = KalshiPrice.parseDollars(yesBidDollars),
        yesAsk = KalshiPrice.parseDollars(yesAskDollars),
        noBid = KalshiPrice.parseDollars(noBidDollars),
        noAsk = KalshiPrice.parseDollars(noAskDollars),
        yesBidSize = KalshiPrice.parseCount(yesBidSizeFp),
        yesAskSize = KalshiPrice.parseCount(yesAskSizeFp),
        closeTimeEpochMs = parseCloseEpochMs(closeTime)
            ?: parseCloseEpochMs(expirationTime)
            ?: parseCloseEpochMs(expectedExpirationTime),
        status = status
    )
}
