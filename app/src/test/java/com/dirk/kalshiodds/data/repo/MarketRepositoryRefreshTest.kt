package com.dirk.kalshiodds.data.repo

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketSnapshotCache
import com.dirk.kalshiodds.prediction.DipHunterModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class MarketRepositoryRefreshTest {
    @Test
    fun http429ReturnsCachedMarketsInsteadOfThrowing() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                fetchedAtEpochMs = 50L
            )
        )
        val api = ThrowingApi(http(429))
        val snap = repo(api, cache).refresh(watchBtc = true, watchEth = true, watchSol = true)
        assertTrue(snap.rateLimited)
        assertTrue(snap.fromCache)
        assertEquals("Rate limited — backing off", snap.errorMessage)
        assertEquals(listOf("KXBTC15M-CACHED"), snap.btc.map { it.ticker })
        assertTrue(api.series.none { it == KalshiApi.SERIES_ETH || it == KalshiApi.SERIES_SOL })
    }

    @Test
    fun offlineWithNoCacheReturnsAnErrorSnapshot() = runBlocking {
        val api = ThrowingApi(IOException("offline"))
        val snap = repo(api, MemoryCache(null)).refresh()
        assertFalse(snap.rateLimited)
        assertFalse(snap.fromCache)
        assertEquals("offline", snap.errorMessage)
        assertTrue(snap.btc.isEmpty())
    }

    private fun repo(api: KalshiApi, cache: MarketSnapshotCache): MarketRepository {
        val context = RuntimeEnvironment.getApplication()
        return MarketRepository(
            context = context,
            api = api,
            resolveApi = { api },
            cache = cache,
            model = DipHunterModel()
        )
    }

    private fun http(code: Int): HttpException = HttpException(
        Response.error<MarketsResponse>(code, "limited".toResponseBody("text/plain".toMediaType()))
    )

    private class ThrowingApi(private val error: Exception) : KalshiApi {
        val series = mutableListOf<String>()
        override suspend fun getMarkets(
            seriesTicker: String,
            status: String,
            limit: Int?,
            cursor: String?,
            ticker: String?
        ): MarketsResponse {
            series += seriesTicker
            throw error
        }
    }

    private class MemoryCache(initial: CachedMarketsPayload?) : MarketSnapshotCache {
        private var payload = initial
        override val cachedFlow = MutableStateFlow(payload)
        override suspend fun read(): CachedMarketsPayload? = payload
        override suspend fun write(
            btc: List<MarketDto>,
            eth: List<MarketDto>,
            sol: List<MarketDto>,
            extra: List<MarketDto>,
            fetchedAtEpochMs: Long
        ) {
            payload = CachedMarketsPayload(btc, eth, sol, extra, fetchedAtEpochMs)
            cachedFlow.value = payload
        }
    }
}
