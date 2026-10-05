package com.dirk.kalshiodds.signal.market

import com.dirk.kalshiodds.domain.ActiveMarketResolver
import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Clock-driven 15m window swap for [CryptoMarkets.DEFAULT_SERIES] (Bitcoin).
 *
 * Production bug this closes: [com.dirk.kalshiodds.ui.OddsViewModel] painted
 * `repository.refresh()` snapshots and never applied [Event] to home, while
 * [start] only ran inside [com.dirk.kalshiodds.signal.service.LiveSignalsService].
 * Kalshi's `status=open` list also keeps the closed ticker until the next
 * window is created (15–42s after close, with 429s). A successor is accepted
 * only when its `close_time` is later than the one we just showed.
 */
class MarketRollover(
    private val clock: Clock,
    private val listOpen: suspend (series: String) -> List<MarketUiModel>,
    private val watchedSeries: () -> List<String> = { CryptoMarkets.DEFAULT_SERIES },
    private val graceAfterCloseMs: Long = 0L,
    private val retryMs: Long = POLL_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val staggerMs: Long = STAGGER_MS,
    private val pollCapMs: Long = POLL_CAP_MS
) {
    data class Event(
        val active: Map<String, MarketUiModel>,
        val listed: List<MarketUiModel>,
        val droppedTickers: Set<String>,
        val addedTickers: Set<String>,
        val retrying: Set<String>,
        val nextWakeMs: Long?,
        val reconnect: Boolean = false,
        val rateLimited: Set<String> = emptySet(),
        val lastCloseMs: Map<String, Long> = emptyMap()
    ) {
        val activeTickers: Set<String> get() = active.values.map { it.ticker }.toSet()
        fun displayed(series: String): MarketUiModel? = active[series]
    }

    fun interface Listener {
        fun onRollover(event: Event)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var last: Event = Event(
        active = emptyMap(),
        listed = emptyList(),
        droppedTickers = emptySet(),
        addedTickers = emptySet(),
        retrying = emptySet(),
        nextWakeMs = null
    )
    @Volatile private var lastCloseBySeries: Map<String, Long> = emptyMap()
    @Volatile private var backoffBySeries: Map<String, Long> = emptyMap()
    private var loop: Job? = null

    fun snapshot(): Event = last

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { refreshFromRest() }
                sleeper(nextDelayMs())
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun nextDelayMs(): Long {
        val now = clock.nowMs()
        val wake = last.nextWakeMs ?: return retryMs
        return (wake - now).coerceAtLeast(50L)
    }

    fun onReconnect(): Event {
        val cur = last
        val replay = cur.copy(
            droppedTickers = emptySet(),
            addedTickers = emptySet(),
            reconnect = true
        )
        last = replay
        listeners.forEach { runCatching { it.onRollover(replay) } }
        return replay
    }

    fun onLifecycle(ticker: String, eventType: String): Boolean {
        val kind = eventType.trim().lowercase()
        if (kind !in LIFECYCLE_REFRESH) return false
        val series = CryptoMarkets.inferSeries(ticker)
        if (series !in watchedSeries()) return false
        return true
    }

    suspend fun onLifecycleRefresh(ticker: String, eventType: String): Event? {
        if (!onLifecycle(ticker, eventType)) return null
        return refreshFromRest()
    }

    suspend fun refreshFromRest(): Event {
        val series = watchedSeries()
        val listed = mutableListOf<MarketUiModel>()
        val failed = mutableSetOf<String>()
        val limited = mutableSetOf<String>()
        series.forEachIndexed { i, s ->
            if (i > 0) sleeper(staggerMs)
            try {
                listed += listOpen(s)
                backoffBySeries = backoffBySeries - s
            } catch (e: HttpException) {
                failed += s
                if (e.code() == 429) {
                    limited += s
                    val wait = retryAfterMs(e) ?: nextBackoff(s)
                    backoffBySeries = backoffBySeries + (s to wait)
                }
            } catch (_: Throwable) {
                failed += s
            }
        }
        return applyListed(listed, failed, limited)
    }

    fun applyListed(
        listed: List<MarketUiModel>,
        fetchFailed: Set<String> = emptySet(),
        rateLimited: Set<String> = emptySet()
    ): Event {
        val series = watchedSeries()
        val event = compute(listed, series, last.active, lastCloseBySeries, fetchFailed, rateLimited, clock.nowMs())
        lastCloseBySeries = event.lastCloseMs
        last = event
        listeners.forEach { runCatching { it.onRollover(event) } }
        return event
    }

    private fun compute(
        listed: List<MarketUiModel>,
        series: List<String>,
        previous: Map<String, MarketUiModel>,
        previousClose: Map<String, Long>,
        fetchFailed: Set<String>,
        rateLimited: Set<String>,
        nowMs: Long
    ): Event {
        val active = linkedMapOf<String, MarketUiModel>()
        val retrying = linkedSetOf<String>()
        val closes = previousClose.toMutableMap()
        for (s in series) {
            val prev = previous[s]
            val keepPrev = prev != null && MarketLifecycle.isCurrentWindow(prev, nowMs)
            if (s in fetchFailed && keepPrev) {
                active[s] = prev
                prev.closeTimeEpochMs?.let { closes[s] = it }
                continue
            }
            val picked = successor(
                series = s,
                listed = listed,
                nowMs = nowMs,
                lastCloseMs = closes[s] ?: prev?.closeTimeEpochMs,
                previous = prev
            )
            if (picked != null) {
                active[s] = picked
                picked.closeTimeEpochMs?.let { closes[s] = it }
            } else {
                retrying += s
            }
        }
        val prevTickers = previous.values.map { it.ticker }.toSet()
        val nextTickers = active.values.map { it.ticker }.toSet()
        return Event(
            active = active,
            listed = listed,
            droppedTickers = prevTickers - nextTickers,
            addedTickers = nextTickers - prevTickers,
            retrying = retrying,
            nextWakeMs = nextWakeMs(active.values, retrying, nowMs, rateLimited),
            rateLimited = rateLimited,
            lastCloseMs = closes
        )
    }

    /**
     * Keep the current window if it is still open. Otherwise take the open
     * listing for this series. Closed tickers Kalshi still marks `status=open`
     * fail [MarketLifecycle.isCurrentWindow] and are not picked. A missing
     * listing returns null so the series stays in [Event.retrying] — the loop
     * never gives up, including mid-window, and respects 429 Retry-After.
     */
    fun successor(
        series: String,
        listed: List<MarketUiModel>,
        nowMs: Long,
        lastCloseMs: Long?,
        previous: MarketUiModel?
    ): MarketUiModel? {
        if (previous != null && MarketLifecycle.isCurrentWindow(previous, nowMs)) return previous
        val pool = listed.filter {
            CryptoMarkets.inferSeries(it.ticker).equals(series, ignoreCase = true)
        }
        val current = MarketLifecycle.currentOpenWindow(pool, nowMs) ?: return null
        val floor = lastCloseMs ?: Long.MIN_VALUE
        val close = current.closeTimeEpochMs ?: return current
        return if (close > floor || previous == null) current else null
    }

    private fun nextWakeMs(
        active: Collection<MarketUiModel>,
        retrying: Collection<String>,
        nowMs: Long,
        rateLimited: Set<String>
    ): Long? {
        val closeWake = active.mapNotNull { it.closeTimeEpochMs }.minOrNull()?.plus(graceAfterCloseMs)
        val unresolved = retrying.isNotEmpty()
        val pollWait = if (unresolved) {
            val limitedWait = rateLimited.mapNotNull { backoffBySeries[it] }.minOrNull()
            nowMs + (limitedWait ?: retryMs)
        } else {
            null
        }
        val staleWake = active.mapNotNull { m ->
            val close = m.closeTimeEpochMs ?: return@mapNotNull null
            if (nowMs > close + STALE_FORCE_MS) nowMs + retryMs else null
        }.minOrNull()
        return listOfNotNull(closeWake, pollWait, staleWake).minOrNull()
    }

    private fun nextBackoff(series: String): Long {
        val cur = backoffBySeries[series] ?: BACKOFF_START_MS
        val next = (cur * 2).coerceAtMost(pollCapMs)
        val jitter = Random.nextLong(0, (next / 5).coerceAtLeast(1))
        return next + jitter
    }

    companion object {
        const val POLL_MS = 2_500L
        /** First 429 backoff when Retry-After is absent (18:00 ET measure). */
        const val BACKOFF_START_MS = 2_000L
        const val POLL_CAP_MS = 10_000L
        const val STAGGER_MS = 250L
        /** Keep "Next window loading" at least this long; never an error at 20s. */
        const val LOADING_MAX_MS = 120_000L
        const val STALE_FORCE_MS = 20_000L
        val LIFECYCLE_REFRESH = setOf(
            "deactivated",
            "determined",
            "settled",
            "closed",
            "activated",
            "created"
        )

        fun retryAfterMs(error: HttpException, nowMs: Long = System.currentTimeMillis()): Long? {
            val raw = error.response()?.headers()?.get("Retry-After") ?: return null
            val parsed = com.dirk.kalshiodds.data.repo.RefreshRecovery.parseRetryAfterHeader(raw, nowMs) ?: return null
            return parsed.coerceAtLeast(POLL_MS)
        }
    }
}
