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
    private val resolveApi: () -> KalshiApi,
    private val rateLimiter: com.dirk.kalshiodds.data.api.KalshiRateLimiter? = null,
    private val feedHealth: com.dirk.kalshiodds.data.api.KalshiFeedHealth? = null
) {
    suspend fun loadSchedule(): D3Fees.Schedule = guarded(com.dirk.kalshiodds.data.api.KalshiRateLimiter.Lane.D3) {
        val series = resolveApi().getSeries(D3Constants.SERIES).series
        D3Fees.fromSeries(
            feeType = series?.feeType,
            feeMultiplier = series?.feeMultiplier,
            makerMultiplier = series?.makerFeeMultiplier
        )
    } ?: D3Fees.fromSeries(null, null, null)

    suspend fun loadFivePmQuotes(): List<D3Quote> = loadFivePmQuotes(D3Constants.SERIES)

    /** 5 PM ET daily above/below quotes for KXBTCD / KXETHD / KXSOLD (REST, rate-limited lane). */
    suspend fun loadFivePmQuotes(seriesTicker: String): List<D3Quote> = guarded(com.dirk.kalshiodds.data.api.KalshiRateLimiter.Lane.D3) {
        val api = resolveApi()
        val out = ArrayList<D3Quote>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = api.getMarkets(
                seriesTicker = seriesTicker,
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
        out
    } ?: emptyList()

    suspend fun loadTrades(ticker: String, minTsMs: Long? = null): List<D3TradePrint> =
        guarded(com.dirk.kalshiodds.data.api.KalshiRateLimiter.Lane.D3) {
        val resp = resolveApi().getTrades(ticker = ticker, limit = 100, minTs = minTsMs?.div(1000L))
        resp.trades.mapNotNull { t ->
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
    } ?: emptyList()

    private suspend fun <T> guarded(
        lane: com.dirk.kalshiodds.data.api.KalshiRateLimiter.Lane,
        block: suspend () -> T
    ): T? {
        val wait = rateLimiter?.reserve(lane) ?: 0L
        if (wait > 0L) {
            feedHealth?.show(com.dirk.kalshiodds.data.api.KalshiRequestStatus.rateLimited(wait))
            return null
        }
        return try {
            val value = block()
            rateLimiter?.onSuccess()
            feedHealth?.clear()
            value
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val retry = if (com.dirk.kalshiodds.data.api.KalshiRequestStatus.shouldBackoff(e)) {
                rateLimiter?.onFailure(
                    com.dirk.kalshiodds.data.api.KalshiRequestStatus.httpCode(e),
                    com.dirk.kalshiodds.data.api.KalshiRequestStatus.retryAfterMs(e)
                ) ?: 1_000L
            } else {
                0L
            }
            feedHealth?.note(e, retry)
            null
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
