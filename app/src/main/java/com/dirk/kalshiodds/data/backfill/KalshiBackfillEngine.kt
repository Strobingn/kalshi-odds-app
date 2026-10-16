package com.dirk.kalshiodds.data.backfill

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.local.archive.BackfillCursorRow
import com.dirk.kalshiodds.data.local.archive.PricePathRow
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class BackfillProgress(
    val series: String,
    val processed: Int,
    val imported: Int,
    val skipped: Int,
    val cursor: String?,
    val lastTicker: String?,
    val message: String,
    val done: Boolean = false,
    val cancelled: Boolean = false
)

data class MarketPage(
    val markets: List<JSONObject>,
    val cursor: String?
)

data class CandlePrint(
    val endTs: Long,
    val yesBid: Double?,
    val noBid: Double?,
    val mid: Double?
)

/**
 * Pulls settled KXBTC15M 15m markets + 1-minute candlesticks.
 *
 * Live: `GET /markets?status=settled` then
 * `GET /series/{series}/markets/{ticker}/candlesticks`.
 * Older than [cutoff]: `GET /historical/markets` +
 * `GET /historical/markets/{ticker}/candlesticks`.
 *
 * Resumable via [BackfillCursorRow.cursor] / [lastTicker].
 */
class KalshiBackfillEngine(
    private val transport: HistoryTransport,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val pagePauseMs: Long = 80L,
    private val maxRetries: Int = 5
) {
    data class Result(
        val settled: List<SettledWindowRow>,
        val path: List<PricePathRow>,
        val cursor: BackfillCursorRow,
        val progress: BackfillProgress
    )

    fun cutoffMs(): Long? {
        val body = get("/historical/cutoff") ?: return null
        val o = JSONObject(body)
        val raw = o.optString("market_settled_ts").ifBlank {
            o.optJSONObject("cutoff")?.optString("market_settled_ts")
        }
        return parseIso(raw)
    }

    fun pageSettled(
        series: String,
        cursor: String?,
        minCloseMs: Long,
        limit: Int = 100,
        historical: Boolean
    ): MarketPage {
        val path = if (historical) "/historical/markets" else "/markets"
        val q = linkedMapOf(
            "series_ticker" to series,
            "limit" to limit.toString()
        )
        if (!historical) q["status"] = "settled"
        if (!cursor.isNullOrBlank()) q["cursor"] = cursor
        val body = get(path, q) ?: return MarketPage(emptyList(), null)
        val o = JSONObject(body)
        val arr = o.optJSONArray("markets") ?: JSONArray()
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val close = parseIso(m.optString("close_time"))
                ?: parseIso(m.optString("expiration_time"))
            if (close != null && close < minCloseMs) continue
            out.add(m)
        }
        return MarketPage(out, o.optString("cursor").ifBlank { null })
    }

    fun candles(series: String, ticker: String, openMs: Long, closeMs: Long, historical: Boolean): List<CandlePrint> {
        val start = (openMs / 1000L) - 60
        val end = (closeMs / 1000L) + 60
        val q = mapOf(
            "start_ts" to start.toString(),
            "end_ts" to end.toString(),
            "period_interval" to "1"
        )
        val path = if (historical) {
            "/historical/markets/$ticker/candlesticks"
        } else {
            "/series/$series/markets/$ticker/candlesticks"
        }
        val body = get(path, q) ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("candlesticks") ?: return emptyList()
        val out = ArrayList<CandlePrint>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(parseCandle(c))
        }
        return out
    }

    /**
     * One page of one series. Caller persists [Result] and decides whether
     * to continue. [cancel] is checked between markets.
     */
    fun step(
        series: String,
        cursor: BackfillCursorRow,
        minCloseMs: Long,
        historical: Boolean,
        pageSize: Int = 40,
        cancel: () -> Boolean = { false }
    ): Result {
        if (cancel()) {
            return Result(
                emptyList(), emptyList(),
                cursor.copy(status = "cancelled", updatedAtMs = nowMs()),
                BackfillProgress(series, cursor.processed, 0, 0, cursor.cursor, cursor.lastTicker, "cancelled", cancelled = true)
            )
        }
        val page = pageSettled(series, cursor.cursor, minCloseMs, pageSize, historical)
        val settled = ArrayList<SettledWindowRow>()
        val path = ArrayList<PricePathRow>()
        var imported = 0
        var skipped = 0
        var lastTicker = cursor.lastTicker
        var skipUntil = cursor.lastTicker?.takeIf { name ->
            page.markets.any { it.optString("ticker") == name }
        }
        for (m in page.markets) {
            if (cancel()) break
            val ticker = m.optString("ticker")
            if (ticker.isBlank() || !CryptoMarkets.isCryptoTicker(ticker)) {
                skipped += 1
                continue
            }
            if (skipUntil != null) {
                if (ticker == skipUntil) {
                    skipUntil = null
                    skipped += 1
                    continue
                }
                skipped += 1
                continue
            }
            val result = m.optString("result").lowercase()
            if (result != "yes" && result != "no") {
                skipped += 1
                continue
            }
            val closeMs = parseIso(m.optString("close_time"))
                ?: parseIso(m.optString("expiration_time"))
                ?: continue
            if (closeMs < minCloseMs) {
                skipped += 1
                continue
            }
            val openMs = parseIso(m.optString("open_time")) ?: (closeMs - 900_000L)
            val strike = m.optDouble("floor_strike").takeIf { m.has("floor_strike") && !m.isNull("floor_strike") }
            settled.add(
                SettledWindowRow(
                    ticker = ticker,
                    series = series,
                    result = result,
                    strikeUsd = strike,
                    openMs = openMs,
                    closeMs = closeMs,
                    importedAtMs = nowMs(),
                    source = if (historical) "kalshi-historical" else "kalshi"
                )
            )
            val prints = runCatching {
                candles(series, ticker, openMs, closeMs, historical)
            }.getOrElse { emptyList() }
            for (p in prints) {
                path.add(
                    PricePathRow(
                        ticker = ticker,
                        tMs = p.endTs * 1000L,
                        yesBid = p.yesBid,
                        noBid = p.noBid,
                        mid = p.mid
                    )
                )
            }
            imported += 1
            lastTicker = ticker
            sleep(pagePauseMs)
        }
        val nextCursor = if (page.cursor.isNullOrBlank()) null else page.cursor
        val done = nextCursor == null && skipUntil == null
        val next = cursor.copy(
            series = series,
            cursor = nextCursor,
            lastTicker = if (done) null else lastTicker,
            minCloseMs = minCloseMs,
            updatedAtMs = nowMs(),
            status = if (done) "done" else "running",
            processed = cursor.processed + imported
        )
        return Result(
            settled = settled,
            path = path,
            cursor = next,
            progress = BackfillProgress(
                series = series,
                processed = next.processed,
                imported = imported,
                skipped = skipped,
                cursor = next.cursor,
                lastTicker = lastTicker,
                message = if (done) "done $series" else "page $series +$imported",
                done = done
            )
        )
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()): String? {
        var last: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return transport.get(path, query)
            } catch (_: HistoryTransport.Cancelled) {
                throw HistoryTransport.Cancelled()
            } catch (e: HistoryTransport.RateLimited) {
                last = e
                sleep((e.retryAfterMs * (1L shl attempt.coerceAtMost(3))).coerceAtMost(10_000L))
            } catch (e: HistoryTransport.HttpFail) {
                last = e
                if (e.code == 404) return null
                if (e.code in listOf(429, 502, 503)) {
                    sleep((1_000L shl attempt.coerceAtMost(4)))
                } else {
                    throw e
                }
            }
        }
        throw last ?: HistoryTransport.HttpFail(0, "retries exhausted")
    }

    companion object {
        val SERIES = CryptoMarkets.DEFAULT_SERIES

        fun parseIso(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            return try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {
                raw.toLongOrNull()?.let { if (it < 1_000_000_000_000L) it * 1000L else it }
            }
        }

        fun parseCandle(c: JSONObject): CandlePrint {
            val end = c.optLong("end_period_ts", c.optLong("end_ts", 0L))
            val yesBid = candlePx(c.optJSONObject("yes_bid"))
            val yesAsk = candlePx(c.optJSONObject("yes_ask"))
            val price = candlePx(c.optJSONObject("price"))
            val mid = when {
                yesBid != null && yesAsk != null -> (yesBid + yesAsk) / 2.0
                price != null -> price
                else -> yesBid
            }
            val noBid = yesAsk?.let { (1.0 - it).coerceIn(0.0, 1.0) }
            return CandlePrint(endTs = end, yesBid = yesBid, noBid = noBid, mid = mid)
        }

        private fun candlePx(o: JSONObject?): Double? {
            if (o == null) return null
            val d = when {
                o.has("close_dollars") -> o.optString("close_dollars").toDoubleOrNull()
                o.has("close") -> o.optDouble("close")
                else -> null
            }
            return d?.takeIf { it.isFinite() && it in 0.0..1.0 }
        }
    }
}
