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
import kotlinx.serialization.json.put

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
    /** Spot features: from Kalshi's index stream when "Bitcoin price from Kalshi only" is on, else the exchange poll. */
    val external = ExternalMarketCache(
        kalshi = { now ->
            if (hub.settings.kalshiPriceOnly) com.dirk.kalshiodds.signal.external.KalshiSpot.snapshot(cfIndex, now) else null
        }
    )
    /** Paper-only late-favorite tracker (edge_search H2). Never orders. */
    val lateFavorite = com.dirk.kalshiodds.signal.latefav.LateFavoriteStore(app)
    /** Paper-only flow-fade tracker (docs/flow-fade-2026-10-04.md). Never orders. */
    val flowFade = com.dirk.kalshiodds.signal.flowfade.FlowFadeStore(app)
    /** Paper-only 1¢-better resting bid (maker_sim improve rule). Never orders. */
    val centBetter = com.dirk.kalshiodds.signal.centbetter.CentBetterStore(app)
    /** Paper record of the clear-lead rule (Dirk's "bet the way Bitcoin is going"). Never orders. */
    val clearLead = com.dirk.kalshiodds.signal.trend.ClearLeadStore(app)
    /** Paper record of the late-comeback rule (the phone's call that made money on the recorded days). Never orders. */
    val lateComeback = com.dirk.kalshiodds.signal.trend.LateComebackStore(app)
    /** Learning mode: every strategy bets on paper (default on). Set before anything reads the strategy list. */
    private val learningModeBoot: Boolean = app.getSharedPreferences("bitcoin_claude_supervisor", Context.MODE_PRIVATE)
        .getBoolean("learning_mode", true)
        .also { com.dirk.kalshiodds.signal.scalper.ScalpStrategy.learningMode = it }

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
        engine = com.dirk.kalshiodds.signal.scalper.ScalperEngine(tuning = scalperStore.tuning),
        onReset = { scalperStore.log.archive() },
        fairYes = { t, now -> scalperFairYes(t, now) }
    )

    /**
     * YES fair price for the arena's late favourites, from Kalshi only: the CF
     * Benchmarks index Kalshi streams on its WebSocket (the index these markets
     * settle on), its own 1-second volatility, the strike Kalshi publishes and,
     * in the final minute, Kalshi's running settlement average. While the index
     * is stale it falls back to the Coinbase stream ([coinbaseIndex], the source
     * the arena replay priced these strategies with), unless Settings → "Bitcoin
     * price from Kalshi only" is on; null (no trade) when neither is fresh.
     */
    private fun scalperFairYes(ticker: String, nowMs: Long): Double? {
        val coin = com.dirk.kalshiodds.signal.scalper.ScalperState.coinOf(ticker)
        val close = scoring.book.closeTime(ticker) ?: com.dirk.kalshiodds.signal.trade.TakerCost.closeEpochMs(ticker) ?: return null
        val strike = scoring.book.strike(ticker) ?: com.dirk.kalshiodds.signal.engine.DirectionSanity.parseStrike(ticker)
        val spot = cfIndex.fresh(coin, nowMs)?.value
            ?: return if (hub.settings.kalshiPriceOnly) null else coinbaseFairYes(coin, strike, close, nowMs)
        val observed = if (close - nowMs < 60_000L) {
            cfIndex.finalMinuteAverage(coin, close, nowMs)?.takeIf { it > 0.0 }?.let { kotlin.math.ln(it) }
                ?: cfIndex.meanLog(coin, close - 60_000L, nowMs)
        } else {
            null
        }
        return com.dirk.kalshiodds.signal.scalper.ScalpFair.fairYes(
            spot = spot,
            strike = strike,
            tteSeconds = (close - nowMs) / 1000.0,
            std1m = cfIndex.std1m(coin, nowMs),
            coin = coin,
            observedMeanLog = observed,
            indexNoise = com.dirk.kalshiodds.signal.external.CfIndexBook.INDEX_NOISE_LOG
        )
    }

    /** [scalperFairYes] from the Coinbase stream alone, priced the way the arena replay prices it. */
    private fun coinbaseFairYes(coin: String, strike: Double?, close: Long, nowMs: Long): Double? {
        val spot = coinbaseIndex.fresh(coin, nowMs)?.value ?: return null
        val observed = if (close - nowMs < 60_000L) coinbaseIndex.meanLog(coin, close - 60_000L, nowMs) else null
        return com.dirk.kalshiodds.signal.scalper.ScalpFair.fairYes(
            spot = spot,
            strike = strike,
            tteSeconds = (close - nowMs) / 1000.0,
            std1m = coinbaseIndex.std1m(coin, nowMs),
            coin = coin,
            observedMeanLog = observed
        )
    }
    /** Paper limit orders resting against the real book, filled by real trades. Never orders. */
    val paperLimits: com.dirk.kalshiodds.signal.limit.PaperLimitBook by lazy {
        com.dirk.kalshiodds.signal.limit.PaperLimitBook(
            paper = paper.book,
            levels = { scoring.book.snapshotBook(it) }
        )
    }
    val hub = SignalHub(
        paperLimits = { paperLimits },
        scoring = scoring,
        notifier = notifier,
        logStore = logStore,
        results = resultsWriter,
        lateFavorite = lateFavorite.ledger,
        flowFade = flowFade.ledger,
        centBetter = centBetter.ledger,
        clearLead = clearLead.ledger,
        lateComeback = lateComeback.ledger,
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

    /** Coinbase prints kept like the index, for the arena's fair price while the index is stale. */
    val coinbaseIndex = com.dirk.kalshiodds.signal.external.CfIndexBook()

    /**
     * AI supervisor: a Groq / Mistral chat LLM that reads the live 15-minute feed every 60 s and tunes the
     * paper scalper's settings, learning from every closed trade. It changes only the Arena settings
     * ([ScalpKnobs], within hard bounds, no cooldown). Paper only; it cannot reach an order. On while
     * Data → "AI supervisor" is on and a key is saved.
     */
    private val supervisorPrefs = app.getSharedPreferences("bitcoin_claude_supervisor", Context.MODE_PRIVATE)
    /** Learning mode on/off (Data → AI supervisor). Takes effect on the next decision, no restart. */
    var learningMode: Boolean
        get() = com.dirk.kalshiodds.signal.scalper.ScalpStrategy.learningMode
        set(value) {
            com.dirk.kalshiodds.signal.scalper.ScalpStrategy.learningMode = value
            supervisorPrefs.edit().putBoolean("learning_mode", value).apply()
        }

    val supervisorLog = com.dirk.kalshiodds.signal.scalper.SupervisorLog(java.io.File(app.filesDir, "supervisor"))

    /**
     * Provider slots (Groq, Mistral, Gemini; keys saved encrypted on the Data screen). Each AI has its OWN order so the two
     * AIs spread across the free tiers: the Scalping AI calls every 60 s, the Paper AI once per window.
     */
    private fun llmProviders(vararg order: String): List<com.dirk.kalshiodds.signal.scalper.LlmProvider> {
        val P = com.dirk.kalshiodds.signal.scalper.LlmProvider
        return order.map { n ->
            when (n) {
                "groq" -> com.dirk.kalshiodds.signal.scalper.LlmProvider("groq", P.GROQ_URL, P.GROQ_MODELS) { extraSecrets.groqApiKey }
                "mistral" -> com.dirk.kalshiodds.signal.scalper.LlmProvider("mistral", P.MISTRAL_URL, P.MISTRAL_MODELS) { extraSecrets.mistralApiKey }
                else -> com.dirk.kalshiodds.signal.scalper.LlmProvider("gemini", P.GEMINI_URL, P.GEMINI_MODELS, "auto") { extraSecrets.geminiApiKey }
            }
        }
    }
    /** Every AI call, change, verdict and paper bet, for the AI tab's charts. Kept on the phone. */
    val aiActivity = com.dirk.kalshiodds.signal.scalper.AiActivity(
        load = { supervisorPrefs.getString("ai_activity", null) },
        save = { json -> supervisorPrefs.edit().putString("ai_activity", json).apply() }
    )
    private fun callsOf(ai: String): (com.dirk.kalshiodds.signal.scalper.LlmAttempt) -> Unit = { a ->
        aiActivity.record(ai, com.dirk.kalshiodds.signal.scalper.AiEvent.CALL, a.provider, a.ok, a.ms, "${a.model} | ${a.error}")
    }
    private fun eventsOf(ai: String): (String, kotlinx.serialization.json.JsonObject) -> Unit = { kind, body ->
        fun f(k: String) = (body[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val detail = when (kind) {
            "applied" -> "${f("strategy")} ${f("param")} ${f("old") ?: "default"} → ${f("new")}"
            "rejected" -> "${f("strategy")} ${f("param")}: ${f("why")}"
            "better", "worse" -> "${f("strategy")} ${f("param")} → ${f("new")}"
            "bet" -> "${f("side")} x${f("contracts")} ${f("ticker")}"
            "settled" -> "${f("side")} ${f("ticker")} ${f("pnlUsd")}"
            else -> f("reason") ?: f("why") ?: ""
        }
        aiActivity.record(ai, kind, detail = detail)
    }

    /** Keys screen's Test button: one real tool-call request per provider. Blocks: call off the main thread. */
    fun testLlmKeys(): List<String> =
        com.dirk.kalshiodds.signal.scalper.HttpLlmChat(llmProviders("groq", "mistral", "gemini"), onCall = callsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.CHAT)).test()

    val anyLlmKey: Boolean
        get() = extraSecrets.groqApiKey.isNotBlank() || extraSecrets.mistralApiKey.isNotBlank() || extraSecrets.geminiApiKey.isNotBlank()
    private fun llmError(kind: String): (String) -> Unit =
        { msg -> supervisorLog.append(kind, "{\"ts\":${System.currentTimeMillis()},\"error\":\"$msg\"}") }

    /** Scalping AI: tunes the scalping algorithms every 60 s. Groq, then Gemini, then Mistral. */
    val llmSupervisor = com.dirk.kalshiodds.signal.scalper.LlmSupervisor(
        tuning = scalperStore.tuning,
        chat = com.dirk.kalshiodds.signal.scalper.HttpLlmChat(llmProviders("groq", "gemini", "mistral"), onError = llmError("errors"), onCall = callsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.SCALPING)),
        feed = { supervisorFeed() },
        stats = { name -> scalperStore.ledger.snapshot().stats(name) },
        sink = { kind, line -> supervisorLog.append(kind, line) },
        persistMemory = { json -> supervisorPrefs.edit().putString("memory_json", json).apply() },
        initialMemory = runCatching {
            supervisorPrefs.getString("memory_json", null)?.let {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(com.dirk.kalshiodds.signal.scalper.SupervisorMemory.serializer(), it)
            }
        }.getOrNull() ?: com.dirk.kalshiodds.signal.scalper.SupervisorMemory(),
        onEvent = eventsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.SCALPING)
    )

    /** Paper AI: a different AI. Decides one paper bet per window. Gemini, then Mistral, then Groq. Own memory and journal. */
    val paperAi = com.dirk.kalshiodds.signal.scalper.PaperAi(
        chat = com.dirk.kalshiodds.signal.scalper.HttpLlmChat(llmProviders("gemini", "mistral", "groq"), onError = llmError("paper_ai_errors"), onCall = callsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.PAPER)),
        feed = { supervisorFeed() },
        sink = { kind, line -> supervisorLog.append(kind, line) },
        persistMemory = { json -> supervisorPrefs.edit().putString("paper_ai_memory", json).apply() },
        initialMemory = runCatching {
            supervisorPrefs.getString("paper_ai_memory", null)?.let {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(com.dirk.kalshiodds.signal.scalper.PaperAiMemory.serializer(), it)
            }
        }.getOrNull() ?: com.dirk.kalshiodds.signal.scalper.PaperAiMemory(),
        onEvent = eventsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.PAPER)
    )

    /** The chat screen: one thread per AI, history kept on the phone. */
    private val chatLlm = com.dirk.kalshiodds.signal.scalper.HttpLlmChat(llmProviders("groq", "gemini", "mistral"), onError = llmError("chat_errors"), onCall = callsOf(com.dirk.kalshiodds.signal.scalper.AiEvent.CHAT))
    val aiChat = com.dirk.kalshiodds.signal.scalper.AiChat(
        ask = { msgs -> chatLlm.chat(msgs) },
        tuning = scalperStore.tuning,
        context = { which ->
            if (which == com.dirk.kalshiodds.signal.scalper.AiChat.PAPER) {
                "Paper AI record: ${paperAi.record.value}\nStatus: ${paperAi.status.value}\nOpen bets: ${paperAi.openBets().joinToString { it.side + " " + it.ticker }}\nLive feed: ${supervisorFeed()}"
            } else {
                "Scalping AI status: ${llmSupervisor.status.value}\nRecent journal: ${llmSupervisor.log.value.take(5).joinToString(" | ")}\nLive feed: ${supervisorFeed()}"
            }
        },
        load = { supervisorPrefs.getString("chat_json", null) },
        save = { json -> supervisorPrefs.edit().putString("chat_json", json).apply() },
        lastError = { chatLlm.lastError }
    )
    private val aiKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Run both AIs now instead of waiting for the next minute (after saving keys, switching an AI on). */
    fun kickAi() { aiKick.trySend(Unit) }

    private val supervisorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    init {
        scalper.onClosed = { llmSupervisor.onClosed(it) }
        supervisorScope.launch {
            kotlinx.coroutines.delay(5_000L)
            while (true) {
                if (!anyLlmKey) {
                    if (extraSecrets.supervisorEnabled) llmSupervisor.idle("add a Groq, Mistral or Gemini key in AI → Keys to start")
                    if (extraSecrets.paperAiEnabled) paperAi.idle("add a Groq, Mistral or Gemini key in AI → Keys to start")
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) { aiKick.receive() }
                    continue
                }
                if (extraSecrets.supervisorEnabled) runCatching { llmSupervisor.tick() }
                if (extraSecrets.paperAiEnabled) runCatching { paperAi.tick() }
                // Next pass in 60 s, or at once after keys are saved or an AI is switched on.
                kotlinx.coroutines.withTimeoutOrNull(60_000L) { aiKick.receive() }
            }
        }
    }

    /** The live 15-minute window as the supervisor sees it: moves, book, spot, open positions. */
    private fun supervisorFeed(): kotlinx.serialization.json.JsonObject {
        val now = System.currentTimeMillis()
        val w = scalper.liveWindow(now)
            ?: return kotlinx.serialization.json.buildJsonObject { put("window", "none: no live 15-minute market yet") }
        val secIn = ((now - w.openMs) / 1000L).toInt()
        val last = w.mids.lastOrNull()?.second
        fun cents(v: Double?): Double? = v?.let { Math.round(it * 10_000.0) / 100.0 }
        fun midAgo(s: Int): Double? = w.mids.lastOrNull { it.first <= secIn - s }?.second
        val book = scoring.book.topOfBook(w.ticker)
        val coin = com.dirk.kalshiodds.signal.scalper.ScalperState.coinOf(w.ticker)
        return kotlinx.serialization.json.buildJsonObject {
            put("ticker", w.ticker)
            put("secondsIntoWindow", secIn)
            put("secondsLeft", 900 - secIn)
            cents(last)?.let { put("yesMidCents", it) }
            for ((label, s) in listOf("change10sCents" to 10, "change30sCents" to 30, "change60sCents" to 60, "change180sCents" to 180)) {
                val a = midAgo(s)
                if (last != null && a != null) cents(last - a)?.let { put(label, it) }
            }
            book?.yesBid?.let { put("yesBidCents", cents(it)) }
            book?.yesAsk?.let { put("yesAskCents", cents(it)) }
            book?.noBid?.let { put("noBidCents", cents(it)) }
            book?.noAsk?.let { put("noAskCents", cents(it)) }
            cfIndex.fresh(coin, now)?.value?.let { put("spot", it) }
            put("openPaperPositions", w.open.size)
        }
    }

    /**
     * Account changes pushed by Kalshi's WebSocket (a fill, an order update,
     * a position change). Screens listening refresh positions and cash from
     * REST right away instead of waiting for the next poll.
     */
    val accountEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

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
            com.dirk.kalshiodds.signal.external.CfIndexBook.indexIdOf(asset)?.let { id ->
                val now = System.currentTimeMillis()
                runCatching {
                    coinbaseIndex.onUpdate(
                        com.dirk.kalshiodds.signal.ws.KalshiWsMessages.Parsed.IndexValue(id, price, now, now, null, null, null, null), now
                    )
                }
            }
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
                centBetter.ledger.openTickers() + clearLead.ledger.openTickers() + lateComeback.ledger.openTickers() +
                scalper.openTickers() +
                paperLimits.openTickers() +
                recorder.pendingSettlementTickers()
        },
        onMarketSettled = { ticker, result ->
            syncLedger(ticker)
            paper.book.settle(ticker, result)
            lateFavorite.ledger.settle(ticker, result)
            flowFade.ledger.settle(ticker, result)
            centBetter.ledger.settle(ticker, result)
            clearLead.ledger.settle(ticker, result)
            lateComeback.ledger.settle(ticker, result)
            scalper.settle(ticker, result)
            paperAi.onSettled(ticker, result)
            paperLimits.closeTicker(ticker)
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
            lateComeback.ledger.settleFromLog(logStore.readAll())
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

    /** Maker fee, strike and settlement checked against Kalshi; read-only calls, never an order. */
    val contractChecks = com.dirk.kalshiodds.signal.scalper.ContractCheckRunner(
        book = scalperStore.checks,
        cf = cfIndex,
        api = { NetworkModule.publicApi(hub.settings.kalshiDemoEnabled) },
        onMakerFeeRate = { rate -> scalper.engine.makerFeeRate = rate }
    )

    init {
        contractChecks.start(ledgerScope)
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
