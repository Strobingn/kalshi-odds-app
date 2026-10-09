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
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStatus
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.service.LiveSignalsService
import com.dirk.kalshiodds.signal.paper.PaperAskDepth
import com.dirk.kalshiodds.signal.paper.PaperAutopilot
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PositionParser
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.chart.ChartWindowService
import com.dirk.kalshiodds.chart.hasQuote
import com.dirk.kalshiodds.chart.hasSpot
import com.dirk.kalshiodds.data.backfill.CoinbaseSpotBackfill
import com.dirk.kalshiodds.data.backfill.LiveWindowBackfill
import com.dirk.kalshiodds.data.backfill.OkHttpHistoryTransport
import com.dirk.kalshiodds.domain.KalshiPrice
import java.util.concurrent.ConcurrentHashMap
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.withLiveQuote
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import com.dirk.kalshiodds.data.api.KalshiPollBudget
import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import com.dirk.kalshiodds.data.api.RefreshGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OddsUiState(
    val isLoading: Boolean = false,
    val snapshot: MarketsSnapshot? = null,
    val userMessage: String? = null,
    val pollLabel: String = "Polling ~2s",
    /** Quiet line when a newer v0.3.*-debug build is on GitHub. */
    val updateBanner: String? = null,
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
    val paper: PaperBookState = PaperBookState(),
    val shadow: com.dirk.kalshiodds.signal.paper.ShadowBookState =
        com.dirk.kalshiodds.signal.paper.ShadowBookState(),
    val liveAutopilotArmed: Boolean = false,
    val liveAutopilotApproveTapped: Boolean = false,
    val positions: List<LivePosition> = emptyList(),
    val positionsNote: String? = null,
    /** Live Kalshi cash from GET /portfolio/balance, if the key can read it. */
    val liveCashUsd: Double? = null,
    val liveCashAtMs: Long? = null,
    val cfFeedLine: String? = null,
    /** CF Benchmarks feed status for BTC / ETH / SOL (primary settlement source). */
    val cfFeedLines: List<String> = emptyList(),
    /** Latest deterministic gate verdict per market ("BET …" or "NO BET — …"). */
    val decisionLines: List<String> = emptyList(),
    val restingOrders: List<com.dirk.kalshiodds.signal.trade.RestingOrder> = emptyList(),
    /** True after the Home Stop tap until Autopilot is turned on again. */
    val persistedHistory: List<ScoredSnapshotRow> = emptyList(),
    val mlGuardNote: String? = null,
    val scorecardSummary: HomeScorecardSummary = HomeScorecardSummary.EMPTY,
    val d3: com.dirk.kalshiodds.signal.d3.D3Snapshot = com.dirk.kalshiodds.signal.d3.D3Snapshot.EMPTY,
    /** 0.3.39: daily 5 PM ET quotes keyed by series (KXBTCD / KXETHD / KXSOLD) for the Home daily views. */
    val dailyQuotes: Map<String, List<com.dirk.kalshiodds.signal.d3.D3Quote>> = emptyMap()
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
    private val paperBook = container.paper.book
    private val shadowBook = container.shadow.book
    private val liveAutopilotSession = container.liveArm

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()
    /** 0.3.39: Scalp is the primary paper Autopilot strategy (Home card). */
    val scalpTrades = container.scalp.trades

    private var pollJob: Job? = null
    private var ticketRebuildJob: Job? = null
    private var scoreOverlayJob: Job? = null
    private val overlayThrottle = OverlayThrottle<Map<String, ScoringEngine.Score>>(
        intervalMs = SCORE_OVERLAY_THROTTLE_MS
    )
    private var currentIntervalMs: Long = BASE_POLL_MS
    private val chartWindows: ChartWindowService by lazy {
        ChartWindowService(
            archive = container.archive,
            book = hub.scoring.book,
            backfill = LiveWindowBackfill(
                kalshi = OkHttpHistoryTransport.kalshi(),
                spot = CoinbaseSpotBackfill(OkHttpHistoryTransport.coinbase())
            )
        )
    }
    private val backfillStarted = ConcurrentHashMap<String, Long>()
    private var backfillJob: Job? = null
    private var lastWsState: WsConnectionState? = null
    private var rolloverBound = false
    private var lastRecordedTicketError: String? = null

    init {
        ticketSession.onStart()
        _state.update { it.copy(paper = paperBook.snapshot()) }
        viewModelScope.launch {
            runCatching {
                ticketSession.state.collect { tickets ->
                    val lastError = tickets.lastError
                    if (!com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.isNotAnOrderError(lastError)) {
                        val incoming = com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce.accept(
                            lastRecordedTicketError,
                            lastError
                        )
                        if (incoming != null) {
                            lastRecordedTicketError = incoming
                            container.lastOrderError.record(incoming)
                        } else if (lastError.isNullOrBlank()) {
                            lastRecordedTicketError = null
                        }
                    }
                    _state.update { cur ->
                        val notice = tickets.lastError
                            ?.takeIf { com.dirk.kalshiodds.signal.trade.TicketSession.isWindowClosedError(it) }
                        val userMessage = when {
                            notice != null && cur.userMessage != notice -> notice
                            notice == null &&
                                com.dirk.kalshiodds.signal.trade.TicketSession.isWindowClosedError(cur.userMessage) ->
                                null
                            else -> cur.userMessage
                        }
                        cur.copy(tickets = tickets, userMessage = userMessage)
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                paperBook.state.collect { paper ->
                    _state.update { it.copy(paper = paper) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                shadowBook.state.collect { shadow ->
                    _state.update { it.copy(shadow = shadow) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                combine(container.logStore.entriesFlow, paperBook.state) { entries, paper ->
                    HomeScorecardSummary.of(
                        entries,
                        paper.lifetimeRealizedPnlUsd ?: paper.liveRealizedPnlUsd
                    )
                }.collect { summary ->
                    _state.update { it.copy(scorecardSummary = summary) }
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
            runCatching { restorePersistedState() }
            startPolling()
            startLastMinuteLoop()
            startD3Loop()
        }
        viewModelScope.launch {
            runCatching {
                repository.cachedSnapshot.collect { cached ->
                    if (cached == null) return@collect
                    val cur = _state.value.snapshot
                    val shouldApply = cur == null ||
                        cur.allMarkets.isEmpty() ||
                        (cur.fromCache && cached.fetchedAtEpochMs >= cur.fetchedAtEpochMs)
                    if (!shouldApply) return@collect
                    val overlaid = attachHistory(
                        cached.overlayScores(hub.latestScores(), _state.value.settings.effectiveEdgeThresholdPp())
                    )
                    _state.update {
                        it.copy(
                            snapshot = overlaid,
                            isLoading = false,
                            modelScoreLabel = scoreLabel(overlaid)
                        )
                    }
                    scheduleRebuildTickets()
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                prefs.settings.collectLatest { settings ->
                    if (com.dirk.kalshiodds.signal.paper.AutopilotMode.parse(settings.autopilotMode) !=
                        com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE
                    ) {
                        liveAutopilotSession.disarm()
                    }
                    hub.settings = settings
                    _state.update {
                        it.copy(
                            settings = settings,
                            liveAutopilotArmed = liveAutopilotSession.armed,
                            liveAutopilotApproveTapped = liveAutopilotSession.approveTapped
                        )
                    }
                    publishSupportState()
                    scheduleRebuildTickets(immediate = true)
                    if (serviceWanted(settings)) {
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
        bindRollover()
        startPositionLoop()
        viewModelScope.launch {
            runCatching {
                com.dirk.kalshiodds.update.UpdateAvailability.offer.collect { offer ->
                    _state.update {
                        it.copy(updateBanner = offer?.let { rel -> "Update ${rel.tag} available" })
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.status.collect { status ->
                    val prev = lastWsState
                    lastWsState = status.state
                    _state.update { it.copy(signalStatus = status) }
                    if (status.state == WsConnectionState.CONNECTED &&
                        prev != null &&
                        prev != WsConnectionState.CONNECTED
                    ) {
                        viewModelScope.launch {
                            runCatching { container.rollover.refreshFromRest() }
                            container.rollover.onReconnect()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.alerts.collect { alerts ->
                    _state.update { it.copy(recentAlerts = alerts) }
                    paperFromAlerts(alerts)
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                hub.scores.collect { scores ->
                    when (overlayThrottle.onEvent(scores)) {
                        OverlayThrottle.Decision.APPLY_NOW -> {
                            scoreOverlayJob?.cancel()
                            scoreOverlayJob = null
                            applyScoreOverlay(scores)
                        }
                        OverlayThrottle.Decision.SCHEDULE_TRAILING -> {
                            val waitMs = overlayThrottle.remainingMs()
                            scoreOverlayJob?.cancel()
                            scoreOverlayJob = viewModelScope.launch {
                                delay(waitMs)
                                overlayThrottle.takeTrailing()?.let { applyScoreOverlay(it) }
                            }
                        }
                        OverlayThrottle.Decision.HOLD -> Unit
                    }
                }
            }
        }
    }

    private suspend fun restorePersistedState() {
        val hydrated = runCatching { prefs.hydrate() }.getOrNull()
        if (hydrated != null) {
            hub.settings = hydrated
            _state.update { it.copy(settings = hydrated) }
        }
        seedOddsHistory()
        seedChartWindows()
        val cached = runCatching { repository.cachedSnapshot.first() }.getOrNull()
        if (cached != null && (_state.value.snapshot == null || _state.value.snapshot!!.allMarkets.isEmpty())) {
            val overlaid = attachHistory(
                cached.overlayScores(hub.latestScores(), _state.value.settings.effectiveEdgeThresholdPp())
            )
            _state.update {
                it.copy(
                    snapshot = overlaid,
                    isLoading = false,
                    modelScoreLabel = scoreLabel(overlaid)
                )
            }
            seedChartWindows()
        }
        val rows = withContext(Dispatchers.IO) { container.resultsStore.recentSnapshots(24) }
        _state.update {
            it.copy(
                persistedHistory = rows,
                mlGuardNote = HeavyMlGuard.lastReason?.let { r -> "Light mode: $r" }
            )
        }
        refreshPositions()
    }

    private fun seedOddsHistory() {
        runCatching {
            val mids = container.resultsStore.recentOddsMids(800)
            val grouped = mids.groupBy { it.ticker }
            for ((ticker, rows) in grouped) {
                hub.scoring.book.seedSeries(ticker, rows.map { it.createdAtMs to it.mid01 }.asReversed())
            }
            if (grouped.isEmpty()) {
                val snaps = container.resultsStore.recentSnapshots(200)
                snaps.groupBy { it.ticker }.forEach { (ticker, rows) ->
                    hub.scoring.book.seedSeries(
                        ticker,
                        rows.map { it.createdAtMs to (it.marketPp / 100.0) }.asReversed()
                    )
                }
            }
        }
    }

    fun seedChartWindows() {
        runCatching {
            val now = System.currentTimeMillis()
            val markets = _state.value.snapshot?.allMarkets.orEmpty()
            val keep = LinkedHashSet<String>()
            for (m in markets) {
                val end = m.closeTimeEpochMs ?: now
                val start = end - ChartWindowService.WINDOW_MS
                chartWindows.restoreWindow(m.ticker, start, end)
                keep.add(m.ticker)
            }
            if (keep.isEmpty()) {
                container.resultsStore.recentOddsMids(40).map { it.ticker }.distinct().forEach { ticker ->
                    chartWindows.restoreWindow(ticker, now - ChartWindowService.WINDOW_MS, now)
                    keep.add(ticker)
                }
            }
            chartWindows.trimActive(keep, now - ChartWindowService.WINDOW_MS)
        }
    }

    fun setLiveSignals(enabled: Boolean) {
        viewModelScope.launch { prefs.updateLiveSignals(enabled) }
    }

    private fun bindRollover() {
        if (rolloverBound) return
        rolloverBound = true
        container.rollover.addListener { event -> applyRolloverEvent(event) }
        container.rollover.start(viewModelScope)
    }

    fun onForeground() {
        viewModelScope.launch {
            runCatching { container.rollover.refreshFromRest() }
        }
    }

    internal fun applyRolloverEvent(event: com.dirk.kalshiodds.signal.market.MarketRollover.Event) {
        val now = container.clock.nowMs()
        _state.update { cur ->
            val merged = HomeSnapshotMerge.apply(cur.snapshot, event, now)
            val overlaid = attachHistory(
                merged.overlayScores(hub.latestScores(), cur.settings.effectiveEdgeThresholdPp())
            )
            cur.copy(snapshot = overlaid, isLoading = false)
        }
        if (event.droppedTickers.isNotEmpty()) {
            ticketSession.voidTickers(event.droppedTickers)
            viewModelScope.launch {
                runCatching { repository.scoreSettlementsNow(now) }
            }
        }
        if (event.addedTickers.isNotEmpty()) {
            applyRolloverCharts(event)
        }
        hub.setWatchTickers(event.activeTickers)
        scheduleRebuildTickets()
    }

    private val refreshGate = RefreshGate(debounceMs = 750L, nowMs = { container.clock.nowMs() })

    fun refresh() {
        if (!refreshGate.tryAcquire()) return
        viewModelScope.launch {
            try {
                _state.update { it.copy(isLoading = true, userMessage = null) }
                val result = try {
                    doRefresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    offlineSnapshot(t)
                }
                applyResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        userMessage = KalshiRequestStatus.message(
                            t,
                            container.kalshiTraffic.limiter.remainingHoldMs()
                        )
                    )
                }
            } finally {
                refreshGate.release()
            }
        }
    }

    private fun offlineSnapshot(error: Throwable): MarketsSnapshot {
        val retry = container.kalshiTraffic.limiter.remainingHoldMs().takeIf { it > 0L } ?: 1_000L
        val message = KalshiRequestStatus.message(error, retry)
        container.kalshiTraffic.health.note(error, retry)
        val cached = _state.value.snapshot
        return (cached ?: MarketsSnapshot(
            btc = emptyList(),
            fetchedAtEpochMs = 0L,
            fromCache = false
        )).copy(
            fromCache = cached != null,
            errorMessage = message,
            rateLimited = KalshiRequestStatus.isRateLimited(error) || KalshiRequestStatus.isServerError(error),
            retryInMs = retry
        )
    }

    private fun restartPolling() {
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val headless = !com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()
                if (headless) {
                    // 0.3.39 always-on: with no UI, this process-scoped ViewModel keeps Autopilot running.
                    val wanted = autopilotWanted(_state.value.settings)
                    com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.headlessDriving.set(wanted)
                    if (!wanted) {
                        delay(1_000L)
                        continue
                    }
                } else {
                    com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.headlessDriving.set(false)
                }
                try {
                    if (_state.value.snapshot == null) {
                        _state.update { it.copy(isLoading = true) }
                    }
                    applyResult(doRefresh())
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val retry = container.kalshiTraffic.limiter.remainingHoldMs()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            userMessage = KalshiRequestStatus.message(t, retry)
                        )
                    }
                }
                delay(
                    if (headless) {
                        max(nextDelayMs(), com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.BACKGROUND_POLL_MS)
                    } else {
                        nextDelayMs()
                    }
                )
            }
        }
    }

    private fun liveArmedNow(s: com.dirk.kalshiodds.signal.config.SignalSettings): Boolean =
        s.autopilotModeEnum() == com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE && liveAutopilotSession.armed

    private fun autopilotWanted(s: com.dirk.kalshiodds.signal.config.SignalSettings): Boolean =
        com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.autopilotWanted(
            s.paperTradingEnabled, s.aiPaperAutopilotEnabled,
            s.autopilotModeEnum() == com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE,
            liveAutopilotSession.armed
        )

    private fun serviceWanted(s: com.dirk.kalshiodds.signal.config.SignalSettings): Boolean =
        s.liveSignalsEnabled || autopilotWanted(s)

    private suspend fun doRefresh(): MarketsSnapshot {
        val s = _state.value.settings
        refreshExternal()
        return repository.refresh(
            watchBtc = true,
            watchEth = true,
            watchSol = true,
            extraTickers = com.dirk.kalshiodds.domain.CryptoMarkets.liveTickers(s.extraTickerList()),
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
        val event = container.rollover.applyListed(result.allMarkets)
        hub.ingestRestSnapshot(result)
        val series = _state.value.settings.watchedSeries.ifEmpty {
            com.dirk.kalshiodds.domain.CryptoMarkets.DEFAULT_SERIES
        }
        val pruned = HomeSnapshotMerge.apply(
            result.retainActiveWindows(container.clock.nowMs(), series),
            event,
            container.clock.nowMs()
        )
        val overlaid = attachLastMinute(
            attachHistory(
                pruned.overlayScores(hub.latestScores(), _state.value.settings.effectiveEdgeThresholdPp())
            )
        )
        _state.update {
            it.copy(
                isLoading = false,
                snapshot = overlaid,
                userMessage = when {
                    !result.errorMessage.isNullOrBlank() -> result.errorMessage
                    event.retrying.isNotEmpty() -> null
                    else -> null
                },
                pollLabel = pollLabel,
                modelScoreLabel = scoreLabel(overlaid),
                cfFeedLine = container.cfFeed.status(
                    com.dirk.kalshiodds.signal.ws.CfBenchmarks.BTC,
                    container.clock.nowMs(),
                    coinbaseAvailable = true
                ).detail,
                avgEdgeWhenRight = overlaid.avgEdgeWhenRight,
                avgEdgeWhenWrong = overlaid.avgEdgeWhenWrong
            )
        }
        publishSupportState()
        scheduleRebuildTickets()
        scheduleChartBackfill(overlaid)
    }

    private suspend fun refreshExternal() {
        runCatching {
            val snap = withContext(Dispatchers.IO) { container.external.refreshIfStale() }
            hub.applyExternal(snap)
        }
        runCatching { refreshBrtiSpot() }
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

    fun focusTicket(ticker: String?, ticketId: String?) {
        val proposals = ticketSession.snapshot().proposals
        val match = proposals.firstOrNull { ticketId != null && it.id == ticketId }
            ?: proposals.firstOrNull { ticker != null && it.ticker.equals(ticker, true) }
        if (match != null) ticketSession.openApprove(match.id)
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
    fun reviseTicketLimit(ticketId: String, centsText: String) {
        val cents = com.dirk.kalshiodds.signal.trade.LimitPriceInput.parse(centsText) ?: return
        val fee = _state.value.settings.feeRate
        ticketSession.revise(ticketId) {
            com.dirk.kalshiodds.signal.trade.LimitPriceInput.apply(it, cents, fee)
        }
    }

    private fun publishFeedBanner() {
        val banner = container.kalshiTraffic.health.banner ?: return
        _state.update { it.copy(userMessage = banner, isLoading = false) }
    }

    fun approveTicket(ticketId: String) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val ticket = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
            when (
                val decision = com.dirk.kalshiodds.signal.trade.ApproveRouter.decide(
                    paperTradingEnabled = settings.paperTradingEnabled,
                    paperOnly = ticket?.paperOnly == true,
                    isSell = ticket?.isSell == true,
                    liveCredentialsConfigured = settings.tradingCredentialsConfigured(),
                    canApprove = ticket?.canApprove == true,
                    blockedReason = ticket?.blockedReason,
                    intent = com.dirk.kalshiodds.signal.trade.ApproveRouter.Intent.Live,
                    keyIdWithoutPem = settings.keyIdWithoutPem()
                )
            ) {
                com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Paper -> applyPaperBuy(ticketId)
                com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Live -> ticketSession.approve(ticketId)
                is com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Blocked ->
                    ticketSession.failSoft(decision.reason)
            }
        }
    }

    fun approveSellTicket(ticketId: String, count: Int, price: Double) {
        viewModelScope.launch {
            val settings = _state.value.settings
            if (!settings.tradingCredentialsConfigured()) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Approving")
                return@launch
            }
            val snap = _state.value
            ticketSession.revise(ticketId) { t ->
                refreshSellAtConfirm(t, count, price, snap)
            }
            val ticket = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
            if (ticket != null && !ticket.canApprove) {
                ticketSession.failSoft(ticket.blockedReason ?: TicketBuilder.NO_BUYERS)
                return@launch
            }
            ticketSession.approve(ticketId)
        }
    }

    fun cancelWorkingOrder(orderId: String) {
        viewModelScope.launch { ticketSession.cancelWorking(orderId) }
    }

    fun setPaperTrading(enabled: Boolean) {
        viewModelScope.launch { prefs.updatePaperTrading(enabled) }
    }

    fun resetPaperBook() {
        val before = paperBook.snapshot().cashUsd
        val snap = _state.value.settings
        val start = snap.paperBankrollStartUsd
        paperBook.reset(start)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                container.archive.insertSettingsChange(
                    com.dirk.kalshiodds.data.local.history.SettingsChange(
                        createdAtMs = System.currentTimeMillis(),
                        key = "paper_reset",
                        oldValue = before.toString(),
                        newValue = start.toString(),
                        snapshotJson = com.dirk.kalshiodds.data.local.history.SettingsRestore.snapshot(snap)
                    )
                )
            }
        }
    }

    /**
     * Simulated fill on the paper book. Never calls [ticketSession.approve]
     * and never hits the Kalshi order API. Works with no Kalshi key.
     */
    fun paperTicket(ticketId: String) {
        applyPaperBuy(ticketId)
    }

    /**
     * Card-level Paper UP / Paper DOWN. $10 at the live ask using the
     * same math as the tile profit line. Never calls [ticketSession.approve]
     * and never hits the live order API.
     */
    fun paperBuySide(market: MarketUiModel, side: String) {
        val now = container.clock.nowMs()
        val all = _state.value.snapshot?.allMarkets.orEmpty()
        val target = MarketLifecycle.resolveActionWindow(market, all, now)
        if (target == null) {
            _state.update { it.copy(userMessage = com.dirk.kalshiodds.ui.HomeMarkets.NEXT_WINDOW_LOADING) }
            return
        }
        val outcome = com.dirk.kalshiodds.signal.paper.PaperTileBuy.place(paperBook, target, side)
        if (outcome.ok) {
            runCatching {
                container.resultsWriter.enqueueTicket(
                    com.dirk.kalshiodds.data.local.results.TicketAttemptRow(
                        ticker = target.ticker,
                        side = if (side.equals("NO", true) || side.equals("DOWN", true)) "NO" else "YES",
                        stakeUsd = outcome.stakeUsd,
                        approved = true,
                        result = "paper filled",
                        createdAtMs = System.currentTimeMillis(),
                        note = outcome.message
                    )
                )
            }
        }
        _state.update {
            it.copy(
                userMessage = outcome.message,
                paper = paperBook.snapshot()
            )
        }
    }

    private fun applyPaperBuy(ticketId: String) {
        val outcome = com.dirk.kalshiodds.signal.paper.PaperApprove.apply(
            session = ticketSession,
            book = paperBook,
            ticketId = ticketId,
            size = { paperSized(it) },
            onHistory = { row ->
                runCatching { container.resultsWriter.enqueueTicket(row) }
            }
        )
        _state.update {
            it.copy(
                userMessage = if (outcome.ok) outcome.message else outcome.visibleReason,
                paper = paperBook.snapshot()
            )
        }
    }

    /** Paper-book sell with edited count/price. Never hits Kalshi. */
    fun paperSellTicket(ticketId: String, count: Int, price: Double) {
        ticketSession.revise(ticketId) { t -> resizeSell(t, count, price) }
        paperTicket(ticketId)
    }

    fun sellPosition(ticker: String, side: String) {
        val s = _state.value
        if (!s.settings.ticketsEnabled) {
            ticketSession.failSoft("Turn on trade tickets in Settings to sell")
            return
        }
        val now = System.currentTimeMillis()
        val markets = s.snapshot?.allMarkets.orEmpty()
        val livePos = s.positions.firstOrNull {
            it.ticker.equals(ticker, true) && it.side.equals(side, true)
        }
        val paperFill = s.paper.fills.firstOrNull {
            !it.settled && it.ticker.equals(ticker, true) && it.side.equals(side, true)
        }
        val held = livePos?.let { PositionParser.heldContracts(it) } ?: paperFill?.contracts ?: 0
        if (held <= 0) {
            ticketSession.failSoft("No open $side position on $ticker")
            return
        }
        val market = markets.firstOrNull { it.ticker.equals(ticker, true) }
            ?: livePos?.let {
                MarketUiModel(
                    ticker = ticker,
                    title = it.title ?: ticker,
                    subtitle = null,
                    floorStrike = null,
                    yesBid = if (side == "YES") it.bestBid else null,
                    yesAsk = null,
                    noBid = if (side == "NO") it.bestBid else null,
                    noAsk = null,
                    lastPrice = it.bestBid,
                    yesProbabilityPercent = null,
                    noProbabilityPercent = null,
                    volume = null,
                    volume24h = null,
                    openInterest = null,
                    liquidityDollars = null,
                    closeTimeLocal = null,
                    closeTimeEpochMs = it.closeTimeEpochMs,
                    status = "active",
                    seriesLabel = com.dirk.kalshiodds.domain.CryptoMarkets.kindFor(ticker).label
                )
            }
        if (market == null) {
            ticketSession.failSoft("Market closed")
            return
        }
        val paperOnly = livePos == null
        val ticket = TicketBuilder.proposeSell(
            market = market,
            side = side,
            heldContracts = held,
            ctx = ticketContext(s, now),
            paperOnly = paperOnly
        ) ?: return
        ticketSession.addManual(ticket)
    }

    /**
     * Open an approve-gated Buy sheet for [side] on the current live window.
     * A stale card after rollover resolves via [MarketLifecycle.resolveActionWindow];
     * a missing open window shows [HomeMarkets.NEXT_WINDOW_LOADING] instead of
     * Window/Market closed. Missing asks become a disabled ticket card — never
     * a page-level "No ask to size" error. Never remaps a previous ticket.
     */
    /**
     * 0.3.39: manual Buy on a daily 5 PM ET strike (BTC/ETH/SOL). Same approve-gated ticket path as
     * the 15m cards (Approve + typed REAL MONEY for live). Uses only that quote's own prices.
     */
    fun buyDaily(quote: com.dirk.kalshiodds.signal.d3.D3Quote, side: String) {
        val s = _state.value
        if (!s.settings.ticketsEnabled) {
            ticketSession.failSoft("Turn on trade tickets in Settings to buy")
            return
        }
        val now = container.clock.nowMs()
        val market = com.dirk.kalshiodds.ui.HomeMarkets.dailyMarket(quote)
        val ticket = TicketBuilder.proposeManual(market, side, ticketContext(s, now))
        if (ticket == null) {
            ticketSession.failSoft("Could not build a buy ticket — turn on trade tickets in Settings")
            return
        }
        ticketSession.addManual(ticket)
    }

    /** 0.3.39: live order book for one ticker (WS book), for the chart screen. */
    fun orderBook(ticker: String): com.dirk.kalshiodds.signal.engine.BookLevelSnapshot? =
        runCatching { hub.scoring.book.snapshotBook(ticker) }.getOrNull()

    fun buyMarket(market: MarketUiModel, side: String) {
        val s = _state.value
        if (!s.settings.ticketsEnabled) {
            ticketSession.failSoft("Turn on trade tickets in Settings to buy")
            return
        }
        val now = container.clock.nowMs()
        val all = s.snapshot?.allMarkets.orEmpty()
        val target = MarketLifecycle.resolveActionWindow(market, all, now)
        if (target == null) {
            ticketSession.failSoft(com.dirk.kalshiodds.ui.HomeMarkets.NEXT_WINDOW_LOADING)
            _state.update { it.copy(userMessage = com.dirk.kalshiodds.ui.HomeMarkets.NEXT_WINDOW_LOADING) }
            return
        }
        val ticketCtx = ticketContext(s, now)
        val lastMinute = TicketBuilder.proposeLastMinute(target.copy(lastMinute = target.lastMinute), ticketCtx)
            ?: s.tickets.proposals.firstOrNull {
                it.kind == com.dirk.kalshiodds.signal.trade.TicketKind.LAST_MINUTE &&
                    it.ticker.equals(target.ticker, true) &&
                    it.canApprove
            }
        if (lastMinute != null && lastMinute.side.equals(side, true)) {
            ticketSession.addManual(lastMinute)
            return
        }
        val ticket = TicketBuilder.proposeManual(target, side, ticketCtx)
        if (ticket == null) {
            ticketSession.failSoft("Could not build a buy ticket — turn on trade tickets in Settings")
            return
        }
        ticketSession.addManual(ticket)
    }

    private fun applyScoreOverlay(scores: Map<String, ScoringEngine.Score>) {
        runCatching {
            _state.update { s ->
                val snap = s.snapshot ?: return@update s
                s.copy(
                    snapshot = attachLastMinute(
                        attachHistory(snap.overlayScores(scores, s.settings.effectiveEdgeThresholdPp()))
                    )
                )
            }
            scheduleRebuildTickets()
            refreshPositionMarks()
        }
    }

    private fun scheduleRebuildTickets(immediate: Boolean = false) {
        ticketRebuildJob?.cancel()
        ticketRebuildJob = viewModelScope.launch {
            if (!immediate) delay(com.dirk.kalshiodds.signal.service.LiveSignalsPolicy.TICKET_REBUILD_DEBOUNCE_MS)
            runCatching { rebuildTickets() }
        }
    }

    private fun applyRolloverCharts(event: com.dirk.kalshiodds.signal.market.MarketRollover.Event) {
        val now = container.clock.nowMs()
        for (ticker in event.addedTickers) {
            val market = event.active.values.firstOrNull { it.ticker == ticker }
            val end = market?.closeTimeEpochMs ?: now + ChartWindowService.WINDOW_MS
            chartWindows.restoreWindow(ticker, end - ChartWindowService.WINDOW_MS, end)
        }
        chartWindows.trimActive(event.activeTickers, now - ChartWindowService.WINDOW_MS)
    }

    private fun rebuildTickets() {
        val s = _state.value
        val now = container.clock.nowMs()
        val markets = s.snapshot?.allMarkets.orEmpty()
        val live = MarketLifecycle.tradable(markets, now)
        val d3Tickers = s.d3.liveTickers
        val liveTickers = live.map { it.ticker }.toSet() + d3Tickers
        val ctx = ticketContext(s, now)
        val stale = s.tickets.proposals.map { it.ticker }.filter { it !in liveTickers }.toSet()
        if (stale.isNotEmpty()) ticketSession.voidTickers(stale)
        val d3Tickets = s.d3.qualifying.mapNotNull {
            TicketBuilder.proposeD3(it, ctx, container.d3Engine.schedule)
        }
        val tickets = TicketBuilder.proposeAll(live, ctx) + d3Tickets
        ticketSession.replaceProposals(tickets, liveTickers = liveTickers)
        runCatching {
            container.opportunities.consider(
                tickets = tickets,
                enabled = s.settings.opportunityAlertsEnabled && s.settings.notificationsEnabled,
                quiet = s.settings.opportunityQuiet
            )
        }
        runPaperAutopilot(live)
        refreshPositionMarks()
    }

    private var lastMinuteJob: Job? = null
    private var d3Job: Job? = null
    private var positionJob: Job? = null
    private val lastD3TradeFetch = ConcurrentHashMap<String, Long>()
    @Volatile private var d3Quotes: List<com.dirk.kalshiodds.signal.d3.D3Quote> = emptyList()
    @Volatile private var lastOtherDailyFetchMs = 0L

    private fun startD3Loop() {
        d3Job?.cancel()
        _state.update {
            it.copy(
                d3 = container.d3Engine.snapshot(
                    emptyList(),
                    container.d3Store,
                    now = container.clock.nowMs()
                )
            )
        }
        d3Job = viewModelScope.launch {
            runCatching {
                val schedule = withContext(Dispatchers.IO) { container.d3Markets.loadSchedule() }
                container.d3Engine.applySchedule(schedule)
            }
            var lastMarketFetch = 0L
            while (isActive) {
                if (!com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()) {
                    delay(5_000)
                    continue
                }
                runCatching { tickD3(lastMarketFetch).also { lastMarketFetch = it } }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    container.kalshiTraffic.health.note(
                        t,
                        container.kalshiTraffic.limiter.remainingHoldMs().takeIf { it > 0L } ?: 1_000L
                    )
                }
            publishFeedBanner()
                delay(5_000)
            }
        }
    }

    private suspend fun tickD3(lastMarketFetchMs: Long): Long {
        val now = container.clock.nowMs()
        val impliedClose = com.dirk.kalshiodds.signal.d3.D3Window.impliedCloseMs(now)
        val phase = com.dirk.kalshiodds.signal.d3.D3Window.phase(now, impliedClose)
        val interval = when (phase) {
            com.dirk.kalshiodds.signal.d3.D3Phase.ACTIVE -> 15_000L
            com.dirk.kalshiodds.signal.d3.D3Phase.WAITING -> 60_000L
            com.dirk.kalshiodds.signal.d3.D3Phase.CLOSED -> 120_000L
        }
        var fetchedAt = lastMarketFetchMs
        if (now - lastMarketFetchMs >= interval) {
            val quotes = withContext(Dispatchers.IO) { container.d3Markets.loadFivePmQuotes() }
            if (quotes.isNotEmpty()) d3Quotes = quotes
            fetchedAt = now
            val daily = HashMap<String, List<com.dirk.kalshiodds.signal.d3.D3Quote>>()
            daily[com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTCD] = quotes
            if (now - lastOtherDailyFetchMs >= OTHER_DAILY_MS) {
                for (series in listOf(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_ETHD, com.dirk.kalshiodds.data.api.KalshiApi.SERIES_SOLD)) {
                    daily[series] = withContext(Dispatchers.IO) {
                        runCatching { container.d3Markets.loadFivePmQuotes(series) }.getOrDefault(emptyList())
                    }
                }
                lastOtherDailyFetchMs = now
            }
            runCatching { runDailyDecisions(daily, now) }
            val shown = daily.filterValues { it.isNotEmpty() }
            if (shown.isNotEmpty()) _state.update { it.copy(dailyQuotes = it.dailyQuotes + shown) }
        }
        val quotes = d3Quotes
        val settings = _state.value.settings
        val paperOn = settings.paperTradingEnabled && settings.aiPaperAutopilotEnabled
        val trades = HashMap<String, List<com.dirk.kalshiodds.signal.d3.D3TradePrint>>()
        if (paperOn) {
            val resting = container.d3Engine.restingBids()
            for (bid in resting) {
                val key = bid.ticker.uppercase()
                val last = lastD3TradeFetch[key] ?: 0L
                if (now - last < com.dirk.kalshiodds.data.api.KalshiPollBudget.D3_TRADES_MS) continue
                val prints = withContext(Dispatchers.IO) {
                    runCatching { container.d3Markets.loadTrades(bid.ticker, bid.placedAtMs) }
                        .getOrDefault(emptyList())
                }
                lastD3TradeFetch[key] = now
                trades[key] = prints
            }
        }
        val bankroll = paperBook.snapshot().paperBankrollUsd
        val fired = container.d3Engine.tickPaper(
            quotes = quotes,
            store = container.d3Store,
            tradesByTicker = trades,
            paperAutopilot = paperOn,
            bankrollUsd = bankroll,
            now = now
        )
        val snap = container.d3Engine.snapshot(quotes, container.d3Store, bankrollUsd = bankroll, now = now)
        _state.update { it.copy(d3 = snap) }
        fired.forEach { signal ->
            runCatching { container.d3Notifier.notifyFired(signal) }
        }
        if (fired.isNotEmpty() || snap.qualifying.isNotEmpty()) {
            scheduleRebuildTickets(immediate = true)
        }
        quotes.forEach { q ->
            q.closeTimeEpochMs?.let { container.repository.noteCloseTime(q.ticker, it) }
        }
        return fetchedAt
    }

    private fun startLastMinuteLoop() {
        lastMinuteJob?.cancel()
        lastMinuteJob = viewModelScope.launch {
            runCatching { refreshMinuteVol() }
            var lastMinuteBucket = 0L
            while (isActive) {
                runCatching { refreshBrtiSpot() }
                runCatching { tickLastMinute() }
                val bucket = container.clock.nowMs() / 60_000L
                if (bucket != lastMinuteBucket) {
                    lastMinuteBucket = bucket
                    runCatching { refreshMinuteVol() }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refreshBrtiSpot() {
        // CF Benchmarks BRTI over the signed Kalshi WS is primary; the public composite is the fallback.
        val now = container.clock.nowMs()
        val cf = container.cfFeed.latest(com.dirk.kalshiodds.signal.ws.CfBenchmarks.BTC)?.takeIf { it.fresh(now) }
        if (cf != null) {
            container.lastMinuteEngine.noteSpot(
                com.dirk.kalshiodds.signal.lastminute.BrtiQuote(
                    price = cf.value,
                    source = "CF BRTI",
                    fallback = false,
                    fetchedAtMs = cf.localReceivedAtMs.takeIf { it > 0L } ?: now
                )
            )
            return
        }
        val fallback = container.external.latest().btc
        val quote = withContext(Dispatchers.IO) {
            container.brti.fetchSpot(fallback?.lastPrice, fallback?.source)
        }
        if (quote != null) container.lastMinuteEngine.noteSpot(quote)
    }

    private suspend fun refreshMinuteVol() {
        val candles = withContext(Dispatchers.IO) { container.brti.fetchMinuteCloses() }
        if (candles.isNotEmpty()) {
            container.lastMinuteEngine.replaceMinuteCloses(container.brti.logCloses(candles))
        }
    }

    private fun tickLastMinute() {
        if (com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired.retired) return
        val snap = _state.value.snapshot ?: return
        val next = attachLastMinute(snap)
        _state.update { it.copy(snapshot = next) }
        scheduleRebuildTickets(immediate = true)
    }

    private fun attachLastMinute(snap: MarketsSnapshot): MarketsSnapshot {
        val stake = _state.value.settings.ticketStakeUsd
        container.lastMinuteEngine.forgetStale(snap.allMarkets.map { it.ticker }.toSet())
        // 0.3.39: last-minute play retired — never attach, record, notify or paper it.
        if (com.dirk.kalshiodds.signal.lastminute.LastMinuteRetired.retired) return snap.mapMarkets { it.copy(lastMinute = null) }
        return snap.mapMarkets { market ->
            val book = hub.scoring.book.snapshotBook(market.ticker)
            val eval = container.lastMinuteEngine.tick(
                market = market,
                book = book,
                stakeUsd = stake,
                fallbackSpot = market.spotUsd,
                fallbackSource = market.spotLabel
            )
            eval.fired?.let { fired ->
                val liveAsk = com.dirk.kalshiodds.signal.trade.TicketBuilder.liveAsk(market, fired.side)
                if (!com.dirk.kalshiodds.signal.flip.FlipCheck.allowsFired(
                        fired,
                        eval.spotUsd ?: market.spotUsd,
                        eval.strikeUsd ?: market.floorStrike,
                        liveAsk
                    )
                ) {
                    return@mapMarkets market.copy(lastMinute = eval)
                }
                val logged = container.lastMinuteStore.record(fired)
                if (logged != null) {
                    // Heads-up even when this Activity is in the foreground.
                    runCatching { container.lastMinuteNotifier.notifyFired(fired) }
                    runPaperAutopilot(listOf(market.copy(lastMinute = eval)))
                }
            }
            market.copy(lastMinute = eval)
        }
    }

    private fun ticketContext(s: OddsUiState, nowMs: Long): TicketBuilder.Context {
        val markets = s.snapshot?.allMarkets.orEmpty()
        val books = markets.mapNotNull { m ->
            hub.scoring.book.snapshotBook(m.ticker)?.let { m.ticker to it }
        }.toMap()
        val ticks = markets.mapNotNull { m ->
            hub.scoring.book.lastTick(m.ticker)?.let { m.ticker to it }
        }.toMap()
        val liveFresh = com.dirk.kalshiodds.decision.LiveBalancePolicy.fresh(s.liveCashUsd, s.liveCashAtMs, nowMs)
        return TicketBuilder.Context(
            settings = s.settings,
            alertsPaused = s.alertsPaused,
            books = books,
            ticks = ticks,
            positions = s.positions,
            nowMs = nowMs,
            // 0.3.40: a stale (>15 min) real balance is never presented as "live".
            bankrollUsd = if (liveFresh) s.liveCashUsd else s.settings.bankrollUsd,
            bankrollSource = if (liveFresh) "live" else "settings"
        )
    }

    private fun paperSized(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (ticket.isSell || !_state.value.settings.winTargetEnabled) return ticket
        val s = _state.value
        val market = s.snapshot?.allMarkets.orEmpty().firstOrNull { it.ticker.equals(ticket.ticker, true) }
            ?: return ticket
        val ctx = ticketContext(s, System.currentTimeMillis()).copy(
            bankrollUsd = paperBook.snapshot().equityUsd,
            bankrollSource = "paper"
        )
        return TicketBuilder.resizeForBankroll(ticket, market, ctx)
    }

    /** Re-mark cached holdings from the latest book / WS tick. No REST. */
    private fun refreshPositionMarks() {
        val raw = _state.value.positions
        if (raw.isEmpty()) return
        decoratePositions(raw)
    }

    private fun startPositionLoop() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            var lastFetch = 0L
            while (isActive) {
                val visible = com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()
                val now = System.currentTimeMillis()
                val headlessLive = !visible && liveArmedNow(_state.value.settings)
                if ((visible || headlessLive) && now - lastFetch >= KalshiPollBudget.POSITIONS_MS) {
                    lastFetch = now
                    refreshPositions()
                }
                delay(1_000L)
            }
        }
    }

    private fun refreshPositions() {
        viewModelScope.launch {
            val settings = _state.value.settings
            if (!com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground() &&
                !liveArmedNow(settings)
            ) return@launch
            if (!settings.tradingCredentialsConfigured()) {
                decoratePositions(_state.value.positions)
                return@launch
            }
            val fetched = withContext(Dispatchers.IO) {
                val positions = runCatching { container.tradeClient.listMarketPositions() }.getOrElse { emptyList() }
                val cashUsd = runCatching { container.tradeClient.getCashUsd() }.getOrNull()
                val resting = runCatching { container.tradeClient.listRestingOrders() }
                Triple(positions, cashUsd, resting)
            }
            val (rows, cash, orders) = fetched
            _state.update { cur ->
                cur.copy(
                    liveCashUsd = cash ?: cur.liveCashUsd,
                    liveCashAtMs = if (cash != null) System.currentTimeMillis() else cur.liveCashAtMs,
                    restingOrders = orders.getOrElse { cur.restingOrders }
                )
            }
            val parsed = PositionParser.parseAll(rows)
            decoratePositions(parsed)
        }
    }

    private fun decoratePositions(raw: List<LivePosition>) {
        val s = _state.value
        val now = System.currentTimeMillis()
        val markets = s.snapshot?.allMarkets.orEmpty().associateBy { it.ticker }
        val ctx = ticketContext(s, now)
        val decorated = raw.map { pos ->
            val market = markets[pos.ticker]
            val bid = market?.let { TicketBuilder.freshBestBid(it, pos.side, ctx) } ?: pos.bestBid
            PositionParser.decorate(pos, market, bid)
        }
        val note = when {
            !s.settings.tradingCredentialsConfigured() ->
                if (s.settings.kalshiDemoEnabled) {
                    "Add a Kalshi demo Key ID + PEM in Settings to load demo positions."
                } else {
                    "Add Kalshi API Key ID + PEM in Settings to load live positions."
                }
            decorated.isEmpty() -> "No open Kalshi positions."
            else -> null
        }
        _state.update {
            if (it.positions == decorated && it.positionsNote == note) it
            else it.copy(positions = decorated, positionsNote = note)
        }
    }

    private fun refreshSellAtConfirm(
        ticket: com.dirk.kalshiodds.signal.trade.TradeTicket,
        count: Int,
        price: Double,
        snap: OddsUiState
    ): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (!ticket.isSell) return ticket
        val bid = KalshiPrice.usable(price) ?: ticket.limitPrice
        // Send the contracts and price the confirm sheet showed. Do not re-price after confirm.
        return TicketBuilder.applySellQuote(ticket, count, bid, snap.settings.feeRate)
    }

    private fun resizeSell(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, count: Int, price: Double): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (!ticket.isSell) return ticket
        return TicketBuilder.applySellQuote(ticket, count, KalshiPrice.usable(price) ?: ticket.limitPrice)
    }

    /**
     * Alerts no longer auto-paper. AI paper fills go through
     * [runPaperAutopilot] (EV at the ask, any time in the window).
     */
    private fun paperFromAlerts(@Suppress("UNUSED_PARAMETER") alerts: List<SignalAlert>) {
        val live = MarketLifecycle.tradable(
            _state.value.snapshot?.allMarkets.orEmpty(),
            container.clock.nowMs()
        )
        runPaperAutopilot(live)
    }

    /**
     * 0.3.38 paper scalp tick: one quote per BTC/ETH/SOL 15m market from the live WS book (no book, no
     * quote), spot from the settlement feed (CF first, Coinbase fallback), σ from the in-app trailing
     * estimator. Never calls Kalshi.
     */
    private fun runScalp(live: List<MarketUiModel>, s: com.dirk.kalshiodds.signal.config.SignalSettings) {
        val now = container.clock.nowMs()
        live.forEach { market ->
            val series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(market.ticker)
            if (series !in com.dirk.kalshiodds.domain.CryptoMarkets.FIFTEEN_SERIES) return@forEach
            val book = hub.scoring.book.snapshotBook(market.ticker) ?: return@forEach
            val spot = settlementFor(market.ticker, now).spot
            val coin = when {
                series.contains("SOL") -> "SOL"
                series.contains("ETH") -> "ETH"
                else -> "BTC"
            }
            val sigma = container.scalp.observeSpot(coin, spot, now)
                ?: com.dirk.kalshiodds.decision.ScalpRule.defaultSigmaPerSec(market.ticker)
            val q = com.dirk.kalshiodds.decision.ScalpRule.quoteFromBook(
                ticker = market.ticker,
                nowMs = now,
                closeMs = market.closeTimeEpochMs,
                bookAgeMs = hub.scoring.book.bookAgeMs(market.ticker, now),
                yesBids = book.yes,
                noBids = book.no,
                spot = spot,
                strike = market.floorStrike ?: hub.scoring.book.strike(market.ticker),
                sigmaPerSec = sigma
            ) ?: return@forEach
            container.scalp.onQuote(q, enabled = s.paperTradingEnabled)
        }
    }

    /** Cancel one resting real-money order from the Home open-bets list. Needs typed REAL MONEY. */
    fun cancelRestingOrder(orderId: String, ticker: String?, typed: String) {
        if (!com.dirk.kalshiodds.signal.trade.RealMoneyPhrase.matches(typed)) {
            _state.update { it.copy(userMessage = "Type REAL MONEY to cancel a real order") }
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { container.tradeClient.cancelById(orderId, ticker) }
            }
            _state.update {
                it.copy(userMessage = if (r.isSuccess) "Cancelled resting order on ${ticker ?: orderId}" else "Cancel failed: ${r.exceptionOrNull()?.message ?: "error"}")
            }
            refreshPositions()
        }
    }

    fun tapLiveAutopilotApprove() {
        liveAutopilotSession.tapApprove()
        publishLiveArm()
    }

    /**
     * Second step of the Real Money confirm. Does not place an order.
     * Later ticks may send only through [AutopilotDispatch].
     */
    fun confirmLiveAutopilotRealMoney(typed: String) {
        if (!com.dirk.kalshiodds.signal.trade.RealMoneyPhrase.matches(typed)) {
            _state.update { it.copy(userMessage = "Type REAL MONEY to arm live Autopilot") }
            return
        }
        if (_state.value.settings.autopilotModeEnum() != com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE) {
            return
        }
        if (!_state.value.settings.tradingCredentialsConfigured()) {
            shadowBook.noteLiveError("Kalshi key missing — live Autopilot will not send")
            return
        }
        liveAutopilotSession.confirmRealMoney(typed)
        if (liveAutopilotSession.armed) {
            runCatching { LiveSignalsService.start(getApplication()) }
        }
        publishLiveArm()
    }

    /** Manual disarm (Real Money tab). Persists, so a restart stays disarmed. */
    fun disarmLiveAutopilot() {
        liveAutopilotSession.disarm()
        publishLiveArm()
    }

    fun clearLiveAutopilotError() {
        shadowBook.clearLiveError()
    }

    private fun publishLiveArm() {
        _state.update {
            it.copy(
                liveAutopilotArmed = liveAutopilotSession.armed,
                liveAutopilotApproveTapped = liveAutopilotSession.approveTapped
            )
        }
    }

    /**
     * Paper, shadow, or limited live. The order API runs only when
     * [com.dirk.kalshiodds.signal.paper.AutopilotDispatch] says so.
     */
    private fun runPaperAutopilot(live: List<MarketUiModel>) {
        val now0 = container.clock.nowMs()
        val backoff = container.paperBackoff
        if (backoff.blocked(now0)) return
        try {
            runPaperAutopilotOnce(live)
            backoff.onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 0.3.39: an error never turns Autopilot off. Back off 30 s doubling to 10 min, then resume.
            val msg = "Autopilot error (${t.javaClass.simpleName})"
            val wait = backoff.onError(now0, msg)
            android.util.Log.w("KashiAutopilot", "$msg — backing off ${wait / 1000}s")
            container.tradeEvents.autopilotBackoff("paper:${backoff.episodeStartMs}", msg, wait)
            runCatching { paperBook.rememberMessage("$msg — resumes in ${wait / 1000}s") }
        }
    }

    private fun runPaperAutopilotOnce(live: List<MarketUiModel>) {
        val s = _state.value.settings
        val assessments = assessForLedger(live, s)
        if (s.paperTradingEnabled) runFav15Ladder(live)
        runScalp(live, s)
        if (!s.paperTradingEnabled || !s.aiPaperAutopilotEnabled) return
        val mode = s.autopilotModeEnum()
        if (mode == com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE) {
            val snapB = _state.value
            container.tradeEvents.balance(
                needsBalance = true,
                fresh = com.dirk.kalshiodds.decision.LiveBalancePolicy.fresh(snapB.liveCashUsd, snapB.liveCashAtMs, container.clock.nowMs())
            )
        }
        paperBook.configure(
            kellyFraction = s.paperKellyFraction,
            feeRate = s.feeRate,
            startUsd = s.paperBankrollStartUsd
        )
        val now = container.clock.nowMs()
        val ctx = ticketContext(_state.value, now)
        live.forEach { market ->
            if (!com.dirk.kalshiodds.domain.CryptoMarkets.isAutopilotTicker(market.ticker)) return@forEach
            val book = hub.scoring.book.snapshotBook(market.ticker)
            val yesAsk = TicketBuilder.liveAsk(market, "YES", ctx)
            val noAsk = TicketBuilder.liveAsk(market, "NO", ctx)
            val snap = _state.value
            // 0.3.40: the whole step (paper tick, sizing from the fresh REAL balance in LIVE, the $5
            // floor, shadow record, dispatch gate, live claim) lives in AutopilotStep so tests run it.
            val outcome = com.dirk.kalshiodds.signal.paper.AutopilotStep.run(
                paperBook = paperBook,
                shadowBook = shadowBook,
                market = market,
                settings = s,
                mode = mode,
                nowMs = now,
                yesAsk = yesAsk,
                noAsk = noAsk,
                yesDepth = paperAskDepth("YES", yesAsk, market.ticker, market),
                noDepth = paperAskDepth("NO", noAsk, market.ticker, market),
                book = book,
                assessment = assessments[market.ticker.uppercase()],
                live = com.dirk.kalshiodds.signal.paper.AutopilotStep.Live(
                    cashUsd = snap.liveCashUsd,
                    cashAtMs = snap.liveCashAtMs,
                    armed = liveAutopilotSession.armed,
                    credentialsOk = s.tradingCredentialsConfigured(),
                    backoffBlocked = container.liveBackoff.blocked(now)
                ),
                clientOrderId = java.util.UUID.randomUUID().toString()
            )
            if (outcome !is com.dirk.kalshiodds.signal.paper.AutopilotStep.Outcome.Send) return@forEach
            val ticket = outcome.ticket
            viewModelScope.launch {
                val result = runCatching {
                    container.tradeClient.createLimit(
                        com.dirk.kalshiodds.signal.paper.ShadowOrderPayload.toTradeTicket(ticket),
                        ticket.clientOrderId
                    )
                }
                if (result.isSuccess) {
                    shadowBook.noteLiveSpend(ticket.clientOrderId, outcome.dayKey)
                    container.liveBackoff.onSuccess()
                    // 0.3.40: the once-per-order "Real bet placed" alert was only wired for manual tickets.
                    container.tradeEvents.realBetPlaced(
                        ticket.clientOrderId,
                        String.format(
                            java.util.Locale.US,
                            "Autopilot %s %s · %d ct @ %.0f¢ · $%.2f all-in",
                            if (ticket.side.equals("NO", true)) "DOWN" else "UP",
                            ticket.ticker, ticket.count, ticket.limitPrice * 100.0, ticket.stakeUsd
                        )
                    )
                } else {
                    shadowBook.releaseLiveReservation(ticket.clientOrderId, ticket.stakeUsd, outcome.dayKey)
                    val msg = result.exceptionOrNull()?.message ?: "Live Autopilot order failed"
                    shadowBook.noteLiveError(msg)
                    // Stay armed; back off and resume. This client_order_id is never retried.
                    val lb = container.liveBackoff
                    val wait = lb.onError(container.clock.nowMs(), msg)
                    container.tradeEvents.autopilotBackoff("live:${lb.episodeStartMs}", msg, wait)
                }
            }
        }
    }

    /**
     * 0.3.37: run the deterministic decision pipeline for every BTC/ETH/SOL
     * 15m market and write the prediction ledger, whether or not Autopilot is
     * on. Returns assessments keyed by upper-case ticker.
     */
    private fun assessForLedger(
        live: List<MarketUiModel>,
        s: com.dirk.kalshiodds.signal.config.SignalSettings
    ): Map<String, com.dirk.kalshiodds.decision.DecisionPipeline.Assessment> {
        val now = container.clock.nowMs()
        val ctx = ticketContext(_state.value, now)
        val out = HashMap<String, com.dirk.kalshiodds.decision.DecisionPipeline.Assessment>()
        live.forEach { market ->
            if (!com.dirk.kalshiodds.domain.CryptoMarkets.isAutopilotTicker(market.ticker)) return@forEach
            runCatching {
                val yesAsk = TicketBuilder.liveAsk(market, "YES", ctx)
                val noAsk = TicketBuilder.liveAsk(market, "NO", ctx)
                val input = com.dirk.kalshiodds.decision.DecisionInputs.build(
                    ticker = market.ticker,
                    nowMs = now,
                    closeTimeMs = market.closeTimeEpochMs,
                    rawModelYes = PaperAutopilot.modelYes(market),
                    marketYes = PaperAutopilot.marketYes(market, yesAsk, noAsk),
                    yesAsk = yesAsk,
                    noAsk = noAsk,
                    yesBid = market.yesBid,
                    noBid = market.noBid,
                    yesDepth = paperAskDepth("YES", yesAsk, market.ticker, market),
                    noDepth = paperAskDepth("NO", noAsk, market.ticker, market),
                    bookAgeMs = bookAgeMs(market.ticker, now),
                    strike = market.floorStrike ?: hub.scoring.book.strike(market.ticker),
                    volPerSec = volPerSecFor(market.ticker),
                    settlement = settlementFor(market.ticker, now),
                    feeRate = s.feeRate,
                    modelVersion = "app-${com.dirk.kalshiodds.BuildConfig.VERSION_NAME}"
                )
                out[market.ticker.uppercase()] = container.decisions.assess(input)
            }
        }
        publishDecisionLines(now)
        return out
    }

    /** Freshest of the WS book and the last REST snapshot; null if neither. */
    private fun bookAgeMs(ticker: String, now: Long): Long? {
        val ws = hub.scoring.book.bookAgeMs(ticker, now)
        val rest = _state.value.snapshot?.takeIf { !it.fromCache && it.fetchedAtEpochMs > 0L }
            ?.let { (now - it.fetchedAtEpochMs).coerceAtLeast(0L) }
        return listOfNotNull(ws, rest).minOrNull()
    }

    private fun volPerSecFor(ticker: String): Double? {
        val series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(ticker)
        return if (series.contains("BTC")) container.lastMinuteEngine.sigS()?.takeIf { it.isFinite() && it > 0.0 } else null
    }

    private fun settlementFor(ticker: String, now: Long): com.dirk.kalshiodds.decision.DecisionInputs.Settlement {
        val series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(ticker)
        val index = com.dirk.kalshiodds.signal.ws.CfBenchmarks.indexForSeries(series)
        val cf = index?.let { container.cfFeed.latest(it) }
        val cb = container.external.latest().forSeries(series)
        return com.dirk.kalshiodds.decision.DecisionInputs.settlement(
            series = series,
            nowMs = now,
            cfTick = cf,
            coinbaseSpot = cb?.lastPrice ?: hub.scoring.book.lastSpot(ticker),
            coinbaseAtMs = cb?.fetchedAtMs?.takeIf { it > 0L }
        )
    }

    private fun cfLines(now: Long): List<String> {
        val ext = container.external.latest()
        return com.dirk.kalshiodds.signal.ws.CfBenchmarks.INDEX_IDS.map { id ->
            val coin = com.dirk.kalshiodds.signal.ws.CfBenchmarks.coinOf(id)
            val cb = ext.forSeries(coin)?.lastPrice != null
            val st = container.cfFeed.status(id, now, coinbaseAvailable = cb)
            "$coin: ${st.detail}"
        }
    }

    private fun publishDecisionLines(now: Long) {
        val latest = container.decisions.latestAll()
        val lines = latest.entries.sortedBy { it.key }.take(12).map { (t, a) -> "$t — ${a.verdict.headline}" }
        val cf = cfLines(now)
        val cur = _state.value
        if (cur.decisionLines != lines || cur.cfFeedLines != cf) {
            _state.update { it.copy(decisionLines = lines, cfFeedLines = cf) }
        }
    }

    /** fav15 ladder (paper only): preregistered favourite rule on the 15m markets. */
    private fun runFav15Ladder(live: List<MarketUiModel>) {
        val now = container.clock.nowMs()
        val ctx = ticketContext(_state.value, now)
        live.forEach { market ->
            val series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(market.ticker)
            if (series !in com.dirk.kalshiodds.domain.CryptoMarkets.FIFTEEN_SERIES) return@forEach
            if (container.ladder.hasEntry(com.dirk.kalshiodds.decision.Fav15Rule.ID, market.ticker)) return@forEach
            val yesAsk = TicketBuilder.liveAsk(market, "YES", ctx)
            val noAsk = TicketBuilder.liveAsk(market, "NO", ctx)
            val age = bookAgeMs(market.ticker, now)
            val r = com.dirk.kalshiodds.decision.Fav15Rule.evaluate(
                yesAsk = yesAsk,
                noAsk = noAsk,
                yesAskSize = paperAskDepth("YES", yesAsk, market.ticker, market)?.toDouble(),
                noAskSize = paperAskDepth("NO", noAsk, market.ticker, market)?.toDouble(),
                secondsRemaining = market.closeTimeEpochMs?.let { (it - now) / 1000.0 },
                bookFresh = age != null && age <= com.dirk.kalshiodds.decision.FillModel.FRESH_BOOK_MS
            )
            if (r is com.dirk.kalshiodds.decision.Fav15Rule.Result.Enter) {
                container.ladder.record(
                    strategy = com.dirk.kalshiodds.decision.Fav15Rule.ID,
                    ticker = market.ticker,
                    event = market.ticker,
                    side = r.signal.side,
                    clip = r.signal.clip,
                    price = r.signal.price,
                    note = "fav15 ${r.signal.side} @ ${(r.signal.price * 100).toInt()}¢"
                )
            }
        }
    }

    /** v060 ladder (paper only) + daily ledger rows for KXBTCD / KXETHD / KXSOLD 5 PM ET. */
    private fun runDailyDecisions(quotesBySeries: Map<String, List<com.dirk.kalshiodds.signal.d3.D3Quote>>, fetchedAtMs: Long) {
        val s = _state.value.settings
        val now = container.clock.nowMs()
        val age = (now - fetchedAtMs).coerceAtLeast(0L)
        quotesBySeries.forEach { (series, quotes) ->
            quotes.forEach { q ->
                val raw = com.dirk.kalshiodds.decision.DecisionInputs.dailyRaw(q.yesBid, q.yesAsk, q.closeTimeEpochMs, now)
                    ?: return@forEach
                if (raw.second < 0.03 || raw.second > 0.97) return@forEach
                runCatching {
                    container.decisions.assess(
                        com.dirk.kalshiodds.decision.DecisionInputs.build(
                            ticker = q.ticker,
                            nowMs = now,
                            closeTimeMs = q.closeTimeEpochMs,
                            rawModelYes = raw.first,
                            marketYes = raw.second,
                            yesAsk = q.yesAsk,
                            noAsk = q.noAsk,
                            yesBid = q.yesBid,
                            noBid = q.noBid,
                            yesDepth = q.yesAskSize?.toInt(),
                            noDepth = q.noAskSize?.toInt(),
                            bookAgeMs = age,
                            strike = q.strikeUsd,
                            volPerSec = if (series.contains("BTC")) container.lastMinuteEngine.sigS() else null,
                            settlement = settlementFor(q.ticker, now),
                            feeRate = s.feeRate,
                            modelVersion = com.dirk.kalshiodds.decision.V060Rule.VERSION
                        )
                    )
                }
            }
        }
        if (!s.paperTradingEnabled) return
        val btc = quotesBySeries[com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTCD].orEmpty()
        if (age > com.dirk.kalshiodds.decision.FillModel.FRESH_BOOK_MS) return
        btc.groupBy { it.eventTicker ?: com.dirk.kalshiodds.decision.V060Rule.eventOf(it.ticker) }.forEach { (event, qs) ->
            val close = qs.firstNotNullOfOrNull { it.closeTimeEpochMs } ?: return@forEach
            val closeDay = java.time.Instant.ofEpochMilli(close).atZone(java.time.ZoneId.of("America/New_York")).dayOfWeek
            if (closeDay == java.time.DayOfWeek.FRIDAY) return@forEach
            val tau = (close - now) / 1000.0
            if (com.dirk.kalshiodds.decision.V060Rule.checkpoint(tau) == null) return@forEach
            if (container.ladder.hasEntry(com.dirk.kalshiodds.decision.V060Rule.ID, event)) return@forEach
            val pick = com.dirk.kalshiodds.decision.V060Rule.bestPick(
                qs.map {
                    com.dirk.kalshiodds.decision.V060Rule.Quote(it.ticker, it.yesBid, it.yesAsk, it.noAsk, it.yesAskSize, it.noAskSize)
                },
                tau
            ) ?: return@forEach
            val q = qs.first { it.ticker == pick.ticker }
            val visible = if (pick.side == "YES") q.yesAskSize else q.noAskSize
            if (visible == null || visible + 1e-9 < pick.clip.contracts) return@forEach
            container.ladder.record(
                strategy = com.dirk.kalshiodds.decision.V060Rule.ID,
                ticker = pick.ticker,
                event = event,
                side = pick.side,
                clip = pick.clip,
                price = pick.price,
                note = String.format(java.util.Locale.US, "v060 %s @ %.0f¢ EV/$ %+.3f", pick.side, pick.price * 100, pick.evPerDollar)
            )
        }
    }

    /**
     * Real ask-side size for a Kelly paper fill. Live book at/below the
     * paid ask, else displayed YES best-ask size, else skip (never unlimited).
     */
    private fun paperAskDepth(
        side: String,
        ask: Double?,
        ticker: String,
        market: MarketUiModel?
    ): Int? {
        val px = KalshiPrice.usable(ask) ?: return null
        val book = hub.scoring.book.snapshotBook(ticker)
        return PaperAskDepth.contracts(side, px, book, market)
    }

    private fun scheduleChartBackfill(snap: MarketsSnapshot) {
        val now = System.currentTimeMillis()
        val targets = snap.allMarkets.filter { m ->
            val end = m.closeTimeEpochMs ?: now
            val start = end - ChartWindowService.WINDOW_MS
            val last = backfillStarted[m.ticker]
            if (last != null && now - last < 60_000L) return@filter false
            chartWindows.needsBackfill(m.ticker, start, end)
        }
        if (targets.isEmpty()) return
        targets.forEach { backfillStarted[it.ticker] = now }
        backfillJob?.cancel()
        backfillJob = viewModelScope.launch(Dispatchers.IO) {
            for (m in targets) {
                if (!isActive) break
                val end = m.closeTimeEpochMs ?: now
                val start = end - ChartWindowService.WINDOW_MS
                runCatching {
                    chartWindows.backfillWindow(
                        ticker = m.ticker,
                        series = com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(m.ticker),
                        windowStartMs = start,
                        windowEndMs = end
                    )
                }
            }
            val latest = _state.value.snapshot ?: return@launch
            val overlaid = attachHistory(latest)
            _state.update { it.copy(snapshot = overlaid) }
        }
    }

    private fun attachHistory(snap: MarketsSnapshot): MarketsSnapshot {
        fun List<MarketUiModel>.withHist(): List<MarketUiModel> = map { raw ->
            val tick = runCatching { hub.scoring.book.lastTick(raw.ticker) }.getOrNull()
            val m = raw.withLiveQuote(tick)
            val pts = runCatching { hub.scoring.book.midHistoryPp(m.ticker) }.getOrElse { emptyList() }
            val last = com.dirk.kalshiodds.chart.ChartSeriesBuilder.sparklineMidsPp(
                listOf(m.yesProbabilityPercent?.toFloat())
            ).singleOrNull()
            val merged = com.dirk.kalshiodds.chart.ChartSeriesBuilder.sparklineMidsPp(
                if (last != null && (pts.isEmpty() || kotlin.math.abs(pts.last() - last) > 0.05f)) {
                    (pts + last).takeLast(com.dirk.kalshiodds.signal.config.SignalConstants.SPARKLINE_MAX_POINTS)
                } else {
                    pts
                }
            )
            val bids = runCatching { chartWindows.seriesForCard(m) }.getOrElse {
                val liveBids = runCatching { hub.scoring.book.bidHistory(m.ticker) }.getOrElse { emptyList() }
                val stored = runCatching {
                    container.archive.bidHistory(
                        m.ticker,
                        (m.closeTimeEpochMs ?: System.currentTimeMillis()) - 3_600_000L,
                        240
                    )
                }.getOrElse { emptyList() }
                com.dirk.kalshiodds.chart.ChartDownsampler.downsample(
                    (stored + liveBids)
                        .filter { it.hasQuote() || it.hasSpot() }
                        .sortedBy { it.tMs }
                        .distinctBy { it.tMs },
                    com.dirk.kalshiodds.chart.ChartDownsampler.CARD_POINTS
                )
            }
            val past = runCatching {
                container.archive.recentSettled(com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(m.ticker), 8)
                    .map { it.result.equals("yes", true) }
            }.getOrElse { emptyList() }
            val withOdds = if (merged.isEmpty() && m.oddsHistory.isEmpty()) m else m.copy(oddsHistory = merged.ifEmpty { m.oddsHistory })
            val withBids = if (bids.isEmpty() && withOdds.bidHistory.isEmpty()) withOdds else withOdds.copy(bidHistory = bids.ifEmpty { withOdds.bidHistory })
            if (past.isEmpty()) withBids else withBids.copy(pastSettlements = past)
        }
        return snap.copy(
            btc = snap.btc.withHist(),
            eth = snap.eth.withHist(),
            sol = snap.sol.withHist(),
            extra = snap.extra.withHist()
        )
    }

    private fun scoreLabel(result: MarketsSnapshot): String? {
        val c = result.modelScoreCorrect ?: return null
        val total = result.modelScoreTotal ?: return null
        if (total <= 0) return null
        return HomeCopy.scorecardLine(c, total, result.modelMeanBrier)
    }

    private fun nextDelayMs(): Long {
        val wsConnected = _state.value.signalStatus.state == WsConnectionState.CONNECTED
        val poll = if (wsConnected) {
            WS_METADATA_POLL_MS
        } else {
            val half = min(JITTER_MS, currentIntervalMs / 3)
            val jitter = if (half <= 0L) 0L else Random.nextLong(-half, half + 1)
            (currentIntervalMs + jitter).coerceAtLeast(MIN_POLL_MS)
        }
        // Do not inherit MarketRollover's wake. That floor used to be 50ms
        // and stacked a second GET /markets on top of this loop.
        return poll.coerceAtLeast(MIN_POLL_MS)
    }

    companion object {
        /** ETH/SOL daily quotes are ledger-only; refresh every 2 min on the rate-limited lane. */
        private const val OTHER_DAILY_MS = 120_000L
        const val BASE_POLL_MS = KalshiPollBudget.HOME_VISIBLE_MS
        const val JITTER_MS = 250L
        const val MIN_POLL_MS = KalshiPollBudget.HOME_VISIBLE_MS
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WS_METADATA_POLL_MS = 15_000L
        const val SCORE_OVERLAY_THROTTLE_MS = 250L
    }
}
