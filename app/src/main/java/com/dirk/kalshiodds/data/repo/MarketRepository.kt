package com.dirk.kalshiodds.data.repo

import android.content.Context
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketCache
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.EDGE_ALERT_THRESHOLD_PP
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.toUiModel
import com.dirk.kalshiodds.domain.withSignalScore
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.prediction.SettlementScorer
import com.dirk.kalshiodds.prediction.SignalSnapshot
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class MarketsSnapshot(
    val btc: List<MarketUiModel>,
    val eth: List<MarketUiModel> = emptyList(),
    val sol: List<MarketUiModel> = emptyList(),
    val extra: List<MarketUiModel> = emptyList(),
    val fetchedAtEpochMs: Long,
    val fromCache: Boolean,
    val errorMessage: String? = null,
    /** True when Kalshi returned HTTP 429 or 503 — callers should back off. */
    val rateLimited: Boolean = false,
    val modelScoreCorrect: Int? = null,
    val modelScoreTotal: Int? = null,
    val modelMeanBrier: Double? = null,
    val avgEdgeWhenRight: Double? = null,
    val avgEdgeWhenWrong: Double? = null
) {
    val allMarkets: List<MarketUiModel> get() = btc + eth + sol + extra

    fun retainActiveWindows(
        nowMs: Long,
        series: Collection<String> = CryptoMarkets.DEFAULT_SERIES
    ): MarketsSnapshot {
        val keep = com.dirk.kalshiodds.domain.ActiveMarketResolver.tickers(allMarkets, series, nowMs)
        fun List<MarketUiModel>.keep() = filter { it.ticker in keep }
        return copy(
            btc = btc.keep(),
            eth = eth.keep(),
            sol = sol.keep(),
            extra = extra.filter {
                it.ticker in keep || com.dirk.kalshiodds.domain.MarketLifecycle.isTradable(it, nowMs)
            }
        )
    }

    fun overlayScores(
        scores: Map<String, com.dirk.kalshiodds.signal.engine.ScoringEngine.Score>,
        thresholdPp: Double
    ): MarketsSnapshot {
        if (scores.isEmpty()) return this
        fun List<MarketUiModel>.apply(): List<MarketUiModel> = map { m ->
            val s = scores[m.ticker] ?: return@map m
            m.withSignalScore(s, thresholdPp)
        }
        return copy(btc = btc.apply(), eth = eth.apply(), sol = sol.apply(), extra = extra.apply())
    }
}

class MarketRepository(
    context: Context,
    private val api: KalshiApi = NetworkModule.api,
    private val resolveApi: () -> KalshiApi = { api },
    private val cache: MarketCache = MarketCache(context.applicationContext),
    private val model: DipHunterModel = DipHunterModel(context.applicationContext),
    private val logStore: PredictionLogStore = PredictionLogStore(context.applicationContext),
    extraOpenTickers: () -> Set<String> = { emptySet() },
    onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> },
    private val scorer: SettlementScorer = SettlementScorer(
        resolveApi,
        logStore,
        extraOpenTickers,
        onMarketSettled
    ),
    private val onCalibration: ((Calibrator.State) -> Unit)? = null,
    private val onAfterScore: (suspend () -> Unit)? = null
) {

    @Volatile private var lastScoreCorrect: Int? = null
    @Volatile private var lastScoreTotal: Int? = null
    @Volatile private var lastMeanBrier: Double? = null
    @Volatile private var lastEdgeRight: Double? = null
    @Volatile private var lastEdgeWrong: Double? = null
    @Volatile var lastCalibration: Calibrator.State = Calibrator.State()
        private set

    private val refreshMutex = Mutex()

    val cachedSnapshot: Flow<MarketsSnapshot?> = cache.cachedFlow.map { payload ->
        payload?.toSnapshot(fromCache = true)
    }

    suspend fun listOpen(series: String): List<MarketUiModel> {
        val resp = resolveApi().getMarkets(seriesTicker = series, status = "open")
        val kind = when (series.uppercase()) {
            KalshiApi.SERIES_ETH -> SeriesKind.ETH
            KalshiApi.SERIES_SOL -> SeriesKind.SOL
            KalshiApi.SERIES_BTC -> SeriesKind.BTC
            else -> CryptoMarkets.kindFor(series)
        }
        return resp.markets
            .filter { CryptoMarkets.isCryptoTicker(it.ticker) }
            .map { it.toUiModel(kind) }
    }

    suspend fun scoreSettlementsNow(nowMs: Long = System.currentTimeMillis()) {
        scorer.maybeScore(nowMs, minIntervalMs = 0L)
    }

    suspend fun refresh(
        watchBtc: Boolean = true,
        watchEth: Boolean = true,
        watchSol: Boolean = true,
        extraTickers: List<String> = emptyList(),
        edgeThresholdPp: Double = EDGE_ALERT_THRESHOLD_PP
    ): MarketsSnapshot = refreshMutex.withLock {
        refreshOnce(
            watchBtc = true,
            watchEth = false,
            watchSol = false,
            extraTickers = CryptoMarkets.liveTickers(extraTickers),
            edgeThresholdPp = edgeThresholdPp
        )
    }

    private suspend fun refreshOnce(
        watchBtc: Boolean,
        watchEth: Boolean,
        watchSol: Boolean,
        extraTickers: List<String>,
        edgeThresholdPp: Double
    ): MarketsSnapshot = coroutineScope {
        try {
            val client = resolveApi()
            val btcDeferred = async {
                if (watchBtc) client.getMarkets(KalshiApi.SERIES_BTC, status = "open") else null
            }
            val ethDeferred = async {
                if (watchEth) client.getMarkets(KalshiApi.SERIES_ETH, status = "open") else null
            }
            val solDeferred = async {
                if (watchSol) client.getMarkets(KalshiApi.SERIES_SOL, status = "open") else null
            }
            val extrasDeferred = CryptoMarkets.liveTickers(extraTickers).map { ticker ->
                async { fetchExtra(ticker) }
            }
            val btcMarkets = btcDeferred.await()?.markets.orEmpty().cryptoOnly()
            val ethMarkets = ethDeferred.await()?.markets.orEmpty().cryptoOnly()
            val solMarkets = solDeferred.await()?.markets.orEmpty().cryptoOnly()
            val seen = (btcMarkets + ethMarkets + solMarkets).map { it.ticker }.toSet()
            val extraMarkets = extrasDeferred.mapNotNull { it.await() }
                .cryptoOnly()
                .filter { it.ticker !in seen }
            val now = System.currentTimeMillis()
            cache.write(btcMarkets, ethMarkets, solMarkets, extraMarkets, now)
            val btcUi = model.annotate(btcMarkets.map { it.toUiModel(SeriesKind.BTC) }, now, edgeThresholdPp)
            val ethUi = model.annotate(ethMarkets.map { it.toUiModel(SeriesKind.ETH) }, now, edgeThresholdPp)
            val solUi = model.annotate(solMarkets.map { it.toUiModel(SeriesKind.SOL) }, now, edgeThresholdPp)
            val extraUi = model.annotate(
                extraMarkets.map { it.toUiModel(CryptoMarkets.kindFor(it.ticker)) },
                now,
                edgeThresholdPp
            )
            logPredictions(btcUi, SeriesKind.BTC, now)
            logPredictions(ethUi, SeriesKind.ETH, now)
            logPredictions(solUi, SeriesKind.SOL, now)
            logPredictions(extraUi, SeriesKind.CRYPTO, now)
            runCatching { scorer.maybeScore(now) }
            refreshScorecard()
            MarketsSnapshot(
                btc = btcUi,
                eth = ethUi,
                sol = solUi,
                extra = extraUi,
                fetchedAtEpochMs = now,
                fromCache = false,
                errorMessage = null,
                rateLimited = false,
                modelScoreCorrect = lastScoreCorrect,
                modelScoreTotal = lastScoreTotal,
                modelMeanBrier = lastMeanBrier,
                avgEdgeWhenRight = lastEdgeRight,
                avgEdgeWhenWrong = lastEdgeWrong
            )
        } catch (e: Exception) {
            val rateLimited = isRateLimited(e)
            val message = when {
                rateLimited -> "Rate limited — backing off"
                else -> e.message ?: "Network error"
            }
            val cached = cache.read()
            if (cached != null) {
                cached.toSnapshot(fromCache = true, errorMessage = message, rateLimited = rateLimited)
            } else {
                MarketsSnapshot(
                    btc = emptyList(),
                    eth = emptyList(),
                    sol = emptyList(),
                    extra = emptyList(),
                    fetchedAtEpochMs = 0L,
                    fromCache = false,
                    errorMessage = message,
                    rateLimited = rateLimited,
                    modelScoreCorrect = lastScoreCorrect,
                    modelScoreTotal = lastScoreTotal,
                    modelMeanBrier = lastMeanBrier,
                    avgEdgeWhenRight = lastEdgeRight,
                    avgEdgeWhenWrong = lastEdgeWrong
                )
            }
        }
    }

    private suspend fun refreshScorecard() {
        val entries = logStore.readAll()
        val fitted = Calibrator.fitEntries(entries)
        lastCalibration = fitted
        onCalibration?.invoke(fitted)
        val summary = logStore.scoreSummary()
        lastScoreCorrect = if (summary.total > 0) summary.correct else null
        lastScoreTotal = if (summary.total > 0) summary.total else null
        lastMeanBrier = if (summary.total > 0) summary.meanBrier else null
        val card = ScorecardMetrics.compute(entries, calibration = fitted)
        lastEdgeRight = card.allTime.avgEdgeWhenRight
        lastEdgeWrong = card.allTime.avgEdgeWhenWrong
        runCatching { onAfterScore?.invoke() }
    }

    private suspend fun fetchExtra(ticker: String): MarketDto? {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val series = CryptoMarkets.inferSeries(ticker)
        return runCatching {
            resolveApi().getMarkets(seriesTicker = series, ticker = ticker, limit = 5)
                .markets.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
                ?.takeIf { CryptoMarkets.isCryptoTicker(it.ticker) }
        }.getOrNull()
    }

    private suspend fun logPredictions(markets: List<MarketUiModel>, series: SeriesKind, now: Long) {
        for (m in markets) {
            if (!CryptoMarkets.isCryptoTicker(m.ticker)) continue
            val yesPct = m.aiYesPercent ?: continue
            val noPct = m.aiNoPercent ?: (100.0 - yesPct)
            val midPct = m.yesProbabilityPercent ?: continue
            runCatching {
                logStore.upsertOpenPrediction(
                    ticker = m.ticker,
                    series = series.ticker.ifBlank { CryptoMarkets.inferSeries(m.ticker) },
                    predictedYes = yesPct / 100.0,
                    predictedNo = noPct / 100.0,
                    marketMid = midPct / 100.0,
                    timestampMs = now,
                    closeTimeMs = m.closeTimeEpochMs,
                    snapshot = SignalSnapshot(
                        predictedSide = com.dirk.kalshiodds.signal.feedback.ForecastUnits.sideFromProbability(yesPct / 100.0),
                        edgePp = m.edgePp,
                        confidence = m.aiConfidence,
                        regime = m.regimeTag,
                        tteBucket = m.tteRegimeLabel,
                        fairValuePp = yesPct,
                        calibrated = m.calibrated,
                        featureDevs = emptyMap(),
                        uncertainty = m.uncertainty,
                        timeToMoveSec = m.timeToMoveSec,
                        midVolPp = m.midVolPp,
                        pFill = m.pFill,
                        mlpYes = m.mlpPp?.div(100.0),
                        cnnYes = m.cnnPp?.div(100.0),
                        gbmYes = m.gbmPp?.div(100.0)
                    )
                )
            }
        }
    }

    private fun isRateLimited(e: Exception): Boolean {
        val code = when (e) {
            is HttpException -> e.code()
            else -> (e.cause as? HttpException)?.code()
        }
        return code == 429 || code == 503
    }

    private fun List<MarketDto>.cryptoOnly(): List<MarketDto> =
        filter { CryptoMarkets.isCryptoTicker(it.ticker) }

    private fun CachedMarketsPayload.toSnapshot(
        fromCache: Boolean,
        errorMessage: String? = null,
        rateLimited: Boolean = false
    ): MarketsSnapshot {
        val now = System.currentTimeMillis()
        return MarketsSnapshot(
            btc = model.annotate(btc.filter { CryptoMarkets.isCryptoTicker(it.ticker) }.map { it.toUiModel(SeriesKind.BTC) }, now),
            eth = model.annotate(eth.filter { CryptoMarkets.isCryptoTicker(it.ticker) }.map { it.toUiModel(SeriesKind.ETH) }, now),
            sol = model.annotate(sol.filter { CryptoMarkets.isCryptoTicker(it.ticker) }.map { it.toUiModel(SeriesKind.SOL) }, now),
            extra = model.annotate(
                extra.filter { CryptoMarkets.isCryptoTicker(it.ticker) }
                    .map { it.toUiModel(CryptoMarkets.kindFor(it.ticker)) },
                now
            ),
            fetchedAtEpochMs = fetchedAtEpochMs,
            fromCache = fromCache,
            errorMessage = errorMessage,
            rateLimited = rateLimited,
            modelScoreCorrect = lastScoreCorrect,
            modelScoreTotal = lastScoreTotal,
            modelMeanBrier = lastMeanBrier,
            avgEdgeWhenRight = lastEdgeRight,
            avgEdgeWhenWrong = lastEdgeWrong
        )
    }
}
