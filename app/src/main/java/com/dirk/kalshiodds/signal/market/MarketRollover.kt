package com.dirk.kalshiodds.signal.market

import com.dirk.kalshiodds.domain.ActiveMarketResolver
import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketUiModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rolls KXBTC15M / KXETH15M / KXSOL15M to the next 15m contract at close.
 *
 * Root cause this replaces: REST metadata only ran every 15s (UI + WS) or 45s
 * (foreground service while the Activity was up skipped entirely), WebSocket
 * [com.dirk.kalshiodds.signal.ws.KalshiWsClient.updateSubscriptions] sent a
 * new `subscribe` without `unsubscribe` / `update_subscription`, and
 * [com.dirk.kalshiodds.signal.SignalHub.ingestRestSnapshot] watched every
 * `status=open` ticker — including a just-closed window Kalshi still listed.
 */
class MarketRollover(
    private val clock: Clock,
    private val listOpen: suspend (series: String) -> List<MarketUiModel>,
    private val watchedSeries: () -> List<String> = { CryptoMarkets.DEFAULT_SERIES },
    private val graceAfterCloseMs: Long = ActiveMarketResolver.GRACE_AFTER_CLOSE_MS,
    private val retryMs: Long = ActiveMarketResolver.RETRY_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    data class Event(
        val active: Map<String, MarketUiModel>,
        val listed: List<MarketUiModel>,
        val droppedTickers: Set<String>,
        val addedTickers: Set<String>,
        val retrying: Set<String>,
        val nextWakeMs: Long?,
        val reconnect: Boolean = false
    ) {
        val activeTickers: Set<String> get() = active.values.map { it.ticker }.toSet()
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
        val listed = series.flatMap { s ->
            runCatching { listOpen(s) }.getOrElse { emptyList() }
        }
        return applyListed(listed)
    }

    fun applyListed(listed: List<MarketUiModel>): Event {
        val series = watchedSeries()
        val event = compute(listed, series, last.active, clock.nowMs())
        last = event
        listeners.forEach { runCatching { it.onRollover(event) } }
        return event
    }

    private fun compute(
        listed: List<MarketUiModel>,
        series: List<String>,
        previous: Map<String, MarketUiModel>,
        nowMs: Long
    ): Event {
        val active = ActiveMarketResolver.activeBySeries(listed, series, nowMs)
        val retrying = series.filter { it !in active }.toSet()
        val prevTickers = previous.values.map { it.ticker }.toSet()
        val nextTickers = active.values.map { it.ticker }.toSet()
        return Event(
            active = active,
            listed = listed,
            droppedTickers = prevTickers - nextTickers,
            addedTickers = nextTickers - prevTickers,
            retrying = retrying,
            nextWakeMs = ActiveMarketResolver.nextWakeMs(
                active = active.values,
                retrying = retrying,
                nowMs = nowMs,
                graceAfterCloseMs = graceAfterCloseMs,
                retryMs = retryMs
            )
        )
    }

    companion object {
        val LIFECYCLE_REFRESH = setOf(
            "deactivated",
            "determined",
            "settled",
            "closed",
            "activated",
            "created"
        )
    }
}
