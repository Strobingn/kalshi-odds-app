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
    val restingOrders: List<com.dirk.kalshiodds.signal.trade.RestingOrder> = emptyList(),
    val persistedHistory: List<ScoredSnapshotRow> = emptyList(),
    val mlGuardNote: String? = null,
    val scorecardSummary: HomeScorecardSummary = HomeScorecardSummary.EMPTY,
    val d3: com.dirk.kalshiodds.signal.d3.D3Snapshot = com.dirk.kalshiodds.signal.d3.D3Snapshot.EMPTY
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
    private val liveAutopilotSession = com.dirk.kalshiodds.signal.paper.LiveAutopilotSession()

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

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
                if (!com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()) {
                    delay(1_000L)
                    continue
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
                delay(nextDelayMs())
            }
        }
    }

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
        val snap = _state.value.snapshot ?: return
        val next = attachLastMinute(snap)
        _state.update { it.copy(snapshot = next) }
        scheduleRebuildTickets(immediate = true)
    }

    private fun attachLastMinute(snap: MarketsSnapshot): MarketsSnapshot {
        val stake = _state.value.settings.ticketStakeUsd
        container.lastMinuteEngine.forgetStale(snap.allMarkets.map { it.ticker }.toSet())
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
        return TicketBuilder.Context(
            settings = s.settings,
            alertsPaused = s.alertsPaused,
            books = books,
            ticks = ticks,
            positions = s.positions,
            nowMs = nowMs,
            bankrollUsd = s.liveCashUsd ?: s.settings.bankrollUsd,
            bankrollSource = if (s.liveCashUsd != null) "live" else "settings"
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
                if (visible && now - lastFetch >= KalshiPollBudget.POSITIONS_MS) {
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
            if (!com.dirk.kalshiodds.signal.service.LiveSignalsKeepAlive.isUiInForeground()) return@launch
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

    fun tapLiveAutopilotApprove() {
        liveAutopilotSession.tapApprove()
        publishLiveArm()
    }

    /**
     * Second step of the Real Money confirm. Does not place an order.
     * Later ticks may send only through [AutopilotDispatch].
     */
    fun confirmLiveAutopilotRealMoney() {
        if (_state.value.settings.autopilotModeEnum() != com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE) {
            return
        }
        if (!_state.value.settings.tradingCredentialsConfigured()) {
            shadowBook.noteLiveError("Kalshi key missing — live Autopilot will not send")
            return
        }
        liveAutopilotSession.confirmRealMoney()
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
        val s = _state.value.settings
        if (!s.paperTradingEnabled || !s.aiPaperAutopilotEnabled) return
        val mode = s.autopilotModeEnum()
        paperBook.configure(
            kellyFraction = s.paperKellyFraction,
            feeRate = s.feeRate,
            startUsd = s.paperBankrollStartUsd
        )
        val now = container.clock.nowMs()
        val ctx = ticketContext(_state.value, now)
        val day = com.dirk.kalshiodds.signal.paper.LiveAutopilotGate.dayKey(now)
        live.forEach { market ->
            if (!com.dirk.kalshiodds.domain.CryptoMarkets.isAutopilotTicker(market.ticker)) return@forEach
            val book = hub.scoring.book.snapshotBook(market.ticker)
            val yesAsk = TicketBuilder.liveAsk(market, "YES", ctx)
            val noAsk = TicketBuilder.liveAsk(market, "NO", ctx)
            val yesDepth = paperAskDepth("YES", yesAsk, market.ticker, market)
            val noDepth = paperAskDepth("NO", noAsk, market.ticker, market)
            val tick = PaperAutopilot.tick(
                paperBook = paperBook,
                market = market,
                settings = s,
                nowMs = now,
                yesAsk = yesAsk,
                noAsk = noAsk,
                yesDepth = yesDepth,
                noDepth = noDepth,
                book = book,
                bookPaper = mode != com.dirk.kalshiodds.signal.paper.AutopilotMode.SHADOW
            )
            val picked = tick.decision.side
            if (!tick.decision.ok || picked == null) return@forEach
            if (mode == com.dirk.kalshiodds.signal.paper.AutopilotMode.PAPER) return@forEach
            val depth = if (picked.side.equals("NO", true)) noDepth else yesDepth
            val sized = if (mode == com.dirk.kalshiodds.signal.paper.AutopilotMode.LIVE) {
                val snap = _state.value
                if (!com.dirk.kalshiodds.decision.LiveBalancePolicy.fresh(snap.liveCashUsd, snap.liveCashAtMs, now)) {
                    paperBook.rememberMessage(com.dirk.kalshiodds.decision.LiveBalancePolicy.REASON)
                    return@forEach
                }
                val live = com.dirk.kalshiodds.signal.paper.PaperKellySizer.size(
                    winProb = picked.winProb,
                    ask = picked.ask,
                    bankrollUsd = snap.liveCashUsd ?: 0.0,
                    kellyFraction = s.paperKellyFraction,
                    feeRate = s.feeRate,
                    depthContracts = depth
                )
                if (!live.ok || com.dirk.kalshiodds.decision.AutopilotMinStake.below(live.allInUsd)) {
                    paperBook.rememberMessage(
                        if (com.dirk.kalshiodds.decision.AutopilotMinStake.below(live.allInUsd)) {
                            com.dirk.kalshiodds.decision.AutopilotMinStake.REASON
                        } else {
                            live.reason ?: "NO BET — balance unavailable"
                        }
                    )
                    return@forEach
                }
                live
            } else {
                com.dirk.kalshiodds.signal.paper.AutopilotOrderSize.quote(
                    decision = tick.decision,
                    fill = tick.fill,
                    depth = depth,
                    kellyFraction = s.paperKellyFraction,
                    feeRate = s.feeRate,
                    cashUsd = paperBook.snapshot().cashUsd
                )
            }
            val tags = com.dirk.kalshiodds.signal.paper.AutopilotRegime.tags(market, picked.side, picked.ask, now)
            val draft = com.dirk.kalshiodds.signal.paper.ShadowOrderPayload.fromKelly(
                ticker = market.ticker,
                side = picked.side,
                sized = sized,
                depth = depth,
                reason = "Autopilot edge ${String.format(java.util.Locale.US, "%.1f¢", picked.evPerContract * 100)} after fees",
                nowMs = now,
                clientOrderId = java.util.UUID.randomUUID().toString(),
                regimeKey = tags.key
            )
            val recorded = shadowBook.record(draft)
            val ticket = recorded.ticket
            val dispatch = com.dirk.kalshiodds.signal.paper.AutopilotDispatch.decide(
                com.dirk.kalshiodds.signal.paper.AutopilotDispatch.Request(
                    mode = mode,
                    masterOn = true,
                    decisionOk = true,
                    paperFilled = tick.fill != null,
                    armed = liveAutopilotSession.armed,
                    credentialsOk = s.tradingCredentialsConfigured(),
                    failClosed = shadowBook.snapshot().liveLastError != null,
                    paperSide = picked.side,
                    paperPrice = picked.ask,
                    shadowSide = ticket.side,
                    shadowPrice = ticket.limitPrice,
                    shadowDepthFill = ticket.depthFill,
                    shadowAllInUsd = ticket.stakeUsd,
                    alreadyAttempted = shadowBook.snapshot().attempted(ticket.clientOrderId)
                )
            )
            if (!dispatch.shouldPlace || !recorded.isNew) return@forEach
            if (!shadowBook.claimLive(ticket.clientOrderId, day, ticket.stakeUsd)) {
                return@forEach
            }
            viewModelScope.launch {
                val result = runCatching {
                    container.tradeClient.createLimit(
                        com.dirk.kalshiodds.signal.paper.ShadowOrderPayload.toTradeTicket(ticket),
                        ticket.clientOrderId
                    )
                }
                if (result.isSuccess) {
                    shadowBook.noteLiveSpend(ticket.clientOrderId, day)
                } else {
                    shadowBook.releaseLiveReservation(ticket.clientOrderId, ticket.stakeUsd, day)
                    val msg = result.exceptionOrNull()?.message ?: "Live Autopilot order failed"
                    shadowBook.noteLiveError(msg)
                    liveAutopilotSession.disarm()
                    publishLiveArm()
                }
            }
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
        const val BASE_POLL_MS = KalshiPollBudget.HOME_VISIBLE_MS
        const val JITTER_MS = 250L
        const val MIN_POLL_MS = KalshiPollBudget.HOME_VISIBLE_MS
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WS_METADATA_POLL_MS = 15_000L
        const val SCORE_OVERLAY_THROTTLE_MS = 250L
    }
}
