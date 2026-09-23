package com.dirk.kalshiodds.signal

import android.os.SystemClock
import android.util.Log
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStatus
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * In-process bus: ticks in on a dedicated dispatcher, alerts + status out to UI.
 */
class SignalHub(
    val scoring: ScoringEngine,
    private val notifier: SignalNotifier,
    tickDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diphunter-ticks").apply { priority = Thread.NORM_PRIORITY + 1; isDaemon = true }
    }.asCoroutineDispatcher()
) {
    private val job = SupervisorJob()
    val tickScope = CoroutineScope(job + tickDispatcher)

    private val _status = MutableStateFlow(SignalStatus())
    val status: StateFlow<SignalStatus> = _status.asStateFlow()

    private val _alerts = MutableStateFlow<List<SignalAlert>>(emptyList())
    val alerts: StateFlow<List<SignalAlert>> = _alerts.asStateFlow()

    private val _watchTickers = MutableStateFlow<Set<String>>(emptySet())
    val watchTickers: StateFlow<Set<String>> = _watchTickers.asStateFlow()

    @Volatile
    var settings: SignalSettings = SignalSettings()

    @Volatile
    var wsLive: Boolean = false

    fun setWatchTickers(tickers: Set<String>) {
        _watchTickers.value = tickers
    }

    fun setConnection(
        state: WsConnectionState,
        host: String? = _status.value.host,
        detail: String? = null
    ) {
        _status.update { it.copy(state = state, host = host, detail = detail) }
    }

    fun ingestRestSnapshot(snapshot: MarketsSnapshot) {
        val recv = elapsedNanos()
        val markets = snapshot.allMarkets.filter { CryptoMarkets.isCryptoTicker(it.ticker) }
        setWatchTickers(markets.map { it.ticker }.toSet())
        for (m in markets) {
            scoring.rememberMeta(m.ticker, m.closeTimeEpochMs, m.volume, m.openInterest)
        }
        if (wsLive && _status.value.state == WsConnectionState.CONNECTED) return
        tickScope.launch {
            for (m in markets) {
                if (!settings.isWatchedTicker(m.ticker)) continue
                processTick(MarketTick.fromUi(m, recv, TickSource.REST), notify = true)
            }
        }
    }

    fun ingestTick(tick: MarketTick) {
        tickScope.launch { processTick(tick, notify = true) }
    }

    private suspend fun processTick(tick: MarketTick, notify: Boolean) {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return
        if (!settings.isWatchedTicker(tick.ticker)) return
        val t0 = tick.receiveElapsedNanos
        val alert = scoring.maybeAlert(tick, settings)
        val processed = elapsedNanos()
        val latencyMs = (processed - t0) / 1_000_000.0
        _status.update {
            it.copy(
                lastTickLatencyMs = latencyMs.coerceAtLeast(0.0),
                lastTickAgeMs = 0L
            )
        }
        Log.d(TAG, "tick ticker=${tick.ticker} mid=${tick.midPp} recvNanos=$t0 scoredNanos=$processed src=${tick.source}")
        if (alert != null && notify) {
            val posted = if (settings.notificationsEnabled) {
                withContext(Dispatchers.Main.immediate) { notifier.notify(alert) }
            } else {
                elapsedNanos()
            }
            val complete = alert.copy(notifyElapsedNanos = posted)
            _alerts.update { (listOf(complete) + it).take(MAX_ALERTS) }
            Log.d(TAG, "alert ticker=${alert.ticker} delta=${alert.deltaPp} notifyMs=${complete.latencyToNotifyMs}")
        }
    }

    companion object {
        private const val TAG = "DipHunterTick"
        const val MAX_ALERTS = 30

        fun elapsedNanos(): Long = try {
            SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }
}
