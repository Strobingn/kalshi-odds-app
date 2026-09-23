package com.dirk.kalshiodds.signal

import android.os.SystemClock
import android.util.Log
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.prediction.SignalSnapshot
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.feedback.Calibrator
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
    private val logStore: PredictionLogStore? = null,
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

    private val _scores = MutableStateFlow<Map<String, ScoringEngine.Score>>(emptyMap())
    val scores: StateFlow<Map<String, ScoringEngine.Score>> = _scores.asStateFlow()

    fun latestScores(): Map<String, ScoringEngine.Score> = _scores.value

    fun applyCalibration(state: Calibrator.State) {
        scoring.calibration = state
    }

    fun applyAdapter(state: com.dirk.kalshiodds.signal.feedback.OnlineAdapter.State) {
        scoring.adapter = state
    }

    fun applyAllowlist(state: com.dirk.kalshiodds.signal.feedback.Allowlist.State) {
        scoring.allowlist = state
    }

    fun applyGuardrails(state: com.dirk.kalshiodds.signal.feedback.Guardrails.State) {
        scoring.guardrails = state
    }

    fun applyExternal(snapshot: com.dirk.kalshiodds.signal.external.ExternalSnapshot) {
        scoring.external = snapshot
    }

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

    fun ingestBookSnapshot(
        ticker: String,
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int?,
        receiveElapsedNanos: Long
    ) {
        tickScope.launch {
            if (!CryptoMarkets.isCryptoTicker(ticker)) return@launch
            if (!settings.isWatchedTicker(ticker)) return@launch
            scoring.applySnapshot(ticker, yesLevels, noLevels, seq)
            publishBookScore(ticker, receiveElapsedNanos)
            scoring.maybeAlertFromBook(ticker, settings, receiveElapsedNanos)?.let { emitAlert(it) }
        }
    }

    fun ingestBookDelta(
        ticker: String,
        price: Double,
        delta: Double,
        side: String,
        seq: Int?,
        receiveElapsedNanos: Long
    ) {
        tickScope.launch {
            if (!CryptoMarkets.isCryptoTicker(ticker)) return@launch
            if (!settings.isWatchedTicker(ticker)) return@launch
            scoring.applyDelta(ticker, price, delta, side, seq)
            publishBookScore(ticker, receiveElapsedNanos)
            scoring.maybeAlertFromBook(ticker, settings, receiveElapsedNanos)?.let { emitAlert(it) }
        }
    }

    private suspend fun processTick(tick: MarketTick, notify: Boolean) {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return
        if (!settings.isWatchedTicker(tick.ticker)) return
        val t0 = tick.receiveElapsedNanos
        val scored = scoring.score(tick, settings)
        if (scored != null) {
            _scores.update { it + (tick.ticker to scored) }
            persistScore(tick, scored)
        }
        val alert = if (notify && scored != null) {
            scoring.maybeAlert(tick, settings, precomputed = scored)
        } else {
            null
        }
        val processed = elapsedNanos()
        val latencyMs = (processed - t0) / 1_000_000.0
        _status.update {
            it.copy(
                lastTickLatencyMs = latencyMs.coerceAtLeast(0.0),
                lastTickAgeMs = 0L
            )
        }
        Log.d(TAG, "tick ticker=${tick.ticker} mid=${tick.midPp} recvNanos=$t0 scoredNanos=$processed src=${tick.source}")
        if (alert != null) emitAlert(alert)
    }

    private suspend fun publishBookScore(ticker: String, receiveElapsedNanos: Long) {
        val tick = scoring.book.tickFromBook(ticker, receiveElapsedNanos) ?: return
        val scored = scoring.score(tick, settings) ?: return
        _scores.update { it + (ticker to scored) }
        persistScore(tick, scored)
    }

    private suspend fun persistScore(tick: MarketTick, scored: ScoringEngine.Score) {
        val store = logStore ?: return
        runCatching {
            store.upsertOpenPrediction(
                ticker = tick.ticker,
                series = tick.series,
                predictedYes = scored.fairValuePp / 100.0,
                predictedNo = 1.0 - scored.fairValuePp / 100.0,
                marketMid = scored.marketMidPp / 100.0,
                timestampMs = System.currentTimeMillis(),
                closeTimeMs = tick.closeTimeEpochMs ?: scoring.book.closeTime(tick.ticker),
                snapshot = SignalSnapshot(
                    predictedSide = scored.predictedSide,
                    edgePp = scored.deltaPp,
                    confidence = scored.confidence,
                    regime = scored.regime.name,
                    tteBucket = scored.tteRegime.name,
                    fairValuePp = scored.fairValuePp,
                    calibrated = scored.calibrated,
                    featureDevs = scored.featureDevs
                )
            )
        }
    }

    private suspend fun emitAlert(alert: SignalAlert) {
        if (scoring.guardrails.paused) return
        val posted = if (settings.notificationsEnabled) {
            withContext(Dispatchers.Main.immediate) { notifier.notify(alert) }
        } else {
            elapsedNanos()
        }
        val complete = alert.copy(notifyElapsedNanos = posted)
        _alerts.update { (listOf(complete) + it).take(MAX_ALERTS) }
        Log.d(TAG, "alert ticker=${alert.ticker} delta=${alert.deltaPp} notifyMs=${complete.latencyToNotifyMs}")
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
