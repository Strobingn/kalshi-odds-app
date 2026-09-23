package com.dirk.kalshiodds.data.repo

import android.content.Context
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketCache
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.toUiModel
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.prediction.SettlementScorer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

data class MarketsSnapshot(
    val btc: List<MarketUiModel>,
    val wti: List<MarketUiModel>,
    val fetchedAtEpochMs: Long,
    val fromCache: Boolean,
    val errorMessage: String? = null,
    /** True when Kalshi returned HTTP 429 or 503 — callers should back off. */
    val rateLimited: Boolean = false,
    /** Scored prediction feedback: correct / total, or null if none yet. */
    val modelScoreCorrect: Int? = null,
    val modelScoreTotal: Int? = null,
    val modelMeanBrier: Double? = null
)

class MarketRepository(
    context: Context,
    private val api: KalshiApi = NetworkModule.api,
    private val cache: MarketCache = MarketCache(context.applicationContext),
    private val model: DipHunterModel = DipHunterModel(context.applicationContext),
    private val logStore: PredictionLogStore = PredictionLogStore(context.applicationContext),
    private val scorer: SettlementScorer = SettlementScorer(api, logStore)
) {

    @Volatile private var lastScoreCorrect: Int? = null
    @Volatile private var lastScoreTotal: Int? = null
    @Volatile private var lastMeanBrier: Double? = null

    val cachedSnapshot: Flow<MarketsSnapshot?> = cache.cachedFlow.map { payload ->
        payload?.toSnapshot(fromCache = true)
    }

    suspend fun refresh(): MarketsSnapshot = coroutineScope {
        try {
            val btcDeferred = async { api.getMarkets(KalshiApi.SERIES_BTC, status = "open") }
            val wtiDeferred = async { api.getMarkets(KalshiApi.SERIES_WTI, status = "open") }
            val btcMarkets = btcDeferred.await().markets
            val wtiMarkets = wtiDeferred.await().markets
            val now = System.currentTimeMillis()
            cache.write(btcMarkets, wtiMarkets, now)
            val btcUi = model.annotate(btcMarkets.map { it.toUiModel(SeriesKind.BTC) }, now)
            val wtiUi = model.annotate(wtiMarkets.map { it.toUiModel(SeriesKind.WTI) }, now)
            logPredictions(btcUi, SeriesKind.BTC, now)
            logPredictions(wtiUi, SeriesKind.WTI, now)
            runCatching { scorer.maybeScore(now) }
            val summary = logStore.scoreSummary()
            lastScoreCorrect = if (summary.total > 0) summary.correct else null
            lastScoreTotal = if (summary.total > 0) summary.total else null
            lastMeanBrier = if (summary.total > 0) summary.meanBrier else null
            MarketsSnapshot(
                btc = btcUi,
                wti = wtiUi,
                fetchedAtEpochMs = now,
                fromCache = false,
                errorMessage = null,
                rateLimited = false,
                modelScoreCorrect = lastScoreCorrect,
                modelScoreTotal = lastScoreTotal,
                modelMeanBrier = lastMeanBrier
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
                    wti = emptyList(),
                    fetchedAtEpochMs = 0L,
                    fromCache = false,
                    errorMessage = message,
                    rateLimited = rateLimited,
                    modelScoreCorrect = lastScoreCorrect,
                    modelScoreTotal = lastScoreTotal,
                    modelMeanBrier = lastMeanBrier
                )
            }
        }
    }

    private suspend fun logPredictions(markets: List<MarketUiModel>, series: SeriesKind, now: Long) {
        for (m in markets) {
            val yesPct = m.aiYesPercent ?: continue
            val noPct = m.aiNoPercent ?: (100.0 - yesPct)
            val midPct = m.yesProbabilityPercent ?: continue
            runCatching {
                logStore.upsertOpenPrediction(
                    ticker = m.ticker,
                    series = series.ticker,
                    predictedYes = yesPct / 100.0,
                    predictedNo = noPct / 100.0,
                    marketMid = midPct / 100.0,
                    timestampMs = now,
                    closeTimeMs = m.closeTimeEpochMs
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

    private fun CachedMarketsPayload.toSnapshot(
        fromCache: Boolean,
        errorMessage: String? = null,
        rateLimited: Boolean = false
    ): MarketsSnapshot {
        val now = System.currentTimeMillis()
        return MarketsSnapshot(
            btc = model.annotate(btc.map { it.toUiModel(SeriesKind.BTC) }, now),
            wti = model.annotate(wti.map { it.toUiModel(SeriesKind.WTI) }, now),
            fetchedAtEpochMs = fetchedAtEpochMs,
            fromCache = fromCache,
            errorMessage = errorMessage,
            rateLimited = rateLimited,
            modelScoreCorrect = lastScoreCorrect,
            modelScoreTotal = lastScoreTotal,
            modelMeanBrier = lastMeanBrier
        )
    }
}
