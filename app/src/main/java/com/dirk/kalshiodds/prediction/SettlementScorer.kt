package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.data.api.KalshiApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves open prediction-log / paper tickers against settled Kalshi
 * markets. Restricted to KXBTC15M the app tracked plus held KXBTCD (D3)
 * tickets, only after close_time,
 * with 5s/15s/30s/60s backoff, in-flight dedupe, and 429 Retry-After.
 * Due tickers for one series share a single GET.
 */
class SettlementScorer(
    private val resolveApi: () -> KalshiApi,
    private val logStore: PredictionLogStore,
    private val extraOpenTickers: () -> Set<String> = { emptySet() },
    private val onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> },
    private val closeTimeOf: (String) -> Long? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val rateLimiter: com.dirk.kalshiodds.data.api.KalshiRateLimiter? = null,
    private val feedHealth: com.dirk.kalshiodds.data.api.KalshiFeedHealth? = null
) {
    constructor(
        api: KalshiApi,
        logStore: PredictionLogStore,
        extraOpenTickers: () -> Set<String> = { emptySet() },
        onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> },
        closeTimeOf: (String) -> Long? = { null },
        nowMs: () -> Long = { System.currentTimeMillis() }
    ) : this({ api }, logStore, extraOpenTickers, onMarketSettled, closeTimeOf, nowMs)

    private val mutex = Mutex()
    private val schedules = ConcurrentHashMap<String, SettlementPollPolicy.Schedule>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val closeTimes = ConcurrentHashMap<String, Long>()
    /** Cursor for the min_close_ts walk, per series, so a long gap is not re-read from page one. */
    private val staleCursor = ConcurrentHashMap<String, String>()
    @Volatile private var lastRunMs: Long = 0L
    @Volatile private var globalHoldUntilMs: Long = 0L

    fun noteCloseTime(ticker: String, closeTimeMs: Long) {
        if (!SettlementPollPolicy.isPollableTicker(ticker) && !SettlementPollPolicy.isD3Ticker(ticker)) return
        if (closeTimeMs <= 0L) return
        closeTimes[ticker.uppercase()] = closeTimeMs
    }

    fun closeTime(ticker: String): Long? =
        closeTimes[ticker.uppercase()] ?: closeTimeOf(ticker.uppercase()) ?: closeTimeOf(ticker)
            ?: com.dirk.kalshiodds.decision.ScalpTicker.closeMs(ticker)

    suspend fun maybeScore(
        nowMs: Long = this.nowMs(),
        minIntervalMs: Long = com.dirk.kalshiodds.data.api.KalshiPollBudget.SETTLEMENT_MS
    ) {
        val hold = globalHoldUntilMs
        if (nowMs < hold) return
        if (minIntervalMs > 0L && nowMs - lastRunMs < minIntervalMs) return
        mutex.withLock {
            if (nowMs < globalHoldUntilMs) return
            if (minIntervalMs > 0L && nowMs - lastRunMs < minIntervalMs) return
            lastRunMs = nowMs
            scoreOnce(nowMs)
        }
    }

    private suspend fun scoreOnce(nowMs: Long) {
        val open = logStore.readAll().filter { it.outcome == null }
        val extra = extraOpenTickers()
        val tracked = SettlementPollPolicy.mergeTracked(open, extra) { ticker ->
            closeTime(ticker)
        }
        val heldD3 = extra.filter { SettlementPollPolicy.isD3Ticker(it) }.map { it.uppercase() }.toSet()
        val due = SettlementPollPolicy.candidates(
            tracked = tracked,
            nowMs = nowMs,
            schedules = schedules,
            inFlight = inFlight,
            globalHoldUntilMs = globalHoldUntilMs,
            heldD3 = heldD3
        )
        val accepted = due.filter { inFlight.add(it) }
        if (accepted.isEmpty()) return
        try {
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (ticker in accepted) {
                val series = SettlementPollPolicy.seriesOf(ticker) ?: continue
                groups.getOrPut(series) { ArrayList() }.add(ticker)
            }
            for ((series, tickers) in groups) {
                pollSeries(series, tickers, nowMs)
            }
        } finally {
            accepted.forEach { inFlight.remove(it) }
        }
    }

    /**
     * Fresh windows share one newest-settled page. Windows closed more than an hour
     * ago are not on that page, so they are looked up by ticker (then one
     * `min_close_ts` page) and wait longer between misses.
     * Tickers that are not past close never reach this method.
     */
    private suspend fun pollSeries(series: String, tickers: List<String>, nowMs: Long) {
        val fresh = ArrayList<String>()
        val stale = ArrayList<String>()
        for (ticker in tickers) {
            val close = closeTime(ticker)
            if (close != null && nowMs - close >= SettlementPollPolicy.STALE_AFTER_MS) stale.add(ticker)
            else fresh.add(ticker)
        }
        if (fresh.isNotEmpty()) pollNewest(series, fresh, nowMs)
        if (stale.isNotEmpty() && nowMs >= globalHoldUntilMs) pollStale(series, stale, nowMs)
    }

    /** Newest settled page. Enough for windows that closed in the last hour. */
    private suspend fun pollNewest(series: String, tickers: List<String>, nowMs: Long) {
        try {
            val resp = resolveApi().getMarkets(
                seriesTicker = series,
                status = "settled",
                // 0.3.52: newest settled first; 200 full market bodies per poll was ~MBs of JSON for a handful of tickers.
                limit = (tickers.size + 12).coerceIn(12, 60)
            )
            rateLimiter?.onSuccess()
            feedHealth?.clear()
            val byTicker = resp.markets.associateBy { it.ticker.uppercase() }
            for (ticker in tickers) {
                val result = normalizeResult(byTicker[ticker]?.result)
                if (result != null) {
                    applyResult(ticker, result)
                    schedules.remove(ticker)
                } else {
                    val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
                    schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
                }
            }
        } catch (e: Exception) {
            recordMiss(tickers, e, nowMs, stale = false)
        }
    }

    /**
     * Direct ticker lookup for windows the newest page has already scrolled past.
     * One batch per pass; the rest stay due and go out on a later poll.
     * Tickers missing from that response get one `min_close_ts` page.
     */
    private suspend fun pollStale(series: String, tickers: List<String>, nowMs: Long) {
        val batch = tickers.take(SettlementPollPolicy.STALE_BATCH)
        val found = HashMap<String, com.dirk.kalshiodds.data.dto.MarketDto>()
        try {
            val listed = resolveApi().getMarketsByTickers(
                seriesTicker = series,
                status = "settled",
                tickers = batch.joinToString(","),
                limit = batch.size.coerceAtLeast(1)
            )
            rateLimiter?.onSuccess()
            feedHealth?.clear()
            for (market in listed.markets) found[market.ticker.uppercase()] = market
        } catch (e: Exception) {
            recordMiss(batch, e, nowMs, stale = true)
            return
        }
        val absent = batch.filter { found[it.uppercase()] == null }
        if (absent.isNotEmpty() && nowMs >= globalHoldUntilMs) {
            try {
                val page = pageOlderSettled(series, absent)
                rateLimiter?.onSuccess()
                feedHealth?.clear()
                for (market in page.markets) found[market.ticker.uppercase()] = market
            } catch (e: Exception) {
                recordMiss(absent, e, nowMs, stale = true)
            }
        }
        for (ticker in batch) {
            val held = schedules[ticker]?.nextAttemptMs?.let { it > nowMs } == true
            val result = normalizeResult(found[ticker.uppercase()]?.result)
            if (result != null) {
                applyResult(ticker, result)
                schedules.remove(ticker)
            } else if (!held) {
                val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
                schedules[ticker] = SettlementPollPolicy.afterStaleMiss(prev, nowMs)
            }
        }
    }

    private suspend fun pageOlderSettled(
        series: String,
        tickers: List<String>
    ): com.dirk.kalshiodds.data.dto.MarketsResponse {
        val oldest = tickers.mapNotNull { closeTime(it) }.minOrNull() ?: 0L
        val page = resolveApi().getSettledSince(
            seriesTicker = series,
            status = "settled",
            minCloseTs = (oldest / 1000L) - 120L,
            limit = 200,
            cursor = staleCursor[series]
        )
        val next = page.cursor?.takeIf { it.isNotBlank() }
        if (next == null) staleCursor.remove(series) else staleCursor[series] = next
        return page
    }

    private fun recordMiss(tickers: List<String>, error: Throwable, nowMs: Long, stale: Boolean) {
        noteTransport(error)
        val limited = SettlementPollPolicy.isRateLimited(error)
        val retry = if (limited && error is HttpException) SettlementPollPolicy.retryAfterMs(error) else null
        for (ticker in tickers) {
            val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
            val next = when {
                limited -> SettlementPollPolicy.after429(prev, nowMs, retry)
                stale -> SettlementPollPolicy.afterStaleMiss(prev, nowMs)
                else -> SettlementPollPolicy.afterMiss(prev, nowMs)
            }
            schedules[ticker] = next
            if (limited) globalHoldUntilMs = maxOf(globalHoldUntilMs, next.nextAttemptMs)
        }
    }

    private fun noteTransport(error: Throwable) {
        if (!com.dirk.kalshiodds.data.api.KalshiRequestStatus.shouldBackoff(error)) return
        val wait = rateLimiter?.onFailure(
            com.dirk.kalshiodds.data.api.KalshiRequestStatus.httpCode(error),
            com.dirk.kalshiodds.data.api.KalshiRequestStatus.retryAfterMs(error),
                local = com.dirk.kalshiodds.data.api.KalshiRequestStatus.isLocalThrottle(error)
        ) ?: com.dirk.kalshiodds.data.api.KalshiRequestStatus.retryAfterMs(error) ?: 1_000L
        feedHealth?.note(error, wait)
    }

    private suspend fun applyResult(ticker: String, raw: String?) {
        val result = normalizeResult(raw) ?: return
        logStore.applySettlement(ticker, result)
        runCatching { onMarketSettled(ticker, result) }
    }

    companion object {
        fun normalizeResult(raw: String?): String? {
            val n = raw?.lowercase()?.trim().orEmpty()
            return when (n) {
                "yes", "no", "void" -> n
                "scalar", "cancelled", "canceled", "invalid" -> "void"
                else -> null
            }
        }
    }
}
