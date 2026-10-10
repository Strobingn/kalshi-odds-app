package com.dirk.kalshiodds.data.backfill

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.dollarsToCents
import com.dirk.kalshiodds.domain.CryptoMarkets
import org.json.JSONObject

/**
 * Gap-fill for the current open 15m window.
 *
 * Official live path (docs.kalshi.com):
 * `GET /series/{series_ticker}/markets/{ticker}/candlesticks`
 * with `start_ts`, `end_ts` (Unix seconds) and `period_interval=1`.
 *
 * Bid/ask closes are FixedPointDollars `*_dollars` strings
 * (`yes_bid.close_dollars`, `yes_ask.close_dollars`). Integer `close`
 * on archived candles is also dollars, not cents.
 *
 * Rate limits: token-bucket, default 10 tokens/request, 429 → backoff.
 * https://docs.kalshi.com/getting_started/rate_limits
 */
class LiveWindowBackfill(
    private val kalshi: HistoryTransport,
    private val spot: CoinbaseSpotBackfill? = null,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val maxRetries: Int = 4
) {
    fun candles(series: String, ticker: String, startMs: Long, endMs: Long): List<BidPoint> {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return emptyList()
        val startTs = (startMs / 1000L).coerceAtMost(endMs / 1000L)
        val endTs = (endMs / 1000L).coerceAtLeast(startTs)
        val q = mapOf(
            "start_ts" to startTs.toString(),
            "end_ts" to endTs.toString(),
            "period_interval" to PERIOD_INTERVAL_1M
        )
        val path = "/series/$series/markets/$ticker/candlesticks"
        val body = get(path, q) ?: return emptyList()
        return parseResponse(body).map { it.toBidPoint() }
    }

    fun spotPath(series: String, startMs: Long, endMs: Long): List<Pair<Long, Double>> {
        val engine = spot ?: return emptyList()
        val product = engine.productForSeries(series)
        return engine.candles(product, startMs, endMs, granularitySec = 60)
            .map { it.tMs to it.close }
            .filter { it.second.isFinite() && it.second > 0.0 }
    }

    private fun get(path: String, query: Map<String, String>): String? {
        var last: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return kalshi.get(path, query)
            } catch (_: HistoryTransport.Cancelled) {
                throw HistoryTransport.Cancelled()
            } catch (e: HistoryTransport.RateLimited) {
                last = e
                sleep((e.retryAfterMs * (1L shl attempt.coerceAtMost(3))).coerceAtMost(10_000L))
            } catch (e: HistoryTransport.HttpFail) {
                last = e
                if (e.code == 404) return null
                if (e.code in listOf(429, 502, 503)) {
                    sleep(1_000L shl attempt.coerceAtMost(4))
                } else {
                    throw e
                }
            }
        }
        throw last ?: HistoryTransport.HttpFail(0, "retries exhausted")
    }

    companion object {
        /** Official 1-minute bar. Docs: 1, 60, or 1440 only. */
        const val PERIOD_INTERVAL_1M = "1"
        const val LIVE_PATH = "/series/{series_ticker}/markets/{ticker}/candlesticks"
        const val HISTORICAL_PATH = "/historical/markets/{ticker}/candlesticks"
        const val TRADES_PATH = "/markets/trades"
        const val DOCS_CANDLES = "https://docs.kalshi.com/api-reference/market/get-market-candlesticks"
        const val DOCS_HISTORICAL = "https://docs.kalshi.com/api-reference/historical/get-historical-market-candlesticks"
        const val DOCS_TRADES = "https://docs.kalshi.com/api-reference/market/get-trades"
        const val DOCS_RATES = "https://docs.kalshi.com/getting_started/rate_limits"
        const val DOCS_FEES = "https://docs.kalshi.com/getting_started/fee_rounding"

        fun parseResponse(body: String): List<CandlePrint> {
            val arr = JSONObject(body).optJSONArray("candlesticks") ?: return emptyList()
            val out = ArrayList<CandlePrint>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                out.add(KalshiBackfillEngine.parseCandle(c))
            }
            return out.filter { it.endTs > 0L }
        }
    }
}

fun CandlePrint.toBidPoint(): BidPoint = BidPoint(
    tMs = if (endTs < 1_000_000_000_000L) endTs * 1000L else endTs,
    upBidCents = dollarsToCents(yesBid),
    downBidCents = dollarsToCents(noBid),
    spotUsd = null
)
