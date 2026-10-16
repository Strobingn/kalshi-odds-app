package com.dirk.kalshiodds.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.KalshiRequestStatus
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.data.dto.SeriesResponse
import com.dirk.kalshiodds.data.dto.TradesResponse
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketSnapshotCache
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.prediction.PredictionLogStore
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineCacheRefreshTest {

    @Test
    fun coldStartNetworkFailureShowsCachedMarketsOffline() = runBlocking {
        val cache = MemoryCache()
        cache.write(listOf(btc("KXBTC15M-26SEP251530-30")), emptyList(), emptyList(), emptyList(), 1_700_000_000_000L)
        val repo = repo(cache, object : ThrowingApi() {
            override suspend fun getMarkets(
                seriesTicker: String,
                status: String,
                limit: Int?,
                cursor: String?,
                ticker: String?,
                eventTicker: String?
            ): MarketsResponse = throw IOException("network down")
        })
        val snap = repo.refresh()
        assertEquals(KalshiRequestStatus.OFFLINE, snap.errorMessage)
        assertTrue(snap.fromCache)
        assertEquals("KXBTC15M-26SEP251530-30", snap.btc.single().ticker)
        assertEquals(0.34, snap.btc.single().yesAsk!!, 1e-9)
        assertFalse(snap.allMarkets.isEmpty())
    }

    @Test
    fun emptySuccessfulFetchDoesNotWipeCachedBitcoin() = runBlocking {
        val cache = MemoryCache()
        cache.write(listOf(btc("KXBTC15M-CACHED")), emptyList(), emptyList(), emptyList(), 1_700_000_000_000L)
        val seen = mutableListOf<String>()
        val repo = repo(cache, object : ThrowingApi() {
            override suspend fun getMarkets(
                seriesTicker: String,
                status: String,
                limit: Int?,
                cursor: String?,
                ticker: String?,
                eventTicker: String?
            ): MarketsResponse {
                seen += seriesTicker
                return MarketsResponse(markets = emptyList())
            }
        })
        val snap = repo.refresh()
        assertTrue(seen.contains(KalshiApi.SERIES_BTC))
        assertEquals("KXBTC15M-CACHED", snap.btc.single().ticker)
        assertEquals("KXBTC15M-CACHED", cache.read()!!.btc.single().ticker)
    }

    private fun repo(cache: MemoryCache, api: KalshiApi): MarketRepository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return MarketRepository(
            context = ctx,
            resolveApi = { api },
            cache = cache,
            model = DipHunterModel(null),
            logStore = PredictionLogStore(ctx)
        )
    }

    private fun btc(ticker: String) = MarketDto(
        ticker = ticker,
        title = "Bitcoin",
        yesBidDollars = "0.33",
        yesAskDollars = "0.34",
        noBidDollars = "0.66",
        noAskDollars = "0.67",
        lastPriceDollars = "0.34",
        volumeFp = "1000",
        status = "active"
    )

    private open class ThrowingApi : KalshiApi {
        override suspend fun getMarkets(
            seriesTicker: String,
            status: String,
            limit: Int?,
            cursor: String?,
            ticker: String?,
            eventTicker: String?
        ): MarketsResponse = throw IOException("offline")

        override suspend fun getSeries(seriesTicker: String): SeriesResponse = SeriesResponse()

        override suspend fun getTrades(
            ticker: String,
            limit: Int?,
            minTs: Long?,
            cursor: String?
        ): TradesResponse = TradesResponse()
    }

    private class MemoryCache : MarketSnapshotCache {
        private val state = MutableStateFlow<CachedMarketsPayload?>(null)
        override val cachedFlow = state
        override suspend fun read() = state.value
        override suspend fun write(
            btc: List<MarketDto>,
            eth: List<MarketDto>,
            sol: List<MarketDto>,
            extra: List<MarketDto>,
            fetchedAtEpochMs: Long
        ) {
            state.value = CachedMarketsPayload(btc, eth, sol, extra, fetchedAtEpochMs)
        }
    }
}
