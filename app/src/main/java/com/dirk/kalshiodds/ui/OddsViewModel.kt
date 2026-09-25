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
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TicketUiState
import com.dirk.kalshiodds.chart.hasSpot
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
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
    val mlGuardNote: String? = null
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

    private val _state = MutableStateFlow(OddsUiState(isLoading = true))
    val state: StateFlow<OddsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var ticketRebuildJob: Job? = null
    private var scoreOverlayJob: Job? = null
    private val overlayThrottle = OverlayThrottle<Map<String, ScoringEngine.Score>>(
        intervalMs = SCORE_OVERLAY_THROTTLE_MS
    )
    private var currentIntervalMs: Long = BASE_POLL_MS

    init {
        ticketSession.onStart()
        _state.update { it.copy(paper = paperBook.snapshot()) }
        viewModelScope.launch {
            runCatching {
                ticketSession.state.collect { tickets ->
                    _state.update { it.copy(tickets = tickets) }
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
        viewModelScope.launch {
            runCatching {
                hub.status.collect { status -> _state.update { it.copy(signalStatus = status) } }
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
        val overlaid = attachHistory(
            result.overlayScores(hub.latestScores(), _state.value.settings.effectiveEdgeThresholdPp())
        )
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
        refreshPositions()
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
            if (ticket != null && ticket.isSell && !ticket.paperOnly) {
                if (!settings.credentialsConfigured) {
                    ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Approving a live sell")
                    return@launch
                }
                if (!ticket.canApprove) return@launch
                ticketSession.approve(ticketId)
                return@launch
            }
            if (settings.paperTradingEnabled || ticket?.paperOnly == true) {
                applyPaperBuy(ticketId)
                return@launch
            }
            if (!settings.credentialsConfigured) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Live Approve — or turn on Paper trading")
                return@launch
            }
            if (ticket != null && !ticket.canApprove) {
                ticketSession.failSoft(ticket.blockedReason ?: "Ticket cannot be approved")
                return@launch
            }
            ticketSession.approve(ticketId)
        }
    }

    fun approveSellTicket(ticketId: String, count: Int, price: Double) {
        viewModelScope.launch {
            val settings = _state.value.settings
            if (!settings.credentialsConfigured) {
                ticketSession.failSoft("Add Kalshi API Key ID + PEM in Settings before Approving")
                return@launch
            }
            ticketSession.revise(ticketId) { t ->
                resizeSell(t, count, price)
            }
            val ticket = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
            if (ticket != null && !ticket.canApprove) return@launch
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

    private fun applyPaperBuy(ticketId: String) {
        val ticket = ticketSession.snapshot().proposals.firstOrNull { it.id == ticketId }
        if (ticket == null) {
            ticketSession.failSoft("Paper buy ignored — no matching ticket")
            return
        }
        val sized = if (ticket.isSell) ticket else paperSized(ticket)
        val outcome = com.dirk.kalshiodds.signal.paper.PaperBuy.execute(paperBook, sized)
        if (outcome.ok) {
            ticketSession.failSoft(outcome.message)
            _state.update { it.copy(userMessage = outcome.message, paper = paperBook.snapshot()) }
            runCatching {
                container.resultsWriter.enqueueTicket(
                    com.dirk.kalshiodds.data.local.results.TicketAttemptRow(
                        ticker = ticket.ticker,
                        side = ticket.side,
                        stakeUsd = outcome.stakeUsd,
                        approved = true,
                        result = "paper filled",
                        createdAtMs = System.currentTimeMillis(),
                        note = outcome.message
                    )
                )
            }
            ticketSession.dismiss(ticketId)
        } else {
            ticketSession.failSoft(outcome.visibleReason)
            _state.update { it.copy(userMessage = outcome.visibleReason, paper = paperBook.snapshot()) }
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
     * Open an approve-gated Buy sheet for [side] on [market]. Never places.
     * Closed 15m windows remap to the current live contract. Missing asks
     * become a disabled ticket card — never a page-level "No ask to size" error.
     */
    fun buyMarket(market: MarketUiModel, side: String) {
        val s = _state.value
        if (!s.settings.ticketsEnabled) {
            ticketSession.failSoft("Turn on trade tickets in Settings to buy")
            return
        }
        val now = System.currentTimeMillis()
        val all = s.snapshot?.allMarkets.orEmpty()
        val target = MarketLifecycle.resolveLive(market, all, now)
        val ticketCtx = ticketContext(s, now)
        val ticket = TicketBuilder.proposeManual(target, side, ticketCtx) ?: return
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

    private fun rebuildTickets() {
        val s = _state.value
        val now = System.currentTimeMillis()
        val markets = s.snapshot?.allMarkets.orEmpty()
        val live = MarketLifecycle.tradable(markets, now)
        val liveTickers = live.map { it.ticker }.toSet()
        val ctx = ticketContext(s, now)
        val remappedManuals = s.tickets.proposals.mapNotNull { existing ->
            if (existing.kind != TicketKind.MANUAL) return@mapNotNull null
            if (existing.ticker in liveTickers) return@mapNotNull null
            val next = MarketLifecycle.liveSuccessor(existing.ticker, live, now) ?: return@mapNotNull null
            TicketBuilder.proposeManual(next, existing.side, ctx)
        }
        val tickets = TicketBuilder.proposeAll(live, ctx) + remappedManuals
        ticketSession.replaceProposals(tickets, liveTickers = liveTickers)
        runCatching {
            container.opportunities.consider(
                tickets = tickets,
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
            if (!settings.credentialsConfigured) {
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
            val bid = market?.let { TicketBuilder.bestBid(it, pos.side, ctx) } ?: pos.bestBid
            PositionParser.decorate(pos, market, bid)
        }
        val note = when {
            !s.settings.credentialsConfigured ->
                "Add Kalshi API Key ID + PEM in Settings to load live positions."
            decorated.isEmpty() -> "No open Kalshi positions."
            else -> null
        }
        _state.update {
            if (it.positions == decorated && it.positionsNote == note) it
            else it.copy(positions = decorated, positionsNote = note)
        }
    }

    private fun resizeSell(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket, count: Int, price: Double): com.dirk.kalshiodds.signal.trade.TradeTicket {
        if (!ticket.isSell) return ticket
        val held = ticket.heldContracts ?: ticket.contracts
        val qty = count.coerceIn(1, held.coerceAtLeast(1))
        val bid = KalshiPrice.usable(price) ?: ticket.limitPrice
        val yesLimit = if (ticket.side == "YES") bid else (1.0 - bid)
        val proceeds = qty * bid
        return ticket.copy(
            contracts = qty,
            limitPrice = bid,
            yesLimitPrice = KalshiPrice.clipLimit(yesLimit),
            stakeUsd = proceeds,
            estimatedFillUsd = proceeds,
            maxPayoutUsd = proceeds,
            estimatedAvgFill = bid,
            sizingNote = "$qty ct · sell ${ticket.side} @ ${String.format(java.util.Locale.US, "%.1f¢", bid * 100.0)} · reduce-only"
        )
    }

    private fun paperFromAlerts(alerts: List<SignalAlert>) {
        if (!_state.value.settings.paperTradingEnabled) return
        val markets = _state.value.snapshot?.allMarkets.orEmpty().associateBy { it.ticker }
        val now = System.currentTimeMillis()
        alerts.forEach { alert ->
            val market = markets[alert.ticker]
            if (market != null && !MarketLifecycle.isTradable(market, now)) return@forEach
            val ask = market?.let {
                TicketBuilder.bestAsk(it, alert.predictedSide, ticketContext(s = _state.value, nowMs = now))
            }
            paperBook.considerAlert(alert, ask, enabled = true)
        }
    }

    private fun attachHistory(snap: MarketsSnapshot): MarketsSnapshot {
        fun List<MarketUiModel>.withHist(): List<MarketUiModel> = map { m ->
            val pts = runCatching { hub.scoring.book.midHistoryPp(m.ticker) }.getOrElse { emptyList() }
            val last = m.yesProbabilityPercent?.toFloat()
            val merged = if (last != null && (pts.isEmpty() || kotlin.math.abs(pts.last() - last) > 0.05f)) {
                (pts + last).takeLast(com.dirk.kalshiodds.signal.config.SignalConstants.SPARKLINE_MAX_POINTS)
            } else {
                pts
            }
            val liveBids = runCatching { hub.scoring.book.bidHistory(m.ticker) }.getOrElse { emptyList() }
            val stored = runCatching {
                container.archive.bidHistory(
                    m.ticker,
                    (m.closeTimeEpochMs ?: System.currentTimeMillis()) - 3_600_000L,
                    240
                )
            }.getOrElse { emptyList() }
            val bids = com.dirk.kalshiodds.chart.ChartDownsampler.downsample(
                (stored + liveBids)
                    .filter {
                        com.dirk.kalshiodds.signal.engine.QuoteSanity.usableCents(it.upBidCents) != null ||
                            com.dirk.kalshiodds.signal.engine.QuoteSanity.usableCents(it.downBidCents) != null ||
                            it.hasSpot()
                    }
                    .sortedBy { it.tMs }
                    .distinctBy { it.tMs },
                com.dirk.kalshiodds.chart.ChartDownsampler.CARD_POINTS
            )
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
        const val SCORE_OVERLAY_THROTTLE_MS = 250L
    }
}
