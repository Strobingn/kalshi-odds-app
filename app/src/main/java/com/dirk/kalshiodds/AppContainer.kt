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
import com.dirk.kalshiodds.signal.external.CoinbaseSpotStream
import com.dirk.kalshiodds.signal.external.ExternalMarketCache
import com.dirk.kalshiodds.signal.external.SpotStreamBook
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
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val extraSecrets = com.dirk.kalshiodds.signal.config.SecureExtraStore(app)
    val preferences = SignalPreferences(app, extras = extraSecrets)
    val model = DipHunterModel(app)
    val logStore = PredictionLogStore(app)
    val ledger: com.dirk.kalshiodds.prediction.ledger.LedgerStore? =
        runCatching { com.dirk.kalshiodds.prediction.ledger.LedgerStore(app) }.getOrNull()
    private val ledgerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** App build + edge model, stamped on each ledger row. */
    fun ledgerModelVersion(): String {
        val edge = runCatching { importedModel.currentManifest()?.version }.getOrNull() ?: "built-in"
        return "app ${BuildConfig.VERSION_NAME}#${BuildConfig.CI_RUN_NUMBER} · edge $edge"
    }

    /** Copies settled yes/no calls from the prediction log into the permanent ledger. */
    fun syncLedger(ticker: String? = null) {
        val store = ledger ?: return
        ledgerScope.launch {
            runCatching {
                val version = ledgerModelVersion()
                val rows = logStore.readAll()
                    .filter { ticker == null || it.ticker == ticker }
                    .mapNotNull { com.dirk.kalshiodds.prediction.ledger.LedgerRow.from(it, version) }
                    // One row per window: the last call logged for it.
                    .groupBy { it.ticker }
                    .map { (_, rs) -> rs.maxBy { it.calledAtMs } }
                store.upsert(rows)
            }
        }
    }
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
    val scoring = ScoringEngine(
        model = model,
        heavy = HeavyMlRuntime().also { HeavyMlAssets.apply(app, it) },
        extended = ExtendedAiRuntime()
    ).also { it.edgeModel = importedModel.current() }
    val support = DecisionSupport(
        logStore = logStore,
        adapterStore = adapterStore,
        guardrailStore = guardrailStore,
        scoring = scoring,
        heavyStore = heavyStore,
        results = resultsWriter
    )
    val external = ExternalMarketCache()
    /** Paper-only late-favorite tracker (edge_search H2). Never orders. */
    val lateFavorite = com.dirk.kalshiodds.signal.latefav.LateFavoriteStore(app)
    /** Paper-only flow-fade tracker (docs/flow-fade-2026-10-04.md). Never orders. */
    val flowFade = com.dirk.kalshiodds.signal.flowfade.FlowFadeStore(app)
    /** Paper-only 1¢-better resting bid (maker_sim improve rule). Never orders. */
    val centBetter = com.dirk.kalshiodds.signal.centbetter.CentBetterStore(app)
    /** Paper record of the clear-lead rule (Dirk's "bet the way Bitcoin is going"). Never orders. */
    val clearLead = com.dirk.kalshiodds.signal.trend.ClearLeadStore(app)
    /** Paper scalper record and model (docs/scalping-2026-10-09.md). Never orders. */
    val scalperStore = com.dirk.kalshiodds.signal.scalper.ScalperStore(app)
    val scalper = com.dirk.kalshiodds.signal.scalper.PaperScalper(
        model = scalperStore.model,
        ledger = scalperStore.ledger,
        topOfBook = { scoring.book.topOfBook(it) },
        bookLevels = { scoring.book.snapshotBook(it) },
        closeMs = { t ->
            scoring.book.closeTime(t) ?: com.dirk.kalshiodds.signal.trade.TakerCost.closeEpochMs(t)
        },
        onReset = { scalperStore.log.archive() }
    )
    val hub = SignalHub(
        scoring = scoring,
        notifier = notifier,
        logStore = logStore,
        results = resultsWriter,
        lateFavorite = lateFavorite.ledger,
        flowFade = flowFade.ledger,
        centBetter = centBetter.ledger,
        clearLead = clearLead.ledger,
        scalper = scalper
    )

    /**
     * Streamed Coinbase spot (docs/ml-review-2026-09-27.md #6). Scoring reads
     * [spotBook] while it is fresh and falls back to [external] (REST); each
     * print can re-score that coin via [SignalHub.ingestSpot]. Runs while the
     * app is visible or Live signals is on, and the Settings toggle allows it.
     */
    val spotBook = SpotStreamBook().also { scoring.spotStream = it }
    /** Kalshi's CF Benchmarks settlement index (Live signals WebSocket). */
    val cfIndex = com.dirk.kalshiodds.signal.external.CfIndexBook().also { scoring.cfIndex = it }

    /**
     * Research recorder (spot / top of book / trades / settlements as daily
     * gzip CSV under filesDir/recordings). Started by LiveSignalsService.
     */
    val recorder = com.dirk.kalshiodds.data.local.recording.MarketDataRecorder(
        files = com.dirk.kalshiodds.data.local.recording.RecordingFiles(
            File(app.filesDir, com.dirk.kalshiodds.data.local.recording.MarketDataRecorder.DIR_NAME)
        ),
        topOf = { ticker -> scoring.book.topOfBook(ticker) },
        metaOf = { ticker ->
            val last = scoring.book.lastTick(ticker)
            (scoring.book.strike(ticker) ?: last?.floorStrike) to
                (scoring.book.closeTime(ticker) ?: last?.closeTimeEpochMs)
        },
        watchedTickers = { hub.watchTickers.value }
    )
    val spotStream = CoinbaseSpotStream(
        book = spotBook,
        onPrint = { asset, price, recvNanos ->
            recorder.onSpot("$asset-USD", price)
            hub.ingestSpot(asset, price, recvNanos)
        }
    )
    val lastOrderError = com.dirk.kalshiodds.signal.trade.LastOrderErrorStore(app)
    val paper = PaperBookStore(app)
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
        extraOpenTickers = {
            paper.book.openTickers() + lateFavorite.ledger.openTickers() + flowFade.ledger.openTickers() +
                centBetter.ledger.openTickers() + clearLead.ledger.openTickers() + scalper.openTickers() +
                recorder.pendingSettlementTickers()
        },
        onMarketSettled = { ticker, result ->
            syncLedger(ticker)
            paper.book.settle(ticker, result)
            lateFavorite.ledger.settle(ticker, result)
            flowFade.ledger.settle(ticker, result)
            centBetter.ledger.settle(ticker, result)
            clearLead.ledger.settle(ticker, result)
            scalper.settle(ticker, result)
            recorder.onSettled(ticker, result)
        },
        onCalibration = { hub.applyCalibration(it) },
        onAfterScore = {
            support.refreshFromSettlements(hub.settings)
            paper.book.settleFromLog(logStore.readAll())
            lateFavorite.ledger.settleFromLog(logStore.readAll())
            flowFade.ledger.settleFromLog(logStore.readAll())
            centBetter.ledger.settleFromLog(logStore.readAll())
            clearLead.ledger.settleFromLog(logStore.readAll())
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
        // Backfill: settled calls already in the 400-entry prediction log.
        syncLedger()
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
