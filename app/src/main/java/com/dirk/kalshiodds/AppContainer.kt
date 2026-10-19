package com.dirk.kalshiodds

import kotlinx.coroutines.launch
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
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.dirk.kalshiodds.crash.SafeScopes.handler("decisions")),
        longshot = com.dirk.kalshiodds.decision.LongshotResidual.loadAsset(app)
    ).also { it.refitIfDue() }
    val ladder = com.dirk.kalshiodds.decision.LadderStore(app)
    /** 0.3.43 paper limit orders (paper_orders in the results DB). Fills hit the paper ledger only — never Kalshi. */
    val paperOrders: com.dirk.kalshiodds.signal.paper.PaperOrderBook by lazy {
        com.dirk.kalshiodds.signal.paper.PaperOrderBook(
            store = (resultsImpl as? com.dirk.kalshiodds.signal.paper.PaperOrderPersistence)
                ?: com.dirk.kalshiodds.signal.paper.InMemoryPaperOrderPersistence(),
            sink = com.dirk.kalshiodds.signal.paper.PaperBookFillSink(paper.book)
        )
    }
    /** 0.3.38 paper scalps (scalp_trades in the results DB). Never places orders. */
    val scalpControls = com.dirk.kalshiodds.decision.SavedScalpControls(app)
    /** 1.0.12: usage, prices, budget, and the AI-adjustments log. API keys stay in [llmSettings]. */
    val aiWatch = com.dirk.kalshiodds.llm.AiWatchStore(File(app.filesDir, "ai-watch.json"))
    val llmSettings by lazy { com.dirk.kalshiodds.llm.LlmSettings(app) }
    val llmClient by lazy { com.dirk.kalshiodds.llm.LlmClient(llmSettings) }
    val llmStrategy by lazy {
        com.dirk.kalshiodds.llm.LlmPaperStrategy(scalp, llmSettings, llmClient) { reply ->
            recordLlm(com.dirk.kalshiodds.llm.LlmSources.STRATEGY, com.dirk.kalshiodds.llm.LlmProviders.XAI, reply.model.ifBlank { llmSettings.model }, reply.promptTokens, reply.completionTokens)
        }
    }
    private val aiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val livePost = java.util.concurrent.atomic.AtomicReference<(String) -> Unit> { }
    /** 1.0.12: follows the live window while the scalping service is up. Paper settings only. */
    val aiWatcher by lazy {
        com.dirk.kalshiodds.llm.AiWindowWatcher(
            controls = scalpControls,
            store = aiWatch,
            snapshot = { watchSnapshot() },
            ask = { ep, msgs -> llmClient.chatReply(msgs, 1200, ep) },
            endpoint = { llmSettings.chatEndpoint() },
            scalpersRunning = { scalpServiceRunning() },
            now = { clock.nowMs() },
            paper = { scalpInsights.tapes(scalp.stats.value) },
            onBenched = { runCatching { scalpInsights.recompute() } }
        ).also { watcher ->
            watcher.onLivePost = { text -> livePost.get().invoke(text) }
            watcher.onWake = { aiScope.launch { runCatching { watcher.tick() } } }
        }
    }
    /** 1.0.4 AI tab: persistent chat + confirm-gated PAPER settings tool. No path to real-money orders. */
    val aiChat by lazy {
        com.dirk.kalshiodds.llm.AiChatController(
            store = com.dirk.kalshiodds.llm.ChatHistoryStore(File(app.filesDir, com.dirk.kalshiodds.llm.ChatHistoryStore.FILE_NAME)),
            controls = scalpControls,
            ask = { msgs ->
                val ep = llmSettings.chatEndpoint()
                val reply = llmClient.chatReply(msgs, 4000, ep)
                recordLlm(com.dirk.kalshiodds.llm.LlmSources.CHAT, ep.provider, reply.model.ifBlank { ep.model }, reply.promptTokens, reply.completionTokens)
                reply.model to reply.text
            },
            hasKey = { llmSettings.chatReady },
            modelName = { llmSettings.chatEndpoint().model },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + com.dirk.kalshiodds.crash.SafeScopes.handler("ai-chat")),
            liveContext = { liveChatWindow() },
            onApplied = { key -> aiWatcher.noteKey(key) }
        ).also { chat -> livePost.set { text -> chat.postLive(text) } }
    }

    /** 1.0.11: current KXBTC15M window from live marks, the spot ring, and open paper scalps. Called on each chat send. */
    private fun liveChatWindow(): String {
        val now = clock.nowMs()
        val quotes = scalp.marks.value.values.toList()
        val strikes = com.dirk.kalshiodds.llm.ChatPrompt.currentStrikes(quotes, now)
        val openMs = strikes.firstOrNull()?.closeMs?.minus(com.dirk.kalshiodds.domain.MarketLifecycle.WINDOW_MS)
        val atOpen = openMs?.let { scalp.spotAt("BTC", it) }
        val atm = com.dirk.kalshiodds.llm.ChatPrompt.atm(strikes, com.dirk.kalshiodds.llm.ChatPrompt.liveSpot(strikes))
        val up = atm?.let { q ->
            val dm = runCatching { scalp.dm60Snapshot(q.ticker.uppercase(), now) }.getOrDefault(0.0)
            com.dirk.kalshiodds.llm.ChatPrompt.modelUp(q, dm)
        }
        val window = com.dirk.kalshiodds.llm.ChatPrompt.liveWindow(
            com.dirk.kalshiodds.llm.ChatPrompt.LiveWindow(now, quotes, scalp.snapshot(), atOpen, up)
        )
        val card = aiWatch.live.value
        val posts = runCatching { aiChat.messages.value.map { it.text } }.getOrDefault(emptyList())
        val history = com.dirk.kalshiodds.llm.ChatPrompt.tickHistory(card, aiWatch.adjustments.value, posts)
        return listOf(window, history).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun recordLlm(source: String, provider: String, model: String, prompt: Long, completion: Long) {
        runCatching { aiWatch.record(source, provider, model, prompt, completion, clock.nowMs()) }
    }

    private fun scalpServiceRunning(): Boolean {
        val s = hub.settings
        return com.dirk.kalshiodds.signal.paper.AlwaysOnAutopilot.autopilotWanted(s.paperTradingEnabled, s.aiPaperAutopilotEnabled)
    }

    /** 1.0.12: chat live window plus each scalper's bench, recent results, and current paper settings. */
    private fun watchSnapshot(): com.dirk.kalshiodds.llm.WindowSnapshot {
        val now = clock.nowMs()
        val quotes = scalp.marks.value.values.toList()
        val strikes = com.dirk.kalshiodds.llm.ChatPrompt.currentStrikes(quotes, now)
        val live = liveChatWindow()
        if (strikes.isEmpty()) return com.dirk.kalshiodds.llm.WindowSnapshot("", 0L, live, "none")
        val closeMs = strikes.first().closeMs
        val key = com.dirk.kalshiodds.domain.MarketLifecycle.windowKey(strikes.first().ticker) ?: strikes.first().ticker
        val spot = com.dirk.kalshiodds.llm.ChatPrompt.liveSpot(strikes)
        val atm = com.dirk.kalshiodds.llm.ChatPrompt.atm(strikes, spot)
        val dm = atm?.let { q -> runCatching { scalp.dm60Snapshot(q.ticker.uppercase(), now) }.getOrDefault(0.0) } ?: 0.0
        val up = com.dirk.kalshiodds.llm.ChatPrompt.modelUp(atm, dm)
        val controls = scalpControls.state.value
        val bench = com.dirk.kalshiodds.decision.insights.BenchRegistry.decisions.value
        val open = scalp.snapshot()
        val tapes = runCatching { scalpInsights.tapes(scalp.stats.value) }.getOrDefault(emptyList()).associateBy { it.code }
        val scalpers = com.dirk.kalshiodds.decision.ScalpModels.ALL.map { m ->
            val b = bench[m.code]
            val fb = scalpControls.feedback(m.code)
            val tape = tapes[m.code]
            val fields = com.dirk.kalshiodds.llm.SettingTools.fieldsFor(m.code).joinToString(",") { f ->
                "$f=${com.dirk.kalshiodds.llm.SettingTools.get(controls, "strategy.${m.code}.$f")}"
            }
            val opens = open.count { t ->
                com.dirk.kalshiodds.decision.ScalpModels.modelOf(t)?.code == m.code &&
                    t.state != com.dirk.kalshiodds.decision.ScalpState.CLOSED &&
                    t.state != com.dirk.kalshiodds.decision.ScalpState.NO_FILL
            }
            val last20 = tape?.recentReal?.takeLast(20)?.joinToString(",") { n ->
                String.format(java.util.Locale.US, "%+.2f", n)
            }.orEmpty()
            val benchName = when {
                controls.benchOverrides[m.code] == "ACTIVE" -> "FORCED_ACTIVE"
                controls.benchOverrides[m.code] == "BENCHED" -> "FORCED_BENCHED"
                m.code in controls.aiBench -> "BENCHED"
                else -> b?.status?.name ?: "ACTIVE"
            }
            com.dirk.kalshiodds.llm.AiWindowContext.Scalper(
                m.code, benchName, tape?.trades ?: fb.trades, tape?.netUsd ?: b?.netUsd ?: fb.meanNetPerContract, fields, opens,
                winRate = tape?.winRate, last20 = last20, algo = com.dirk.kalshiodds.decision.StrategyAlgo.describe(controls, m.code)
            )
        }
        val positions = open.filter { t ->
            t.state != com.dirk.kalshiodds.decision.ScalpState.CLOSED && t.state != com.dirk.kalshiodds.decision.ScalpState.NO_FILL
        }.joinToString(",") { "${it.variantId}:${it.side}:${it.state}" }
        val market = com.dirk.kalshiodds.llm.AiWindowContext.Market(
            key, spot, atm?.yesBid, atm?.yesAsk, atm?.bid("NO"), atm?.ask("NO"), up, positions
        )
        val lines = scalpers.map { s ->
            val label = com.dirk.kalshiodds.decision.ScalpModels.modelByCode(s.code)?.label ?: s.code
            com.dirk.kalshiodds.llm.AiWindowContext.line(label, s)
        }
        return com.dirk.kalshiodds.llm.WindowSnapshot(key, closeMs, com.dirk.kalshiodds.llm.AiWindowContext.compose(live, lines), com.dirk.kalshiodds.llm.AiWindowContext.fingerprint(market, scalpers))
    }
    val scalp = com.dirk.kalshiodds.decision.ScalpBook(
        (resultsImpl as? com.dirk.kalshiodds.decision.ScalpPersistence)
            ?: com.dirk.kalshiodds.decision.InMemoryScalpPersistence(),
        tuneStore = com.dirk.kalshiodds.decision.SharedPrefsScalpTuneStore(app),
        liveExtras = true,
        controls = scalpControls,
        // 0.3.40: total open scalp cost ≤ free paper bankroll (cash not tied up in Autopilot paper bets).
        // 0.3.59 experiment: scalpers' paper bankroll is unlimited (no cash check). P&L still per algo; Autopilot unchanged.
        bankrollUsd = { Double.POSITIVE_INFINITY }
    )
    /** 1.0.9 paper insights: auto-bench, alerts (hot streak / big loss / strong AI). Never touches real money. */
    val scalpInsightNotifier by lazy { com.dirk.kalshiodds.signal.notify.ScalpInsightNotifier(app) }
    val scalpInsights by lazy {
        com.dirk.kalshiodds.decision.insights.ScalpInsights(scalp, scalpControls, resultsImpl as? com.dirk.kalshiodds.decision.ScalpPersistence) { a ->
            scalpInsightNotifier.post(a.kind.name, a.key, a.title, a.body)
        }
    }
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
                    com.dirk.kalshiodds.data.api.KalshiRequestStatus.retryAfterMs(error),
                local = com.dirk.kalshiodds.data.api.KalshiRequestStatus.isLocalThrottle(error)
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

    init {
        // 0.3.46: paper MARKET fills walk the live WS book (VWAP of consumed levels, capped at total displayed depth).
        paper.book.depth = { ticker, side, buy ->
            val b = scoring.book.snapshotBook(ticker)?.takeIf { scoring.book.hasSnapshot(ticker) }
            if (b == null) null else com.dirk.kalshiodds.signal.paper.PaperDepthLevels.levels(side, buy, b.yes, b.no)
        }
    }

}
