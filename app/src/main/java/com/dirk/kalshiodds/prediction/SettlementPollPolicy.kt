package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.domain.CryptoMarkets
import retrofit2.HttpException

/**
 * Settlement poller rules for KXBTC15M only.
 *
 * 0.3.15 hit Kalshi with thousands of `GET /markets?status=settled`
 * calls (~5/s), including non-KXBTC15M tickers and the still-open
 * current window. This policy:
 *  - restricts to KXBTC15M tickers the app tracked / picked
 *  - waits until [closeTimeMs]
 *  - backs off 5s → 15s → 30s → 60s (capped)
 *  - dedupes in-flight requests
 *  - honors HTTP 429 Retry-After
 */
object SettlementPollPolicy {

    val BACKOFF_MS: LongArray = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)
    const val MAX_BACKOFF_MS = 60_000L
    const val SERIES = "KXBTC15M"

    data class Tracked(
        val ticker: String,
        val closeTimeMs: Long?
    )

    data class Schedule(
        val nextAttemptMs: Long = 0L,
        val backoffIndex: Int = 0
    )

    fun isPollableTicker(ticker: String): Boolean {
        val u = ticker.trim().uppercase()
        if (u.isEmpty()) return false
        if (!u.startsWith(SERIES)) return false
        return CryptoMarkets.isLiveTicker(u)
    }

    fun afterClose(closeTimeMs: Long?, nowMs: Long): Boolean =
        closeTimeMs != null && nowMs >= closeTimeMs

    fun shouldPoll(
        ticker: String,
        closeTimeMs: Long?,
        nowMs: Long,
        nextAttemptMs: Long,
        inFlight: Boolean,
        globalHoldUntilMs: Long = 0L
    ): Boolean {
        if (inFlight) return false
        if (!isPollableTicker(ticker)) return false
        if (!afterClose(closeTimeMs, nowMs)) return false
        if (nowMs < globalHoldUntilMs) return false
        return nowMs >= nextAttemptMs
    }

    fun delayMs(backoffIndex: Int, retryAfterMs: Long? = null): Long {
        val stepped = BACKOFF_MS[backoffIndex.coerceIn(0, BACKOFF_MS.lastIndex)]
        val retry = retryAfterMs?.takeIf { it > 0L }
        return maxOf(retry ?: 0L, stepped).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun nextIndex(backoffIndex: Int): Int =
        (backoffIndex + 1).coerceAtMost(BACKOFF_MS.lastIndex)

    fun afterMiss(schedule: Schedule, nowMs: Long, retryAfterMs: Long? = null): Schedule {
        val wait = delayMs(schedule.backoffIndex, retryAfterMs)
        return Schedule(
            nextAttemptMs = nowMs + wait,
            backoffIndex = nextIndex(schedule.backoffIndex)
        )
    }

    fun after429(schedule: Schedule, nowMs: Long, retryAfterMs: Long?): Schedule {
        val wait = maxOf(delayMs(schedule.backoffIndex, retryAfterMs), retryAfterMs ?: 0L)
            .coerceAtLeast(BACKOFF_MS[0])
        return Schedule(
            nextAttemptMs = nowMs + wait.coerceAtMost(MAX_BACKOFF_MS),
            backoffIndex = nextIndex(schedule.backoffIndex)
        )
    }

    fun retryAfterMs(error: HttpException): Long? = retryAfterHeader(error.response()?.headers()?.get("Retry-After"))

    fun retryAfterHeader(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val seconds = s.toLongOrNull() ?: return null
        return (seconds * 1_000L).coerceAtLeast(0L)
    }

    fun isRateLimited(error: Throwable): Boolean {
        val code = when (error) {
            is HttpException -> error.code()
            else -> (error.cause as? HttpException)?.code()
        }
        return code == 429
    }

    fun candidates(
        tracked: Collection<Tracked>,
        nowMs: Long,
        schedules: Map<String, Schedule>,
        inFlight: Set<String>,
        globalHoldUntilMs: Long = 0L
    ): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (row in tracked) {
            val ticker = row.ticker.uppercase()
            if (!seen.add(ticker)) continue
            val sched = schedules[ticker] ?: Schedule()
            if (shouldPoll(
                    ticker = ticker,
                    closeTimeMs = row.closeTimeMs,
                    nowMs = nowMs,
                    nextAttemptMs = sched.nextAttemptMs,
                    inFlight = ticker in inFlight,
                    globalHoldUntilMs = globalHoldUntilMs
                )
            ) {
                out.add(ticker)
            }
        }
        return out
    }

    fun mergeTracked(
        openLog: Collection<PredictionLogEntry>,
        extraTickers: Collection<String>,
        closeTimeOf: (String) -> Long?
    ): List<Tracked> {
        val out = LinkedHashMap<String, Tracked>()
        for (e in openLog) {
            if (e.outcome != null) continue
            if (!isPollableTicker(e.ticker)) continue
            val key = e.ticker.uppercase()
            out[key] = Tracked(key, e.closeTimeMs ?: closeTimeOf(key))
        }
        for (raw in extraTickers) {
            if (!isPollableTicker(raw)) continue
            val key = raw.uppercase()
            val prev = out[key]
            val close = prev?.closeTimeMs ?: closeTimeOf(key)
            out[key] = Tracked(key, close)
        }
        return out.values.toList()
    }
}
