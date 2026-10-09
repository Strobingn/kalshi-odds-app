package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.data.api.KalshiApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves open prediction-log / paper tickers against settled Kalshi
 * markets. Restricted to KXBTC15M the app tracked, only after close_time,
 * with 5s/15s/30s/60s backoff, in-flight dedupe, and 429 Retry-After.
 */
class SettlementScorer(
    private val resolveApi: () -> KalshiApi,
    private val logStore: PredictionLogStore,
    private val extraOpenTickers: () -> Set<String> = { emptySet() },
    private val onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> },
    private val closeTimeOf: (String) -> Long? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
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
        if (!SettlementPollPolicy.isPollableTicker(ticker)) return
        if (closeTimeMs <= 0L) return
        closeTimes[ticker.uppercase()] = closeTimeMs
    }

    fun closeTime(ticker: String): Long? =
        closeTimes[ticker.uppercase()] ?: closeTimeOf(ticker.uppercase()) ?: closeTimeOf(ticker)

    suspend fun maybeScore(nowMs: Long = this.nowMs(), minIntervalMs: Long = 15_000L) {
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
        val due = SettlementPollPolicy.candidates(
            tracked = tracked,
            nowMs = nowMs,
            schedules = schedules,
            inFlight = inFlight,
            globalHoldUntilMs = globalHoldUntilMs
        )
        for (ticker in due) {
            if (!inFlight.add(ticker)) continue
            try {
                pollTicker(ticker, nowMs)
            } finally {
                inFlight.remove(ticker)
            }
        }
    }

    private suspend fun pollTicker(ticker: String, nowMs: Long) {
        val prev = schedules[ticker] ?: SettlementPollPolicy.Schedule()
        try {
            val resp = resolveApi().getMarkets(
                seriesTicker = SettlementPollPolicy.SERIES,
                status = "settled",
                ticker = ticker,
                limit = 5
            )
            val hit = resp.markets.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
            val result = normalizeResult(hit?.result)
            if (result != null) {
                applyResult(ticker, result)
                schedules.remove(ticker)
            } else {
                schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
            }
        } catch (e: HttpException) {
            if (SettlementPollPolicy.isRateLimited(e)) {
                val retry = SettlementPollPolicy.retryAfterMs(e)
                val next = SettlementPollPolicy.after429(prev, nowMs, retry)
                schedules[ticker] = next
                globalHoldUntilMs = maxOf(globalHoldUntilMs, next.nextAttemptMs)
            } else {
                schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
            }
        } catch (_: Exception) {
            schedules[ticker] = SettlementPollPolicy.afterMiss(prev, nowMs)
        }
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
