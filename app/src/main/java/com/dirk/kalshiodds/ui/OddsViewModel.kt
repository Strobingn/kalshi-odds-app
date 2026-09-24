package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.engine.OverlayThrottle
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.model.LiveCall
import com.dirk.kalshiodds.signal.model.LiveQuote
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStatus
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketUiState
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
    val avgEdgeWhenWrong: Double? = null,
    val alertsPaused: Boolean = false,
    val pauseBanner: String? = null,
    val mutedSummary: String? = null,
    val tickets: TicketUiState = TicketUiState(),
    val persistedHistory: List<ScoredSnapshotRow> = emptyList(),
    val mlGuardNote: String? = null,
    val liveCalls: Map<String, LiveCall> = emptyMap(),
    val liveQuotes: Map<String, LiveQuote> = emptyMap()
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
    private val ticketSession = container.tickets

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var ticketRebuildJob: Job? = null
    private var scoreOverlayJob: Job? = null
    private var currentIntervalMs: Long = BASE_POLL_MS
    private val overlayThrottle = OverlayThrottle(SCORE_OVERLAY_THROTTLE_MS)
    @Volatile private var pendingScores: Map<String, ScoringEngine.Score> = emptyMap()

    init {
        ticketSession.onStart()
        viewModelScope.launch {
            runCatching {
                ticketSession.state.collect { tickets ->
                    _state.update { it.copy(tickets = tickets) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.applyCalibration(Calibrator.fitEntries(container.logStore.readAll()))
                container.support.bootstrap(_state.value.settings)
                publishSupportState()
            }
        }
        viewModelScope.launch {
            runCatching {
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
        }
        viewModelScope.launch {
            runCatching {
                prefs.settings.collectLatest { settings ->
                    hub.settings = settings
                    _state.update { it.copy(settings = settings) }
                    publishSupportState()
                    scheduleRebuildTickets(immediate = true)
                    if (settings.liveSignalsEnabled) {
                        runCatching { LiveSignalsService.start(getApplication()) }
                        if (!settings.credentialsConfigured) {
                            hub.setConnection(WsConnectionState.NEEDS_API_KEY)
                        }
                    } else if (hub.status.value.state != WsConnectionState.IDLE) {
                        // Service is stopping itself; keep the HUD honest if it is already gone.
                        hub.setConnection(WsConnectionState.IDLE)
                    }
                    restartPolling()
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.status.collect { status -> _state.update { it.copy(signalStatus = status) } }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.alerts.collect { alerts -> _state.update { it.copy(recentAlerts = alerts) } }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.liveCalls.collect { calls ->
                    _state.update { it.copy(liveCalls = calls) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.liveQuotes.collect { quotes ->
                    _state.update { it.copy(liveQuotes = quotes) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.scores.collect { scores ->
                    pendingScores = scores
                    val wait = overlayThrottle.onEvent()
                    if (wait == 0L) {
                        applyScoreOverlay(scores)
                    } else if (scoreOverlayJob?.isActive != true) {
                        scoreOverlayJob = viewModelScope.launch {
                            delay(wait)
                            applyScoreOverlay(pendingScores)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                val rows = withContext(Dispatchers.IO) { container.resultsStore.recentSnapshots(24) }
                _state.update {
                    it.copy(
                        persistedHistory = rows,
                        mlGuardNote = HeavyMlGuard.lastReason?.let { r -> "Light mode: $r" }
                    )
                }
            }
        }
        startPolling()
    }

    fun setLiveSignals(enabled: Boolean) {
        viewModelScope.launch { prefs.updateLiveSignals(enabled) }
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
                runCatching {
                    if (_state.value.snapshot == null) {
                        _state.update { it.copy(isLoading = true) }
                    }
                    applyResult(doRefresh())
                }
                delay(nextDelayMs())
            }
        }
    }

    private suspend fun doRefresh(): MarketsSnapshot {
        val s = _state.value.settings
        refreshExternal()
        return repository.refresh(
            watchBtc = s.watchBtc,
            watchEth = s.watchEth,
            watchSol = s.watchSol,
            extraTickers = s.extraTickerList(),
            edgeThresholdPp = s.edgeThresholdPp
        )
    }

    private fun applyScoreOverlay(scores: Map<String, ScoringEngine.Score>) {
        overlayThrottle.markApplied()
        runCatching {
            _state.update { s ->
                val snap = s.snapshot ?: return@update s
                s.copy(snapshot = snap.overlayScores(scores, s.settings.edgeThresholdPp))
            }
            scheduleRebuildTickets()
        }
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
        publishSupportState()
        scheduleRebuildTickets()
    }

    private suspend fun refreshExternal() {
        runCatching {
            val snap = withContext(Dispatchers.IO) { container.external.refreshIfStale() }
            hub.applyExternal(snap)
        }
        runCatching {
            val s = _state.value.settings
            if (s.extendedAiEnabled && s.newsPulseEnabled) {
                val news = withContext(Dispatchers.IO) { container.newsCache.refreshIfStale() }
                container.scoring.extended.news = news
            }
        }
    }

    private fun publishSupportState() {
        val g = container.scoring.guardrails
        val a = container.scoring.allowlist
        val muted = buildList {
            if (a.mutedSeries.isNotEmpty()) add("series ${a.mutedSeries.joinToString { com.dirk.kalshiodds.signal.feedback.Allowlist.shortSeries(it) }}")
            if (a.mutedRegimes.isNotEmpty()) add("regimes ${a.mutedRegimes.joinToString()}")
            if (a.mutedTte.isNotEmpty()) add("TTE ${a.mutedTte.joinToString()}")
        }.joinToString(" · ").ifBlank { null }
        _state.update {
            it.copy(
                alertsPaused = g.paused,
                pauseBanner = g.banner,
                mutedSummary = muted?.let { m -> "Auto-mute: $m" }
            )
        }
    }

    fun resumeAlerts() {
        viewModelScope.launch {
            container.support.resumeAlerts()
            publishSupportState()
            scheduleRebuildTickets(immediate = true)
        }
    }

    fun openTicketApprove(ticketId: String) {
        ticketSession.openApprove(ticketId)
    }

    fun cancelTicketApprove() {
        ticketSession.cancelApprove()
    }

    fun dismissTicket(ticketId: String) {
        ticketSession.dismiss(ticketId)
    }

    /**
     * Explicit Approve for [ticketId] only. Nothing else in this ViewModel
     * (init, poll, score overlay) ever calls the trade client.
     */
    fun approveTicket(ticketId: String) {
        viewModelScope.launch {
            val settings = _state.value.settings
            if (!settings.credentialsConfigured) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Approving")
                return@launch
            }
            ticketSession.approve(ticketId)
        }
    }

    fun cancelWorkingOrder(orderId: String) {
        viewModelScope.launch { ticketSession.cancelWorking(orderId) }
    }

    private fun scheduleRebuildTickets(immediate: Boolean = false) {
        ticketRebuildJob?.cancel()
        ticketRebuildJob = viewModelScope.launch {
            if (!immediate) delay(com.dirk.kalshiodds.signal.service.LiveSignalsPolicy.TICKET_REBUILD_DEBOUNCE_MS)
            runCatching { rebuildTickets() }
        }
    }

    private fun rebuildTickets() {
        val s = _state.value
        val markets = s.snapshot?.allMarkets.orEmpty()
        val books = markets.mapNotNull { m ->
            hub.scoring.book.snapshotBook(m.ticker)?.let { m.ticker to it }
        }.toMap()
        val tickets = TicketBuilder.proposeAll(
            markets,
            TicketBuilder.Context(
                settings = s.settings,
                alertsPaused = s.alertsPaused,
                books = books
            )
        )
        ticketSession.replaceProposals(tickets)
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
        const val SCORE_OVERLAY_THROTTLE_MS = 80L
    }
}
