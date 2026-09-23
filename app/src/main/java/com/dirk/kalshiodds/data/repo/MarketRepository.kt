package com.dirk.kalshiodds.data.repo

import android.content.Context
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketCache
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.toUiModel
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
    val rateLimited: Boolean = false
)

class MarketRepository(
    context: Context,
    private val api: KalshiApi = NetworkModule.api,
    private val cache: MarketCache = MarketCache(context.applicationContext)
) {

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
            MarketsSnapshot(
                btc = btcMarkets.map { it.toUiModel(SeriesKind.BTC) },
                wti = wtiMarkets.map { it.toUiModel(SeriesKind.WTI) },
                fetchedAtEpochMs = now,
                fromCache = false,
                errorMessage = null,
                rateLimited = false
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
                    rateLimited = rateLimited
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
    ): MarketsSnapshot = MarketsSnapshot(
        btc = btc.map { it.toUiModel(SeriesKind.BTC) },
        wti = wti.map { it.toUiModel(SeriesKind.WTI) },
        fetchedAtEpochMs = fetchedAtEpochMs,
        fromCache = fromCache,
        errorMessage = errorMessage,
        rateLimited = rateLimited
    )
}
