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
    @Volatile private var lastRunMs: Long = 0L
    @Volatile private var globalHoldUntilMs: Long = 0L

    fun noteCloseTime(ticker: String, closeTimeMs: Long) {
        if (!SettlementPollPolicy.isPollableTicker(ticker) && !SettlementPollPolicy.isD3Ticker(ticker)) return
        if (closeTimeMs <= 0L) return
        closeTimes[ticker.uppercase()] = closeTimeMs
    }

    fun closeTime(ticker: String): Long? =
        closeTimes[ticker.uppercase()] ?: closeTimeOf(ticker.uppercase()) ?: closeTimeOf(ticker)

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
     * One settled-market page per series. Tickers that are not past close
     * never reach this method. KXBTCD is included only for held D3 tickets.
     */
    private suspend fun pollSeries(series: String, tickers: List<String>, nowMs: Long) {
        try {
            val resp = resolveApi().getMarkets(
                seriesTicker = series,
                status = "settled",
                limit = 200
            )
            val byTicker = resp.markets.associateBy { it.ticker.uppercase() }
            rateLimiter?.onSuccess()
            feedHealth?.clear()
            for (ticker in tickers) {
                val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
                val result = normalizeResult(byTicker[ticker]?.result)
                if (result != null) {
                    applyResult(ticker, result)
                    schedules.remove(ticker)
                } else {
                    schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
                }
            }
        } catch (e: HttpException) {
            noteTransport(e)
            val limited = SettlementPollPolicy.isRateLimited(e)
            val retry = if (limited) SettlementPollPolicy.retryAfterMs(e) else null
            for (ticker in tickers) {
                val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
                val next = if (limited) {
                    SettlementPollPolicy.after429(prev, nowMs, retry)
                } else {
                    SettlementPollPolicy.afterMiss(prev, nowMs)
                }
                schedules[ticker] = next
                if (limited) globalHoldUntilMs = maxOf(globalHoldUntilMs, next.nextAttemptMs)
            }
        } catch (e: Exception) {
            noteTransport(e)
            for (ticker in tickers) {
                val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
                schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
            }
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
