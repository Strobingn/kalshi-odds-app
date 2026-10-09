package com.dirk.kalshiodds

import android.content.Context
import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.data.local.results.OomFlagStore
import com.dirk.kalshiodds.data.local.results.ResultsStore
import com.dirk.kalshiodds.data.local.results.RollingTextLog
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.data.repo.MarketRepository
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.signal.SignalHub
import com.dirk.kalshiodds.signal.config.SignalPreferences
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.external.ExternalMarketCache
import com.dirk.kalshiodds.signal.feedback.DecisionSupport
import com.dirk.kalshiodds.signal.feedback.GuardrailStore
import com.dirk.kalshiodds.signal.feedback.LearnedWeightsStore
import com.dirk.kalshiodds.signal.ml.ExtendedAiRuntime
import com.dirk.kalshiodds.signal.ml.HeavyMlAssets
import com.dirk.kalshiodds.signal.ml.HeavyMlRuntime
import com.dirk.kalshiodds.signal.ml.HeavyMlStore
import com.dirk.kalshiodds.signal.ml.NewsPulseCache
import com.dirk.kalshiodds.signal.notify.SignalNotifier
import com.dirk.kalshiodds.signal.paper.PaperBookStore
import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.market.MarketRollover
import com.dirk.kalshiodds.signal.trade.TicketSession
import java.io.File

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val extraSecrets = com.dirk.kalshiodds.signal.config.SecureExtraStore(app)
    val preferences = SignalPreferences(app, extras = extraSecrets)
    val model = DipHunterModel(app)
    val logStore = PredictionLogStore(app)
    val adapterStore = LearnedWeightsStore(app)
    val guardrailStore = GuardrailStore(app)
    val heavyStore = HeavyMlStore(app)
    val notifier = SignalNotifier(app)
    val opportunities = com.dirk.kalshiodds.signal.notify.OpportunityNotifier(app)
    val newsCache = NewsPulseCache()
    val oomFlag = OomFlagStore(app)
    private val resultsImpl = runCatching { SqliteResultsStore(app) as ResultsStore }
        .getOrElse { com.dirk.kalshiodds.data.local.results.InMemoryResultsStore() }
    val resultsStore: ResultsStore = resultsImpl
    val archive: com.dirk.kalshiodds.data.local.archive.DataArchive =
        resultsImpl as com.dirk.kalshiodds.data.local.archive.DataArchive
    val sessionId: String = java.util.UUID.randomUUID().toString()
    val dataPrefs = com.dirk.kalshiodds.data.prefs.DataPrefs(app)
    val importedModel = com.dirk.kalshiodds.prediction.ImportedModelStore(app)
    val resultsLog = RollingTextLog(File(app.filesDir, "results.log"))
    val resultsWriter = AsyncResultsWriter(resultsStore, resultsLog)
    val spotStream = com.dirk.kalshiodds.signal.external.SpotStream()
    val scoring = ScoringEngine(
        model = model,
        heavy = HeavyMlRuntime().also { HeavyMlAssets.apply(app, it) },
        extended = ExtendedAiRuntime()
    ).also {
        it.edgeModel = importedModel.currentOrBundled()
        it.spotStream = spotStream
    }
    val support = DecisionSupport(
        logStore = logStore,
        adapterStore = adapterStore,
        guardrailStore = guardrailStore,
        scoring = scoring,
        heavyStore = heavyStore,
        results = resultsWriter
    )
    val external = ExternalMarketCache(stream = spotStream)
    val hub = SignalHub(
        scoring = scoring,
        notifier = notifier,
        logStore = logStore,
        results = resultsWriter
    )
    val lastOrderError = com.dirk.kalshiodds.signal.trade.LastOrderErrorStore(app)

    // ---- Experimental scalper (the ONE non-approve-gated path) -------------
    // Lifecycle is bound to LiveSignalsService: the service calls
    // scalpEngine.start()/stop(); until start() runs the engine's `running`
    // flag is false and onTick is a no-op beyond feature accumulation.
    // Settings gate: enabled (default false) && !killSwitch; executor choice
    // comes from settings.liveMode (default false = paper). The $10 live cap
    // is re-enforced inside LiveScalpExecutor via LiveOrderSizer.
    val scalpSettingsStore = com.dirk.kalshiodds.signal.scalp.ScalpSettingsStore(app)
    val scalpLedger = com.dirk.kalshiodds.signal.scalp.ScalpPositionStore(app)
    private val scalpScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    /** Most recent ticker the hub scored — the scalper's book lookup key. */
    @Volatile
    private var scalpBookTicker: String? = null

    private fun scalpBook(): com.dirk.kalshiodds.signal.engine.LocalOrderBook? =
        scalpBookTicker?.let { scoring.book.orderBook(it) }

    private fun onScalpEvent(event: com.dirk.kalshiodds.signal.scalp.ScalpEvent) {
        // Guardrail-blocked events never become notifications (anti-spam).
        if (event is com.dirk.kalshiodds.signal.scalp.ScalpEvent.GuardrailBlocked) return
        if (!hub.settings.notificationsEnabled) return
        when (event) {
            is com.dirk.kalshiodds.signal.scalp.ScalpEvent.Entered -> {
                val p = event.position
                notifier.notifyScalp(
                    title = "Scalp: entered ${p.ticker}",
                    text = "Bought ${p.contracts} YES @ ${p.entryPriceCents}¢ " +
                        "(${if (p.mode == com.dirk.kalshiodds.signal.scalp.ScalpMode.PAPER) "paper" else "LIVE"})",
                    ticker = p.ticker
                )
            }
            is com.dirk.kalshiodds.signal.scalp.ScalpEvent.Exited -> {
                val p = event.position
                val pnl = event.pnlCents / 100.0
                notifier.notifyScalp(
                    title = "Scalp: exited ${p.ticker} ${if (pnl >= 0) "+" else "−"}$${"%.2f".format(pnl).removePrefix("-")}",
                    text = "Sold ${p.contracts} YES @ ${p.exitPriceCents ?: "?"}¢ — ${event.reason.name.lowercase().replace('_', ' ')}",
                    ticker = p.ticker
                )
            }
            is com.dirk.kalshiodds.signal.scalp.ScalpEvent.Error ->
                notifier.notifyScalp(title = "Scalp: error", text = event.message)
            else -> Unit
        }
    }

    val paperScalpExecutor = com.dirk.kalshiodds.signal.scalp.PaperScalpExecutor(
        bookProvider = { scalpBook() }
    )

    val paper = PaperBookStore(app) { fills ->
        runCatching { archive.upsertPaperFills(fills) }
    }
    val lastMinuteStore = com.dirk.kalshiodds.signal.lastminute.LastMinuteStore(app)
    val lastMinuteEngine = com.dirk.kalshiodds.signal.lastminute.LastMinuteEngine(nowMs = { clock.nowMs() })
    val brti = com.dirk.kalshiodds.signal.lastminute.BrtiCompositeClient()
    val lastMinuteNotifier = com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifier(app)
    val tradeClient = KalshiTradeClient(
        primary = NetworkModule.tradeApi(
            { tradingCredentials() },
            com.dirk.kalshiodds.data.api.KalshiApi.TRADE_BASE_URL
        ),
        fallback = NetworkModule.tradeApi(
            { tradingCredentials() },
            com.dirk.kalshiodds.data.api.KalshiApi.BASE_URL
        ),
        demoPrimary = NetworkModule.tradeApi(
            { tradingCredentials() },
            com.dirk.kalshiodds.data.api.KalshiApi.DEMO_TRADE_BASE_URL
        ),
        demoFallback = NetworkModule.tradeApi(
            { tradingCredentials() },
            com.dirk.kalshiodds.data.api.KalshiApi.DEMO_SHARED_BASE_URL
        ),
        credentials = { tradingCredentials() },
        useDemo = { hub.settings.kalshiDemoEnabled }
    )
    val liveScalpExecutor = com.dirk.kalshiodds.signal.scalp.LiveScalpExecutor(
        tradeClient = tradeClient,
        onPlaced = { position, placed ->
            scalpLedger.attachOrderIds(position.id, placed.clientOrderId, placed.orderId)
        }
    )
    val scalpEngine = com.dirk.kalshiodds.signal.scalp.ScalpEngine(
        settings = scalpSettingsStore.settings,
        bookProvider = { scalpBook() },
        paperExecutor = paperScalpExecutor,
        liveExecutor = liveScalpExecutor,
        store = scalpLedger,
        scope = scalpScope,
        onEvent = { event -> runCatching { onScalpEvent(event) } }
    )
    init {
        // Every scored tick (WS, REST, book-derived) feeds the scalper. The
        // engine itself gates on enabled && !killSwitch && running, so this
        // hook is inert unless the scalper is enabled AND the Live signals
        // service has started the engine.
        hub.scoredTickHook = { tick ->
            scalpBookTicker = tick.ticker
            scalpEngine.onTick(tick)
        }
    }
    val tickets = TicketSession(
        placeOrder = { ticket, clientOrderId ->
            runCatching { tradeClient.createLimit(ticket, clientOrderId) }
        },
        cancelOrder = { order ->
            runCatching { tradeClient.cancel(order) }
        },
        onAttempt = { row: TicketAttemptRow -> resultsWriter.enqueueTicket(row) }
    )
    val clock: Clock = Clock.System
    val repository = MarketRepository(
        context = app,
        resolveApi = {
            val keyed = hub.settings.tradingCredentialsConfigured()
            NetworkModule.marketsApi(
                demo = hub.settings.kalshiDemoEnabled,
                credentials = if (keyed) ({ tradingCredentials() }) else null
            )
        },
        model = model,
        logStore = logStore,
        extraOpenTickers = { paper.book.openTickers() },
        onMarketSettled = { ticker, result ->
            paper.book.settle(ticker, result)
            lastMinuteStore.settle(ticker, result)
        },
        onCalibration = { hub.applyCalibration(it) },
        onAfterScore = {
            support.refreshFromSettlements(hub.settings)
            paper.book.settleFromLog(logStore.readAll())
            val tuned = com.dirk.kalshiodds.signal.feedback.EdgeAutoTuner.fromEntries(
                logStore.readAll(),
                feeRate = hub.settings.feeRate
            )
            if (hub.settings.autoTuneEnabled && !hub.settings.autoTuneManualOverride) {
                runCatching { preferences.updateSitOut(tuned.sitOut) }
                runCatching { preferences.updateTunedEdgeThresholdPp(tuned.thresholdPp) }
                runCatching { preferences.updateAutoTuneNote(tuned.reason) }
                hub.settings = hub.settings.copy(
                    sitOut = tuned.sitOut,
                    tunedEdgeThresholdPp = tuned.thresholdPp,
                    autoTuneNote = tuned.reason
                )
            }
        }
    )
    val rollover = MarketRollover(
        clock = clock,
        listOpen = { series -> repository.listOpen(series) },
        watchedSeries = {
            hub.settings.watchedSeries.toList().ifEmpty { CryptoMarkets.DEFAULT_SERIES }
        }
    )

    init {
        runCatching {
            archive.insertSession(
                com.dirk.kalshiodds.data.local.history.HistorySession(
                    id = sessionId,
                    startedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun tradingCredentials(): Pair<String, String> =
        if (hub.settings.kalshiDemoEnabled) extraSecrets.demoSnapshot()
        else preferences.credentialSnapshot()

    fun endSession() {
        val paperSnap = runCatching { paper.book.snapshot() }.getOrNull()
        runCatching {
            archive.closeSession(
                id = sessionId,
                endedAtMs = System.currentTimeMillis(),
                markets = resultsStore.recentOddsMids(40).map { it.ticker }.distinct().size,
                signals = resultsStore.recentSnapshots(200).size,
                bets = resultsStore.recentTickets(200).size + (paperSnap?.fills?.size ?: 0),
                pnlUsd = paperSnap?.realizedPnlUsd
            )
        }
    }
}
