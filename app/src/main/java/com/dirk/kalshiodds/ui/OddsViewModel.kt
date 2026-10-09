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
    val paper: PaperBookState = PaperBookState(),
    val positions: List<LivePosition> = emptyList(),
    val positionsNote: String? = null,
    /** Live Kalshi cash from GET /portfolio/balance, if the key can read it. */
    val liveCashUsd: Double? = null,
    val persistedHistory: List<ScoredSnapshotRow> = emptyList(),
    val mlGuardNote: String? = null,
    val scorecardSummary: HomeScorecardSummary = HomeScorecardSummary.EMPTY,
    /** Paper-only late-favorite tracker ledger (home card). */
    val lateFavorite: com.dirk.kalshiodds.signal.latefav.LateFavoriteState =
        com.dirk.kalshiodds.signal.latefav.LateFavoriteState(),
    /** Paper-only flow-fade tracker (same ledger type as the late favorite). */
    val flowFade: com.dirk.kalshiodds.signal.latefav.LateFavoriteState =
        com.dirk.kalshiodds.signal.latefav.LateFavoriteState(),
    /** Paper-only 1¢-better resting bid tracker. */
    val centBetter: com.dirk.kalshiodds.signal.latefav.LateFavoriteState =
        com.dirk.kalshiodds.signal.latefav.LateFavoriteState(),
    /** Paper record of the clear-lead rule (same ledger type). */
    val clearLead: com.dirk.kalshiodds.signal.latefav.LateFavoriteState =
        com.dirk.kalshiodds.signal.latefav.LateFavoriteState(),
    /** Paper scalper scoreboard. */
    val scalper: com.dirk.kalshiodds.signal.scalper.ScalperState =
        com.dirk.kalshiodds.signal.scalper.ScalperState.fresh(),
    /** Paper scalper: (resting bids, open scalps) right now. */
    val scalperWorking: Pair<Int, Int> = 0 to 0,
    /** Paper limit orders resting right now. */
    val paperLimits: List<com.dirk.kalshiodds.signal.limit.PaperLimitOrder> = emptyList()
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

    /** Daily cap on live buys (Settings → Live Approve tickets). Never touches paper or sells. */
    private val liveCap = com.dirk.kalshiodds.signal.trade.LiveDailyCapStore.get(application)

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
                container.paperLimits.state.collect { resting ->
                    _state.update { it.copy(paperLimits = resting) }
                }
            }
        }
        viewModelScope.launch {
            // Paper limit orders expire by the clock even when no tick arrives for their window.
            runCatching {
                while (isActive) {
                    delay(1_000L)
                    val now = container.clock.nowMs()
                    container.paperLimits.openTickers().forEach { container.paperLimits.onClock(it, now) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                container.lateFavorite.ledger.state.collect { lf ->
                    _state.update { it.copy(lateFavorite = lf) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                container.flowFade.ledger.state.collect { ff ->
                    _state.update { it.copy(flowFade = ff) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                container.centBetter.ledger.state.collect { cb ->
                    _state.update { it.copy(centBetter = cb) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                container.clearLead.ledger.state.collect { cl ->
                    _state.update { it.copy(clearLead = cl) }
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                // The scoreboard changes many times a second while scalps close: show it once a second.
                while (isActive) {
                    val sc = container.scalperStore.ledger.snapshot()
                    val working = container.scalper.working()
                    if (sc !== _state.value.scalper || working != _state.value.scalperWorking) {
                        _state.update { it.copy(scalper = sc, scalperWorking = working) }
                    }
                    delay(1_000L)
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                combine(container.logStore.entriesFlow, paperBook.state) { entries, paper ->
                    HomeScorecardSummary.of(entries, paper.liveRealizedPnlUsd)
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
        bindRollover()
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
            watchBtc = true,
            watchEth = false,
            watchSol = false,
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
        val overlaid = attachHistory(
            pruned.overlayScores(hub.latestScores(), _state.value.settings.effectiveEdgeThresholdPp())
        )
        _state.update {
            it.copy(
                isLoading = false,
                snapshot = overlaid,
                userMessage = when {
                    event.retrying.isNotEmpty() -> null
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
        refreshPositions()
        scheduleChartBackfill(overlaid)
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
                com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Live -> approveLiveWithinCap(ticketId, ticket)
                is com.dirk.kalshiodds.signal.trade.ApproveRouter.Decision.Blocked ->
                    ticketSession.failSoft(decision.reason)
            }
        }
    }

    /**
     * Live Approve behind the daily cap. A buy that would pass the cap is
     * not sent and the reason is shown; sells are never capped. An accepted
     * buy is counted at its all-in cost.
     */
    private suspend fun approveLiveWithinCap(
        ticketId: String,
        ticket: com.dirk.kalshiodds.signal.trade.TradeTicket?
    ) {
        val isBuy = ticket != null && !ticket.isSell
        if (isBuy) {
            val cost = com.dirk.kalshiodds.signal.trade.LiveDailyCap.costOf(ticket!!)
            liveCap.blockReason(cost)?.let { reason ->
                ticketSession.failSoft(reason)
                return
            }
        }
        val before = ticketSession.placementCount
        val next = ticketSession.approve(ticketId)
        if (isBuy && next.placementCount > before) {
            val placed = (next.phase as? com.dirk.kalshiodds.signal.trade.TicketPhase.Submitted)?.order?.ticket
            liveCap.record(com.dirk.kalshiodds.signal.trade.LiveDailyCap.costOf(placed ?: ticket!!))
        }
        // A resting bid that is still open after its time is cancelled: it must not sit through the window.
        val order = (next.phase as? com.dirk.kalshiodds.signal.trade.TicketPhase.Submitted)?.order
        val cancelAfter = order?.ticket?.restingCancelAfterMs
        val orderId = order?.orderId
        if (order != null && cancelAfter != null && orderId != null && order.isResting) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(cancelAfter)
                val stillOpen = ticketSession.snapshot().working.any { it.orderId == orderId && it.isResting }
                if (stillOpen) cancelWorkingOrder(orderId)
            }
        }
    }

    /**
     * Turn the ticket awaiting Approve into a post-only resting bid one cent
     * above the best bid ([com.dirk.kalshiodds.signal.trade.RestingBid]).
     * Changes nothing at Kalshi; the user still has to Approve.
     */
    fun restTicket(ticketId: String) {
        val snap = _state.value
        val ticket = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId } ?: return
        val market = snap.snapshot?.allMarkets?.firstOrNull { it.ticker == ticket.ticker }
        val ctx = ticketContext(snap, container.clock.nowMs())
        val bid = market?.let { TicketBuilder.freshBestBid(it, ticket.side, ctx) }
        val ask = market?.let { TicketBuilder.bestAsk(it, ticket.side, ctx) }
        com.dirk.kalshiodds.signal.trade.RestingBid.build(ticket, bid, ask).fold(
            onSuccess = { rested -> ticketSession.revise(ticketId) { rested } },
            onFailure = { ticketSession.failSoft(it.message ?: "Cannot rest this order") }
        )
    }

    /** The limit-order editor's view of the app. Only [LimitHost.place] can send anything. */
    val limitHost: com.dirk.kalshiodds.signal.limit.LimitHost = object : com.dirk.kalshiodds.signal.limit.LimitHost {
        override fun quote(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket) = limitQuote(ticket.ticker, ticket.side)

        override fun queueAhead(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, price: Double): Double? =
            runCatching { container.paperLimits.queueAhead(ticket, price) }.getOrNull()

        override fun check(
            ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean
        ): String? = buildLimit(ticket, price, contracts, cancelAfterMs, paper).exceptionOrNull()?.message

        override fun place(ticketId: String, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean) =
            placeLimit(ticketId, price, contracts, cancelAfterMs, paper)

        override fun cancelPaper(orderId: String) {
            if (container.paperLimits.cancel(orderId)) {
                _state.update { it.copy(userMessage = container.paperLimits.lastMessage) }
            }
        }

        override val tradeFeedOn: Boolean get() = _state.value.settings.liveSignalsEnabled
    }

    /** Live best bid / ask and the sizes shown for [side] on [ticker]: the book first, then the last quotes. */
    private fun limitQuote(ticker: String, side: String): com.dirk.kalshiodds.signal.limit.LimitOrder.Quote {
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val yes = want == "YES"
        val s = _state.value
        val top = runCatching { container.scoring.book.topOfBook(ticker) }.getOrNull()?.takeIf { !it.isEmpty() }
        val market = s.snapshot?.allMarkets?.firstOrNull { it.ticker.equals(ticker, true) }
        val ctx = ticketContext(s, container.clock.nowMs())
        val bid = KalshiPrice.usable(if (yes) top?.yesBid else top?.noBid)
            ?: market?.let { TicketBuilder.freshBestBid(it, want, ctx) }
        val ask = KalshiPrice.usable(if (yes) top?.yesAsk else top?.noAsk)
            ?: market?.let { TicketBuilder.bestAsk(it, want, ctx) }
        return com.dirk.kalshiodds.signal.limit.LimitOrder.Quote(
            bid = bid,
            ask = ask,
            bidQty = if (yes) top?.yesBidQty else top?.noBidQty,
            askQty = if (yes) top?.yesAskQty else top?.noAskQty
        )
    }

    private fun limitOnPaper(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, paper: Boolean): Boolean =
        paper || ticket.paperOnly || _state.value.settings.paperTradingEnabled

    private fun buildLimit(
        ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean
    ): Result<com.dirk.kalshiodds.signal.trade.TradeTicket> {
        val s = _state.value
        val closeMs = s.snapshot?.allMarkets?.firstOrNull { it.ticker.equals(ticket.ticker, true) }?.closeTimeEpochMs
            ?: com.dirk.kalshiodds.signal.trade.TakerCost.closeEpochMs(ticket.ticker)
        return com.dirk.kalshiodds.signal.limit.LimitOrder.build(
            ticket = ticket,
            price = price,
            contracts = contracts,
            quote = limitQuote(ticket.ticker, ticket.side),
            cancelAfterMs = cancelAfterMs,
            closeMs = closeMs,
            nowMs = container.clock.nowMs(),
            paper = limitOnPaper(ticket, paper)
        )
    }

    /**
     * Rest a limit order at the user's price. Paper: onto the paper limit
     * book, filled later by real trades. Real: a post-only order through the
     * same approve gate, daily cap and $5 cap as every live buy, with an end
     * time Kalshi enforces. Called only from the editor's confirm buttons.
     */
    fun placeLimit(ticketId: String, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean) {
        viewModelScope.launch {
            val base = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
            if (base == null) {
                ticketSession.failSoft("Limit order ignored — no matching ticket")
                return@launch
            }
            val limit = buildLimit(base, price, contracts, cancelAfterMs, paper).getOrElse {
                ticketSession.failSoft(it.message ?: "Cannot place this limit order")
                return@launch
            }
            if (limitOnPaper(base, paper)) {
                val placed = container.paperLimits.place(limit, container.clock.nowMs())
                val msg = container.paperLimits.lastMessage ?: "Paper limit order"
                if (placed.isSuccess) {
                    runCatching {
                        container.resultsWriter.enqueueTicket(
                            com.dirk.kalshiodds.data.local.results.TicketAttemptRow(
                                ticker = limit.ticker,
                                side = limit.side,
                                stakeUsd = limit.stakeUsd,
                                approved = true,
                                result = "paper limit resting",
                                createdAtMs = System.currentTimeMillis(),
                                note = msg
                            )
                        )
                    }
                    ticketSession.dismiss(ticketId)
                }
                ticketSession.failSoft(msg)
                _state.update { it.copy(userMessage = msg) }
                return@launch
            }
            if (!_state.value.settings.tradingCredentialsConfigured()) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before sending a real order")
                return@launch
            }
            if (limit.isSell && restingSellOn(limit.ticker)) {
                ticketSession.failSoft(RESTING_SELL_FIRST)
                return@launch
            }
            ticketSession.revise(ticketId) { limit }
            approveLiveWithinCap(ticketId, limit)
            // Say what happened: the confirm sheet is gone by now.
            val after = ticketSession.snapshot()
            val msg = when (val phase = after.phase) {
                is com.dirk.kalshiodds.signal.trade.TicketPhase.Submitted -> {
                    val o = phase.order
                    "REAL limit ${com.dirk.kalshiodds.signal.limit.PaperLimitBook.describe(o.ticket)} is on Kalshi" +
                        (if (o.filledContracts > 0) " · ${o.filledContracts} filled already" else " · resting")
                }
                is com.dirk.kalshiodds.signal.trade.TicketPhase.Failed -> phase.error
                else -> after.lastError
            }
            if (msg != null) _state.update { it.copy(userMessage = msg) }
        }
    }

    /** True while a real limit sell is resting on [ticker]: a second sell could sell more than is held. */
    private fun restingSellOn(ticker: String): Boolean =
        ticketSession.snapshot().working.any {
            it.ticket.isSell && it.isResting && it.error?.startsWith("cancelled") != true &&
                (it.ticket.expiresAtMs ?: Long.MAX_VALUE) > container.clock.nowMs() &&
                it.ticket.ticker.equals(ticker, true)
        }

    fun approveSellTicket(ticketId: String, count: Int, price: Double) {
        viewModelScope.launch {
            val settings = _state.value.settings
            val selling = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
            if (selling != null && restingSellOn(selling.ticker)) {
                ticketSession.failSoft(RESTING_SELL_FIRST)
                return@launch
            }
            if (!settings.tradingCredentialsConfigured()) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Approving")
                return@launch
            }
            val now = System.currentTimeMillis()
            val snap = _state.value
            val ctx = ticketContext(snap, now)
            ticketSession.revise(ticketId) { t ->
                refreshSellAtConfirm(t, count, price, snap, ctx)
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
        viewModelScope.launch {
            val next = ticketSession.cancelWorking(orderId)
            // Give the cancelled, unfilled part of a live buy back to today's cap.
            val cancelled = (next.phase as? com.dirk.kalshiodds.signal.trade.TicketPhase.Cancelled)
                ?.order
                ?.takeIf { it.orderId == orderId && !it.ticket.isSell }
            if (cancelled != null) {
                liveCap.release(com.dirk.kalshiodds.signal.trade.LiveDailyCap.cancelledCostOf(cancelled))
            }
        }
    }

    fun setPaperTrading(enabled: Boolean) {
        viewModelScope.launch { prefs.updatePaperTrading(enabled) }
    }

    fun resetPaperBook() {
        val before = paperBook.snapshot().cashUsd
        val snap = _state.value.settings
        paperBook.reset()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                container.archive.insertSettingsChange(
                    com.dirk.kalshiodds.data.local.history.SettingsChange(
                        createdAtMs = System.currentTimeMillis(),
                        key = "paper_reset",
                        oldValue = before.toString(),
                        newValue = "100.0",
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
                s.copy(snapshot = attachHistory(snap.overlayScores(scores, s.settings.effectiveEdgeThresholdPp())))
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
        val liveTickers = live.map { it.ticker }.toSet()
        val ctx = ticketContext(s, now)
        val stale = s.tickets.proposals.map { it.ticker }.filter { it !in liveTickers }.toSet()
        if (stale.isNotEmpty()) ticketSession.voidTickers(stale)
        val tickets = TicketBuilder.proposeAll(live, ctx)
        ticketSession.replaceProposals(tickets, liveTickers = liveTickers)
        // The clear-lead ticket is built on demand by the Buy button, so it is
        // not in the proposal list; it still gets the heads-up notification.
        val clearLeadTickets = live.mapNotNull { TicketBuilder.proposeClearLead(it, ctx) }
        runCatching {
            container.opportunities.consider(
                tickets = tickets + clearLeadTickets,
                enabled = s.settings.opportunityAlertsEnabled && s.settings.notificationsEnabled,
                quiet = s.settings.opportunityQuiet
            )
        }
        if (s.settings.paperTradingEnabled) {
            val paperCtx = ctx.copy(
                bankrollUsd = paperBook.snapshot().equityUsd,
                bankrollSource = "paper"
            )
            val paperTickets = TicketBuilder.proposeAll(live, paperCtx)
            paperTickets.filter { it.canApprove }.forEach { paperBook.considerTicket(it, enabled = true) }
        }
        refreshPositionMarks()
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

    private fun refreshPositions() {
        viewModelScope.launch {
            val settings = _state.value.settings
            if (!settings.tradingCredentialsConfigured()) {
                decoratePositions(_state.value.positions)
                return@launch
            }
            val (rows, cash) = withContext(Dispatchers.IO) {
                val positions = runCatching { container.tradeClient.listMarketPositions() }.getOrElse { emptyList() }
                val cashUsd = runCatching { container.tradeClient.getCashUsd() }.getOrNull()
                positions to cashUsd
            }
            if (cash != null) {
                _state.update { it.copy(liveCashUsd = cash) }
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
        snap: OddsUiState,
        ctx: TicketBuilder.Context
    ): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (!ticket.isSell) return ticket
        if (ticket.paperOnly) {
            return TicketBuilder.applySellQuote(ticket, count, KalshiPrice.usable(price) ?: ticket.limitPrice)
        }
        val market = snap.snapshot?.allMarkets.orEmpty()
            .firstOrNull { it.ticker.equals(ticket.ticker, true) }
            ?: sellMarketFallback(ticket, snap)
        val fresh = market?.let { TicketBuilder.freshBestBid(it, ticket.side, ctx) }
        if (fresh == null) {
            return TicketBuilder.applySellQuote(ticket, count, bid = null)
        }
        // Live sell always uses the fresh book bid. Never a stale or higher limit.
        return TicketBuilder.applySellQuote(ticket, count, fresh, snap.settings.feeRate)
    }

    private fun sellMarketFallback(
        ticket: com.dirk.kalshiodds.signal.trade.TradeTicket,
        snap: OddsUiState
    ): MarketUiModel? {
        val pos = snap.positions.firstOrNull {
            it.ticker.equals(ticket.ticker, true) && it.side.equals(ticket.side, true)
        } ?: return null
        return MarketUiModel(
            ticker = ticket.ticker,
            title = pos.title ?: ticket.title ?: ticket.ticker,
            subtitle = null,
            floorStrike = null,
            yesBid = if (ticket.side == "YES") pos.bestBid else null,
            yesAsk = null,
            noBid = if (ticket.side == "NO") pos.bestBid else null,
            noAsk = null,
            lastPrice = pos.bestBid,
            yesProbabilityPercent = null,
            noProbabilityPercent = null,
            volume = null,
            volume24h = null,
            openInterest = null,
            liquidityDollars = null,
            closeTimeLocal = null,
            closeTimeEpochMs = pos.closeTimeEpochMs,
            status = "active",
            seriesLabel = com.dirk.kalshiodds.domain.CryptoMarkets.kindFor(ticket.ticker).label
        )
    }

    private fun resizeSell(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, count: Int, price: Double): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (!ticket.isSell) return ticket
        return TicketBuilder.applySellQuote(ticket, count, KalshiPrice.usable(price) ?: ticket.limitPrice)
    }

    private fun paperFromAlerts(alerts: List<SignalAlert>) {
        if (!_state.value.settings.paperTradingEnabled) return
        val markets = _state.value.snapshot?.allMarkets.orEmpty().associateBy { it.ticker }
        val now = System.currentTimeMillis()
        alerts.forEach { alert ->
            if (!com.dirk.kalshiodds.domain.CryptoMarkets.isLiveTicker(alert.ticker)) return@forEach
            val market = markets[alert.ticker]
            if (market != null && !MarketLifecycle.isTradable(market, now)) return@forEach
            val ask = market?.let {
                TicketBuilder.bestAsk(it, alert.predictedSide, ticketContext(s = _state.value, nowMs = now))
            }
            paperBook.considerAlert(alert, ask, enabled = true)
        }
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
        return minOf(poll, container.rollover.nextDelayMs()).coerceAtLeast(50L)
    }

    companion object {
        const val RESTING_SELL_FIRST = "A limit sell is already resting on this window: cancel it first"
        const val BASE_POLL_MS = 750L
        const val JITTER_MS = 250L
        const val MIN_POLL_MS = 500L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WS_METADATA_POLL_MS = 15_000L
        const val SCORE_OVERLAY_THROTTLE_MS = 250L
    }
}
