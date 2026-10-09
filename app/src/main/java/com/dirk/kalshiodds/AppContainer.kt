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
    private val resultsImpl: com.dirk.kalshiodds.data.local.results.ResultsDatabase =
        runCatching { SqliteResultsStore(app) }
            .getOrElse { com.dirk.kalshiodds.data.local.results.InMemoryResultsStore() }
    val resultsStore: ResultsStore = resultsImpl
    val cfFeed = com.dirk.kalshiodds.signal.ws.CfBenchmarkStore()
    val predictionLedger: com.dirk.kalshiodds.data.local.results.SqliteResultsStore? =
        resultsImpl as? com.dirk.kalshiodds.data.local.results.SqliteResultsStore
    val archive: com.dirk.kalshiodds.data.local.archive.DataArchive = resultsImpl
    val sessionId: String = java.util.UUID.randomUUID().toString()
    val dataPrefs = com.dirk.kalshiodds.data.prefs.DataPrefs(app)
    /** 0.3.37 deterministic decision layer: calibration + gate + prediction ledger. No LLM. */
    val decisions = com.dirk.kalshiodds.decision.DecisionRuntime(
        sink = predictionLedger,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        longshot = com.dirk.kalshiodds.decision.LongshotResidual.loadAsset(app)
    ).also { it.refitIfDue() }
    val ladder = com.dirk.kalshiodds.decision.LadderStore(app)
    /** 0.3.38 paper scalps (scalp_trades in the results DB). Never places orders. */
    val scalp = com.dirk.kalshiodds.decision.ScalpBook(
        (resultsImpl as? com.dirk.kalshiodds.decision.ScalpPersistence)
            ?: com.dirk.kalshiodds.decision.InMemoryScalpPersistence(),
        tuneStore = com.dirk.kalshiodds.decision.SharedPrefsScalpTuneStore(app),
        // 0.3.40: total open scalp cost ≤ free paper bankroll (cash not tied up in Autopilot paper bets).
        bankrollUsd = {
            runCatching { paper.book.snapshot().cashUsd }.getOrNull()
                ?: com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_START_USD
        }
    )
    /** Once-per-event trade notifications. */
    val tradeEvents = com.dirk.kalshiodds.signal.notify.TradeEventNotifier(app)
    /** 0.3.39: errors back off (30 s doubling to 10 min) and resume; they never disable Autopilot. */
    val paperBackoff = com.dirk.kalshiodds.signal.paper.AutopilotBackoff()
    val importedModel = com.dirk.kalshiodds.prediction.ImportedModelStore(app)
    val resultsLog = RollingTextLog(File(app.filesDir, "results.log"))
    val resultsWriter = AsyncResultsWriter(resultsStore, resultsLog)
    val scoring = ScoringEngine(
        model = model,
        heavy = HeavyMlRuntime().also { HeavyMlAssets.apply(app, it) },
        extended = ExtendedAiRuntime()
    ).also { engine ->
        engine.edgeModel = importedModel.trustedCurrent()
    }
    val support = DecisionSupport(
        logStore = logStore,
        adapterStore = adapterStore,
        guardrailStore = guardrailStore,
        scoring = scoring,
        heavyStore = heavyStore,
        results = resultsWriter
    )
    val external = ExternalMarketCache()
    val hub = SignalHub(
        scoring = scoring,
        notifier = notifier,
        logStore = logStore,
        results = resultsWriter
    )
    val lastOrderError = com.dirk.kalshiodds.signal.trade.LastOrderErrorStore(app)
    val paper = PaperBookStore(app) { fills ->
        runCatching { archive.upsertPaperFills(fills) }
    }
    val shadow = com.dirk.kalshiodds.signal.paper.ShadowBookStore(app)
    val lastMinuteStore = com.dirk.kalshiodds.signal.lastminute.LastMinuteStore(app)
    val lastMinuteEngine = com.dirk.kalshiodds.signal.lastminute.LastMinuteEngine(nowMs = { clock.nowMs() })
    val brti = com.dirk.kalshiodds.signal.lastminute.BrtiCompositeClient()
    val lastMinuteNotifier = com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifier(app)
    val d3Store = com.dirk.kalshiodds.signal.d3.D3Store(app)
    val d3Engine = com.dirk.kalshiodds.signal.d3.D3Engine(nowMs = { clock.nowMs() })
    val d3Notifier = com.dirk.kalshiodds.signal.d3.D3Notifier(app)
    val kalshiTraffic = com.dirk.kalshiodds.data.api.KalshiTraffic()
    val d3Markets = com.dirk.kalshiodds.signal.d3.D3MarketClient(
        resolveApi = {
            val keyed = hub.settings.tradingCredentialsConfigured()
            NetworkModule.marketsApi(
                demo = hub.settings.kalshiDemoEnabled,
                credentials = if (keyed) ({ tradingCredentials() }) else null
            )
        },
        rateLimiter = kalshiTraffic.limiter,
        feedHealth = kalshiTraffic.health
    )
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
            runCatching { tradeClient.createLimit(ticket, clientOrderId) }.also { r ->
                if (r.isSuccess) {
                    tradeEvents.realBetPlaced(
                        clientOrderId,
                        "${if (ticket.isSell) "SELL" else "BUY"} ${ticket.side} ${ticket.contracts} × ${ticket.ticker} @ ${(ticket.limitPrice * 100).toInt()}¢"
                    )
                }
            }
        },
        cancelOrder = { order ->
            runCatching { tradeClient.cancel(order) }
        },
        onAttempt = { row: TicketAttemptRow -> resultsWriter.enqueueTicket(row) },
        findExistingOrder = { clientOrderId, ticker ->
            runCatching { tradeClient.findByClientOrderId(clientOrderId, ticker) }.getOrNull()
        },
        pendingOrderIds = resultsImpl
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
            paper.book.openTickers() + shadow.book.openTickers() + d3Store.heldTickers() + d3Store.openTickers() +
                ladder.openTickers() + decisions.unsettledTickers() + scalp.openTickers()
        },
        onMarketSettled = { ticker, result ->
            paper.book.settle(ticker, result)
            shadow.book.settle(ticker, result)
            lastMinuteStore.settle(ticker, result)
            d3Store.settle(ticker, result)
            runCatching { predictionLedger?.settleLedger(ticker, result, System.currentTimeMillis()) }
            runCatching { ladder.settle(ticker, result) }
            runCatching { scalp.settle(ticker, result) }
            decisions.onSettled()
        },
        onCalibration = { hub.applyCalibration(it) },
        rateLimiter = kalshiTraffic.limiter,
        feedHealth = kalshiTraffic.health,
        onAfterScore = {
            support.refreshFromSettlements(hub.settings)
            paper.book.settleFromLog(logStore.readAll())
            decisions.refitIfDue()
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
        },
        onThrottle = { error ->
            if (com.dirk.kalshiodds.data.api.KalshiRequestStatus.shouldBackoff(error)) {
                val wait = kalshiTraffic.limiter.onFailure(
                    com.dirk.kalshiodds.data.api.KalshiRequestStatus.httpCode(error),
                    com.dirk.kalshiodds.data.api.KalshiRequestStatus.retryAfterMs(error)
                )
                kalshiTraffic.health.note(error, wait)
            }
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
