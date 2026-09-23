package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStatus
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OddsUiState(
    val isLoading: Boolean = false,
    val snapshot: MarketsSnapshot? = null,
    val userMessage: String? = null,
    val pollLabel: String = "Polling ~750ms",
    val modelScoreLabel: String? = null,
    val signalStatus: SignalStatus = SignalStatus(),
    val recentAlerts: List<SignalAlert> = emptyList(),
    val settings: SignalSettings = SignalSettings(),
    val avgEdgeWhenRight: Double? = null,
    val avgEdgeWhenWrong: Double? = null
)

/**
 * Fast public-REST poll as fallback; WebSocket when Live signals + API key.
 * Crypto series only (BTC / ETH / SOL + extra crypto tickers).
 */
class OddsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = KalshiOddsApp.from(application).container
    private val repository = container.repository
    private val hub = container.hub
    private val prefs = container.preferences

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var currentIntervalMs: Long = BASE_POLL_MS

    init {
        viewModelScope.launch {
            runCatching {
                hub.applyCalibration(Calibrator.fitEntries(container.logStore.readAll()))
            }
        }
        viewModelScope.launch {
            repository.cachedSnapshot.collect { cached ->
                if (cached != null && _state.value.snapshot == null) {
                    val overlaid = cached.overlayScores(hub.latestScores(), _state.value.settings.edgeThresholdPp)
                    _state.update {
                        it.copy(
                            snapshot = overlaid,
                            isLoading = false,
                            modelScoreLabel = scoreLabel(overlaid)
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            prefs.settings.collectLatest { settings ->
                hub.settings = settings
                _state.update { it.copy(settings = settings) }
                if (settings.liveSignalsEnabled) {
                    runCatching { LiveSignalsService.start(getApplication()) }
                    if (!settings.credentialsConfigured) {
                        hub.setConnection(WsConnectionState.NEEDS_API_KEY)
                    }
                } else {
                    LiveSignalsService.stop(getApplication())
                    hub.setConnection(WsConnectionState.IDLE)
                }
                restartPolling()
            }
        }
        viewModelScope.launch {
            hub.status.collect { status -> _state.update { it.copy(signalStatus = status) } }
        }
        viewModelScope.launch {
            hub.alerts.collect { alerts -> _state.update { it.copy(recentAlerts = alerts) } }
        }
        viewModelScope.launch {
            hub.scores.collect { scores ->
                _state.update { s ->
                    val snap = s.snapshot ?: return@update s
                    s.copy(snapshot = snap.overlayScores(scores, s.settings.edgeThresholdPp))
                }
            }
        }
        startPolling()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, userMessage = null) }
            applyResult(doRefresh())
        }
    }

    private fun restartPolling() {
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.snapshot == null) {
                    _state.update { it.copy(isLoading = true) }
                }
                applyResult(doRefresh())
                delay(nextDelayMs())
            }
        }
    }

    private suspend fun doRefresh(): MarketsSnapshot {
        val s = _state.value.settings
        return repository.refresh(
            watchBtc = s.watchBtc,
            watchEth = s.watchEth,
            watchSol = s.watchSol,
            extraTickers = s.extraTickerList(),
            edgeThresholdPp = s.edgeThresholdPp
        )
    }

    private fun applyResult(result: MarketsSnapshot) {
        if (result.rateLimited) {
            currentIntervalMs = min(max(currentIntervalMs * 2, INITIAL_BACKOFF_MS), MAX_BACKOFF_MS)
        } else if (result.errorMessage == null) {
            val wsLive = _state.value.signalStatus.state == WsConnectionState.CONNECTED
            currentIntervalMs = if (wsLive) WS_METADATA_POLL_MS else max((currentIntervalMs * 4) / 5, BASE_POLL_MS)
        }
        val wsConnected = _state.value.signalStatus.state == WsConnectionState.CONNECTED
        val pollLabel = when {
            wsConnected -> "REST metadata ~${currentIntervalMs / 1000}s (WS live)"
            currentIntervalMs > BASE_POLL_MS + JITTER_MS -> "Backing off ~${currentIntervalMs / 1000}s"
            else -> "Polling ~${currentIntervalMs}ms (±${JITTER_MS}ms)"
        }
        hub.ingestRestSnapshot(result)
        val overlaid = result.overlayScores(hub.latestScores(), _state.value.settings.edgeThresholdPp)
        _state.update {
            it.copy(
                isLoading = false,
                snapshot = overlaid,
                userMessage = when {
                    result.errorMessage != null && result.fromCache ->
                        "Offline — showing cache (${result.errorMessage})"
                    result.errorMessage != null -> result.errorMessage
                    else -> null
                },
                pollLabel = pollLabel,
                modelScoreLabel = scoreLabel(overlaid),
                avgEdgeWhenRight = overlaid.avgEdgeWhenRight,
                avgEdgeWhenWrong = overlaid.avgEdgeWhenWrong
            )
        }
    }

    private fun scoreLabel(result: MarketsSnapshot): String? {
        val c = result.modelScoreCorrect ?: return null
        val total = result.modelScoreTotal ?: return null
        if (total <= 0) return null
        val brier = result.modelMeanBrier?.let { String.format(java.util.Locale.US, " · Brier %.3f", it) }.orEmpty()
        val right = result.avgEdgeWhenRight?.let { String.format(java.util.Locale.US, " · Δ✓ %+.1f", it) }.orEmpty()
        val wrong = result.avgEdgeWhenWrong?.let { String.format(java.util.Locale.US, " · Δ✗ %+.1f", it) }.orEmpty()
        return "Scorecard: $c/$total$brier$right$wrong"
    }

    private fun nextDelayMs(): Long {
        val wsConnected = _state.value.signalStatus.state == WsConnectionState.CONNECTED
        if (wsConnected) return WS_METADATA_POLL_MS
        val half = min(JITTER_MS, currentIntervalMs / 3)
        val jitter = if (half <= 0L) 0L else Random.nextLong(-half, half + 1)
        return (currentIntervalMs + jitter).coerceAtLeast(MIN_POLL_MS)
    }

    companion object {
        const val BASE_POLL_MS = 750L
        const val JITTER_MS = 250L
        const val MIN_POLL_MS = 500L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WS_METADATA_POLL_MS = 15_000L
    }
}
