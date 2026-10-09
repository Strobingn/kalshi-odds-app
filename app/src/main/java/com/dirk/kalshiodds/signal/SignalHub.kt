package com.dirk.kalshiodds.signal

import android.os.SystemClock
import android.util.Log
import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.results.OddsMidRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.data.repo.MarketsSnapshot
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.prediction.SignalSnapshot
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookScoreGate
import com.dirk.kalshiodds.signal.engine.LatestWinsMailbox
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.engine.SpotRescoreGate
import java.util.concurrent.atomic.AtomicBoolean
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.model.SignalStatus
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.model.WsConnectionState
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
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
    private val results: AsyncResultsWriter? = null,
    /** Paper-only late-favorite tracker; logs, never orders. */
    private val lateFavorite: com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger? = null,
    /** Paper-only flow-fade tracker (docs/flow-fade-2026-10-04.md); logs, never orders. */
    private val flowFade: com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger? = null,
    /** Paper-only "1¢ better" resting bid (maker_sim improve rule); logs, never orders. */
    private val centBetter: com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger? = null,
    /** Paper record of the clear-lead rule; logs, never orders. */
    private val clearLead: com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger? = null,
    /** Paper scalper: every scalping strategy at once, on paper. Never orders. */
    private val scalper: com.dirk.kalshiodds.signal.scalper.PaperScalper? = null,
    /** Paper limit orders the user placed: filled by the same real trade prints. Never orders. */
    private val paperLimits: (() -> com.dirk.kalshiodds.signal.limit.PaperLimitBook?)? = null,
    tickDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diphunter-ticks").apply { priority = Thread.NORM_PRIORITY + 1; isDaemon = true }
    }.asCoroutineDispatcher()
) {
    private val job = SupervisorJob()
    val tickScope = CoroutineScope(
        job + tickDispatcher + CoroutineExceptionHandler { _, t ->
            CrashBreadcrumb.record("tick", t)
            Log.e(TAG, "tick", t)
        }
    )
    private val persistScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            CrashBreadcrumb.record("persist", t)
        }
    )
    private val lastBookPublishMs = ConcurrentHashMap<String, Long>()
    private val lastOddsPersistMs = ConcurrentHashMap<String, Long>()
    private val lastOddsMid = ConcurrentHashMap<String, Double>()
    private val lastChartPersistMs = ConcurrentHashMap<String, Long>()
    private val tickMailbox = LatestWinsMailbox<MarketTick>()
    private val bookMailbox = LatestWinsMailbox<Long>()
    private val spotMailbox = LatestWinsMailbox<Long>()
    private val lastSpotRescoreMs = ConcurrentHashMap<String, Long>()
    private val logWriteBusy = AtomicBoolean(false)

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
        if (tickers.isNotEmpty()) {
            runCatching { scoring.book.pruneTo(tickers) }
            runCatching { flowWindow.retain(tickers) }
            runCatching { flowMaker.retain(tickers) }
            runCatching { centMaker.retain(tickers) }
            lastSpotRescoreMs.keys.retainAll(tickers)
            _scores.update { cur -> cur.filterKeys { it in tickers } }
        }
    }

    fun restoreAlerts(alerts: List<SignalAlert>) {
        if (alerts.isEmpty()) return
        _alerts.update { cur ->
            if (cur.isNotEmpty()) cur else alerts.take(MAX_ALERTS)
        }
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
        val now = System.currentTimeMillis()
        val markets = snapshot.allMarkets.filter { CryptoMarkets.isLiveTicker(it.ticker) }
        val series = settings.watchedSeries.ifEmpty { CryptoMarkets.DEFAULT_SERIES }
        val active = com.dirk.kalshiodds.domain.ActiveMarketResolver.tickers(markets, series, now)
        val extras = settings.extraTickerList().filter { ticker ->
            markets.any {
                it.ticker.equals(ticker, ignoreCase = true) &&
                    com.dirk.kalshiodds.domain.MarketLifecycle.isTradable(it, now)
            }
        }
        setWatchTickers(active + extras)
        for (m in markets) {
            scoring.rememberMeta(m.ticker, m.closeTimeEpochMs, m.volume, m.openInterest, m.floorStrike)
        }
        if (wsLive && _status.value.state == WsConnectionState.CONNECTED) return
        tickScope.launch {
            for (m in markets) {
                if (m.ticker !in active && m.ticker !in extras) continue
                if (!settings.isWatchedTicker(m.ticker)) continue
                runCatching { processTick(MarketTick.fromUi(m, recv, TickSource.REST), notify = true) }
            }
        }
    }

    /** Rolling 30 s taker flow per ticker, fed by every public trade. */
    val flowWindow = com.dirk.kalshiodds.signal.flowfade.FlowWindow()

    /** Resting paper orders for the flow-fade tracker. Paper only. */
    val flowMaker = com.dirk.kalshiodds.signal.flowfade.MakerPaperBook()

    /** Resting paper bids for the 1¢-better tracker. Paper only. */
    val centMaker = com.dirk.kalshiodds.signal.flowfade.MakerPaperBook(
        cancelMs = com.dirk.kalshiodds.signal.centbetter.CentBetterRule.CANCEL_MS
    )

    fun ingestTick(tick: MarketTick) {
        // Every trade counts toward the flow window; the mailbox below is latest-wins.
        val fadeLedger = flowFade
        if (tick.source == TickSource.WS_TRADE && fadeLedger != null) {
            runCatching {
                val now = System.currentTimeMillis()
                // A print can fill the resting paper order posted before it; then it joins the flow window.
                flowMaker.onTrade(tick.ticker, tick.takerSide, tick.lastPrice, tick.tradeSize, now)?.let { order ->
                    if (!fadeLedger.hasEntry(order.ticker)) {
                        com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.filled(order, now)?.let { fadeLedger.record(it) }
                    }
                }
                flowWindow.onTrade(tick.ticker, tick.takerSide, tick.tradeSize, now)
            }
        }
        runCatching {
            val limits = paperLimits?.invoke()
            if (limits != null) {
                val now = System.currentTimeMillis()
                // Expiry and the book's bound first, then the print.
                limits.onClock(tick.ticker, now)
                if (tick.source == TickSource.WS_TRADE) {
                    limits.onTrade(tick.ticker, tick.takerSide, tick.lastPrice, tick.tradeSize, now)
                }
            }
        }.onFailure { CrashBreadcrumb.record("paper limit ${tick.ticker}", it) }
        val paperScalper = scalper
        if (paperScalper != null && settings.scalperEnabled) {
            runCatching {
                val now = System.currentTimeMillis()
                if (tick.source == TickSource.WS_TRADE) {
                    paperScalper.onTrade(tick.ticker, tick.takerSide, tick.lastPrice, tick.tradeSize, now)
                } else {
                    // Quotes moving with no trade still stop out and time out open scalps.
                    paperScalper.onClock(tick.ticker, now)
                }
            }.onFailure { CrashBreadcrumb.record("scalper ${tick.ticker}", it) }
        }
        val centLedger = centBetter
        if (tick.source == TickSource.WS_TRADE && centLedger != null) {
            runCatching {
                val now = System.currentTimeMillis()
                centMaker.onTrade(tick.ticker, tick.takerSide, tick.lastPrice, tick.tradeSize, now)?.let { order ->
                    if (!centLedger.hasEntry(order.ticker)) {
                        com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.filled(order, now)?.let { centLedger.record(it) }
                    }
                }
            }
        }
        if (tickMailbox.offer(tick.ticker, tick)) {
            tickScope.launch { drainTicks() }
        }
    }

    fun ingestBookSnapshot(
        ticker: String,
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int?,
        receiveElapsedNanos: Long
    ) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        if (!settings.isWatchedTicker(ticker)) return
        runCatching { scoring.applySnapshot(ticker, yesLevels, noLevels, seq) }
        requestBookScore(ticker, receiveElapsedNanos)
    }

    fun ingestBookDelta(
        ticker: String,
        price: Double,
        delta: Double,
        side: String,
        seq: Int?,
        receiveElapsedNanos: Long
    ) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        if (!settings.isWatchedTicker(ticker)) return
        // Apply on the WS thread under TickBook's lock — cheap. Scoring is
        // latest-wins so a 50 Hz delta flood cannot enqueue 50 coroutines.
        runCatching { scoring.applyDelta(ticker, price, delta, side, seq) }
        requestBookScore(ticker, receiveElapsedNanos)
    }

    private fun requestBookScore(ticker: String, receiveElapsedNanos: Long) {
        if (bookMailbox.offer(ticker, receiveElapsedNanos)) {
            tickScope.launch { drainBooks() }
        }
    }

    /**
     * A streamed spot print for [asset] ("BTC" / "ETH" / "SOL") from
     * [com.dirk.kalshiodds.signal.external.CoinbaseSpotStream]. Spot vs strike
     * decides these contracts and Kalshi quotes lag it by seconds, so re-score
     * that coin's watched markets now instead of waiting for a Kalshi tick.
     * Called on the OkHttp reader thread: gate + mailbox only, no scoring here.
     */
    fun ingestSpot(asset: String, price: Double, receiveElapsedNanos: Long) {
        val now = System.currentTimeMillis()
        for (ticker in SpotRescoreGate.tickersFor(asset, _watchTickers.value)) {
            if (!settings.isWatchedTicker(ticker)) continue
            val due = SpotRescoreGate.shouldRescore(ticker, price, now, lastSpotRescoreMs) {
                scoring.book.lastSpot(ticker)
            }
            if (due && spotMailbox.offer(ticker, receiveElapsedNanos)) {
                tickScope.launch { drainSpot() }
            }
        }
    }

    private suspend fun drainTicks() {
        try {
            while (true) {
                val batch = tickMailbox.drain()
                if (batch.isEmpty()) break
                for ((_, tick) in batch) {
                    runCatching { processTick(tick, notify = true) }
                }
            }
        } finally {
            if (tickMailbox.markIdleAndNeedsRerun()) {
                tickScope.launch { drainTicks() }
            }
        }
    }

    private suspend fun drainBooks() {
        try {
            while (true) {
                val batch = bookMailbox.drain()
                if (batch.isEmpty()) break
                for ((ticker, recv) in batch) {
                    runCatching {
                        publishBookScore(ticker, recv)
                        scoring.maybeAlertFromBook(ticker, settings, recv)?.let { emitAlert(it) }
                    }
                }
            }
        } finally {
            if (bookMailbox.markIdleAndNeedsRerun()) {
                tickScope.launch { drainBooks() }
            }
        }
    }

    private suspend fun drainSpot() {
        try {
            while (true) {
                val batch = spotMailbox.drain()
                if (batch.isEmpty()) break
                for ((ticker, recv) in batch) {
                    runCatching { rescoreOnSpot(ticker, recv) }
                }
            }
        } finally {
            if (spotMailbox.markIdleAndNeedsRerun()) {
                tickScope.launch { drainSpot() }
            }
        }
    }

    /**
     * Re-score [ticker] on its current Kalshi quote with the new spot. The
     * tick is book-sourced ([TickSource.WS_ORDERBOOK]) so it refreshes fair,
     * the score overlay and alerts without adding a velocity / flow point:
     * spot moved, the Kalshi mid did not.
     */
    private suspend fun rescoreOnSpot(ticker: String, receiveElapsedNanos: Long) {
        val tick = scoring.book.tickFromBook(ticker, receiveElapsedNanos)
            ?: scoring.book.lastTick(ticker)?.copy(
                source = TickSource.WS_ORDERBOOK,
                receiveElapsedNanos = receiveElapsedNanos
            )
            ?: return
        processTick(tick, notify = true)
    }

    private suspend fun processTick(tick: MarketTick, notify: Boolean) {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return
        if (!settings.isWatchedTicker(tick.ticker)) return
        val t0 = tick.receiveElapsedNanos
        val scored = runCatching { scoring.score(tick, settings) }.getOrElse { t ->
            CrashBreadcrumb.record("processTick ${tick.ticker}", t)
            null
        }
        if (scored != null) {
            _scores.update { it + (tick.ticker to scored) }
            persistScore(tick, scored)
            persistOddsMid(tick.ticker, scored.marketMidPp)
            maybeLateFavorite(tick, scored)
            maybeFlowFade(tick, scored)
            maybeCentBetter(tick, scored)
        }
        persistChartTick(tick)
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
        val now = System.currentTimeMillis()
        if (!BookScoreGate.shouldPublish(ticker, now, lastBookPublishMs)) return
        val tick = scoring.book.tickFromBook(ticker, receiveElapsedNanos) ?: return
        val scored = runCatching { scoring.score(tick, settings) }.getOrNull() ?: return
        _scores.update { it + (ticker to scored) }
        persistScore(tick, scored)
        persistOddsMid(ticker, scored.marketMidPp)
        persistChartTick(tick)
        maybeLateFavorite(tick, scored)
        maybeFlowFade(tick, scored)
        maybeCentBetter(tick, scored)
        maybeClearLead(tick, scored)
    }

    /**
     * Paper record of the clear-lead rule
     * ([com.dirk.kalshiodds.signal.trend.ClearLeadRule]): the first moment in
     * a window the engine's score says Bitcoin is clearly ahead, log a $5 buy
     * of that side at the ask. Logs to [clearLead]; there is no path from
     * here to an order.
     */
    private fun maybeClearLead(tick: MarketTick, scored: ScoringEngine.Score) {
        val ledger = clearLead ?: return
        if (!settings.clearLeadEnabled) return
        val side = scored.clearLeadSide ?: return
        val bp = scored.clearLeadBp ?: return
        val tte = scored.tteSeconds ?: return
        if (!CryptoMarkets.isLiveTicker(tick.ticker) || ledger.hasEntry(tick.ticker)) return
        runCatching {
            // Asks come from the book when it has them: a trade tick carries only the print price.
            val top = scoring.book.topOfBook(tick.ticker)
            val quoted = tick.source != TickSource.WS_TRADE
            val yesBid = top?.yesBid ?: tick.yesBid.takeIf { quoted }
            val noBid = top?.noBid ?: tick.noBid.takeIf { quoted }
            val yesAsk = KalshiPrice.usable(top?.yesAsk ?: tick.yesAsk.takeIf { quoted })
                ?: KalshiPrice.impliedAskFromOppositeBid(noBid)
            val noAsk = KalshiPrice.usable(top?.noAsk ?: tick.noAsk.takeIf { quoted })
                ?: KalshiPrice.impliedAskFromOppositeBid(yesBid)
            val decision = com.dirk.kalshiodds.signal.trend.ClearLeadRule.decide(
                ticker = tick.ticker,
                nowMs = System.currentTimeMillis(),
                signal = com.dirk.kalshiodds.signal.trend.ClearLeadRule.Signal(side, bp, tte),
                yesAsk = yesAsk,
                noAsk = noAsk
            ) ?: return
            ledger.record(decision)?.let {
                Log.d(TAG, "clearlead paper ${it.ticker} ${it.side} @ ${it.ask} bp=${it.z} tte=${it.tteSeconds}")
            }
        }.onFailure { CrashBreadcrumb.record("clearlead ${tick.ticker}", it) }
    }

    /**
     * Paper-only flow-fade tracker: posts a resting paper bid on the side
     * takers are not buying ([flowMaker]); [ingestTick] records it in
     * [flowFade] when later prints fill it. Cheap exits first: no ledger,
     * outside the time band, already filled this market, or an order is
     * still resting. There is no path from here to a real order.
     */
    /**
     * Paper-only 1¢-better tracker: rests a paper bid 1¢ above the best bid
     * on the side the digital fair favors ([centMaker]); [ingestTick] records
     * it in [centBetter] when a later print fills it. No path to a real order.
     */
    private fun maybeCentBetter(tick: MarketTick, scored: ScoringEngine.Score) {
        val ledger = centBetter ?: return
        val tte = scored.tteSeconds ?: return
        if (tte < com.dirk.kalshiodds.signal.centbetter.CentBetterRule.MIN_TTE_SECONDS ||
            tte > com.dirk.kalshiodds.signal.centbetter.CentBetterRule.MAX_TTE_SECONDS
        ) {
            return
        }
        if (!CryptoMarkets.isLiveTicker(tick.ticker) || ledger.hasEntry(tick.ticker)) return
        val fairPp = scored.digitalFairPp ?: return
        runCatching {
            val now = System.currentTimeMillis()
            centMaker.expire(tick.ticker, now)
            if (centMaker.pending(tick.ticker) != null) return
            val top = scoring.book.topOfBook(tick.ticker)
            val quoted = tick.source != TickSource.WS_TRADE
            val order = com.dirk.kalshiodds.signal.centbetter.CentBetterRule.order(
                com.dirk.kalshiodds.signal.centbetter.CentBetterRule.Inputs(
                    ticker = tick.ticker,
                    nowMs = now,
                    tteSeconds = tte,
                    fairYes = fairPp / 100.0,
                    yesBid = top?.yesBid ?: tick.yesBid.takeIf { quoted },
                    yesAsk = top?.yesAsk ?: tick.yesAsk.takeIf { quoted },
                    noBid = top?.noBid ?: tick.noBid.takeIf { quoted },
                    noAsk = top?.noAsk ?: tick.noAsk.takeIf { quoted }
                ),
                alreadyEntered = false
            ) ?: return
            if (centMaker.post(order)) {
                Log.d(TAG, "centbetter resting ${order.ticker} ${order.side} @ ${order.price} edge=${order.imbalance}")
            }
        }.onFailure { CrashBreadcrumb.record("centbetter ${tick.ticker}", it) }
    }

    private fun maybeFlowFade(tick: MarketTick, scored: ScoringEngine.Score) {
        val ledger = flowFade ?: return
        val tte = scored.tteSeconds ?: return
        if (tte < com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.MIN_TTE_SECONDS ||
            tte > com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.MAX_TTE_SECONDS
        ) {
            return
        }
        if (!CryptoMarkets.isLiveTicker(tick.ticker) || ledger.hasEntry(tick.ticker)) return
        runCatching {
            val now = System.currentTimeMillis()
            flowMaker.expire(tick.ticker, now)
            if (flowMaker.pending(tick.ticker) != null) return
            val (takerYes, takerNo) = flowWindow.sums(tick.ticker, now)
            // Bids come from the book when it has them: a trade tick carries only the print price.
            val top = scoring.book.topOfBook(tick.ticker)
            val quoted = tick.source != TickSource.WS_TRADE
            val yesBid = top?.yesBid ?: tick.yesBid.takeIf { quoted }
            val noBid = top?.noBid ?: tick.noBid
                ?: KalshiPrice.usable(top?.yesAsk ?: tick.yesAsk.takeIf { quoted })?.let { 1.0 - it }
            val order = com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.order(
                com.dirk.kalshiodds.signal.flowfade.FlowFadeRule.MakerInputs(
                    ticker = tick.ticker,
                    nowMs = now,
                    tteSeconds = tte,
                    takerYes = takerYes,
                    takerNo = takerNo,
                    yesBid = yesBid,
                    noBid = noBid,
                    yesBidQty = top?.yesBidQty,
                    noBidQty = top?.noBidQty
                ),
                alreadyEntered = false
            ) ?: return
            if (flowMaker.post(order)) {
                Log.d(TAG, "flowfade resting ${order.ticker} ${order.side} @ ${order.price} queue=${order.queueAhead} imb=${order.imbalance}")
            }
        }.onFailure { CrashBreadcrumb.record("flowfade ${tick.ticker}", it) }
    }

    /**
     * Paper-only late-favorite tracker (tools/research/edge_search.py H2).
     * Cheap exits first: off, > 5 min left, or already entered this market.
     * Logs to [lateFavorite]; there is no path from here to an order.
     */
    private fun maybeLateFavorite(tick: MarketTick, scored: ScoringEngine.Score) {
        val ledger = lateFavorite ?: return
        if (!settings.lateFavoriteEnabled) return
        val tte = scored.tteSeconds ?: return
        if (tte <= 0L || tte > com.dirk.kalshiodds.signal.latefav.LateFavoriteRule.MAX_TTE_SECONDS) return
        if (!CryptoMarkets.isLiveTicker(tick.ticker) || ledger.hasEntry(tick.ticker)) return
        runCatching {
            val now = System.currentTimeMillis()
            val spot = com.dirk.kalshiodds.signal.external.SpotMerge.forSeries(
                scoring.external,
                scoring.spotStream,
                tick.series,
                now
            )
            val decision = com.dirk.kalshiodds.signal.latefav.LateFavoriteRule.evaluate(
                com.dirk.kalshiodds.signal.latefav.LateFavoriteRule.Inputs(
                    ticker = tick.ticker,
                    nowMs = now,
                    tteSeconds = tte,
                    spotUsd = spot?.lastPrice,
                    strikeUsd = tick.floorStrike ?: scoring.book.strike(tick.ticker),
                    sigma1m = spot?.realizedVol15m,
                    yesAsk = KalshiPrice.usable(tick.yesAsk) ?: KalshiPrice.impliedAskFromOppositeBid(tick.noBid),
                    noAsk = KalshiPrice.usable(tick.noAsk) ?: KalshiPrice.impliedAskFromOppositeBid(tick.yesBid)
                ),
                alreadyEntered = false
            ) ?: return
            ledger.record(decision)?.let {
                Log.d(TAG, "latefav paper ${it.ticker} ${it.side} @ ${it.ask} z=${it.z} tte=${it.tteSeconds}")
            }
        }.onFailure { CrashBreadcrumb.record("latefav ${tick.ticker}", it) }
    }

    private fun persistOddsMid(ticker: String, marketMidPp: Double) {
        val mid01 = marketMidPp / 100.0
        if (!mid01.isFinite() || mid01 <= 0.0 || mid01 >= 1.0) return
        val now = System.currentTimeMillis()
        val lastTs = lastOddsPersistMs[ticker] ?: 0L
        val lastMid = lastOddsMid[ticker]
        if (now - lastTs < SignalConstants.ODDS_MID_PERSIST_MIN_MS &&
            lastMid != null && kotlin.math.abs(lastMid - mid01) < 0.001
        ) {
            return
        }
        lastOddsPersistMs[ticker] = now
        lastOddsMid[ticker] = mid01
        runCatching {
            val last = scoring.book.lastTick(ticker)
            results?.enqueueOddsMid(
                OddsMidRow(
                    ticker = ticker,
                    mid01 = mid01,
                    createdAtMs = now,
                    yesBid = last?.yesBid,
                    noBid = last?.noBid ?: last?.yesAsk?.let { (1.0 - it).coerceIn(0.0, 1.0) }
                )
            )
        }
    }

    private fun persistChartTick(tick: MarketTick) {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return
        val now = System.currentTimeMillis()
        val lastTs = lastChartPersistMs[tick.ticker] ?: 0L
        if (now - lastTs < SignalConstants.CHART_TICK_PERSIST_MIN_MS) return
        lastChartPersistMs[tick.ticker] = now
        runCatching {
            results?.enqueueChartTick(
                ChartTickRow(
                    ticker = tick.ticker,
                    tMs = now,
                    yesBid = tick.yesBid,
                    noBid = tick.noBid ?: tick.yesAsk?.let { (1.0 - it).coerceIn(0.0, 1.0) },
                    yesAsk = tick.yesAsk,
                    noAsk = tick.noAsk,
                    spotUsd = scoring.book.lastSpot(tick.ticker),
                    source = if (tick.source == TickSource.REST) ChartTickRow.SOURCE_REST else ChartTickRow.SOURCE_LIVE
                )
            )
        }
    }

    private fun persistScore(tick: MarketTick, scored: ScoringEngine.Score) {
        val now = System.currentTimeMillis()
        runCatching {
            results?.enqueueSnapshot(
                ScoredSnapshotRow(
                    ticker = tick.ticker,
                    series = tick.series,
                    side = scored.predictedSide,
                    edgePp = scored.deltaPp,
                    fairPp = scored.fairValuePp,
                    marketPp = scored.marketMidPp,
                    regime = scored.regime.name,
                    uncertainty = scored.uncertainty,
                    createdAtMs = now,
                    confidence = scored.confidence,
                    tte = scored.tteRegime.name,
                    heavyMl = scored.heavyMl,
                    note = scored.ensembleNote
                )
            )
        }
        // SQLite/text already have the row. Skip the DataStore JSON rewrite
        // when heap is tight or another write is in flight — that rewrite is
        // what filled the 256MB heap on SM-S928U (CancellableContinuationImpl).
        if (com.dirk.kalshiodds.signal.ml.HeapGuard.isTight()) return
        val store = logStore ?: return
        if (!logWriteBusy.compareAndSet(false, true)) return
        persistScope.launch {
            try {
                runCatching {
                    val predictedSide = SignalStance.resolve(
                        storedSide = scored.predictedSide,
                        modelYes = scored.importedModelPp ?: scored.aiPp ?: scored.fairValuePp,
                        marketYes = scored.marketMidPp,
                        fairYes = scored.fairValuePp
                    ).storedSide
                    val sideYes = when (predictedSide?.trim()?.uppercase()) {
                        "YES" -> true
                        "NO" -> false
                        else -> (scored.importedModelPp ?: scored.aiPp ?: scored.fairValuePp) > 50.0
                    }
                    val sized = com.dirk.kalshiodds.signal.feedback.ScorecardLedger.captureEntryFromBook(
                        sideYes = sideYes,
                        yesAsk = tick.yesAsk,
                        noAsk = tick.noAsk,
                        yesBid = tick.yesBid
                    )
                    store.upsertOpenPrediction(
                        ticker = tick.ticker,
                        series = tick.series,
                        predictedYes = scored.fairValuePp / 100.0,
                        predictedNo = 1.0 - scored.fairValuePp / 100.0,
                        marketMid = scored.marketMidPp / 100.0,
                        timestampMs = System.currentTimeMillis(),
                        closeTimeMs = tick.closeTimeEpochMs ?: scoring.book.closeTime(tick.ticker),
                        snapshot = SignalSnapshot(
                            predictedSide = predictedSide,
                            edgePp = scored.deltaPp,
                            confidence = scored.confidence,
                            regime = scored.regime.name,
                            tteBucket = scored.tteRegime.name,
                            fairValuePp = scored.fairValuePp,
                            calibrated = scored.calibrated,
                            featureDevs = scored.featureDevs,
                            uncertainty = scored.uncertainty,
                            timeToMoveSec = scored.timeToMoveSec,
                            midVolPp = scored.midVolPp,
                            pFill = scored.pFill,
                            wouldAlert = scored.passedFilter &&
                                kotlin.math.abs(scored.deltaPp) >= settings.effectiveEdgeThresholdPp(),
                            mlpYes = scored.mlpPp?.div(100.0),
                            cnnYes = scored.cnnPp?.div(100.0),
                            gbmYes = scored.gbmPp?.div(100.0),
                            entryAsk = sized.entryAsk,
                            contracts = sized.contracts,
                            stakeUsd = sized.stakeUsd,
                            feeUsd = sized.feeUsd
                        )
                    )
                }
            } finally {
                logWriteBusy.set(false)
            }
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
        runCatching {
            results?.enqueueAlert(
                AlertRow(
                    alertId = complete.id,
                    ticker = complete.ticker,
                    series = complete.series,
                    side = complete.predictedSide,
                    edgePp = complete.deltaPp,
                    fairPp = complete.fairValuePp,
                    marketPp = complete.marketMidPp,
                    reason = complete.reason,
                    regime = complete.regime,
                    createdAtMs = complete.createdAtMs
                )
            )
        }
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
