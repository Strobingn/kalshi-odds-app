package com.dirk.kalshiodds.data.repo

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.data.local.CachedMarketsPayload
import com.dirk.kalshiodds.data.local.MarketSnapshotCache
import com.dirk.kalshiodds.prediction.DipHunterModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun emptyFetchDoesNotOverwriteAGoodCache() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                fetchedAtEpochMs = 50L
            )
        )
        val snap = repo(EmptyApi(), cache).refresh(watchBtc = true, watchEth = false, watchSol = false)
        assertEquals(listOf("KXBTC15M-CACHED"), snap.btc.map { it.ticker })
        assertTrue(snap.fromCache)
        assertEquals(listOf("KXBTC15M-CACHED"), cache.read()?.btc?.map { it.ticker })
    }

    @Test
    fun turningBitcoinOffLeavesTheCachedBoardOnDisk() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                fetchedAtEpochMs = 50L
            )
        )
        val api = EmptyApi()
        val snap = repo(api, cache).refresh(watchBtc = false, watchEth = false, watchSol = false)
        assertTrue(snap.btc.isEmpty())
        assertEquals(0, api.calls)
        assertEquals(listOf("KXBTC15M-CACHED"), cache.read()?.btc?.map { it.ticker })
    }

    @Test
    fun childFetchFailureStillReturnsTheDiskCache() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                fetchedAtEpochMs = 50L
            )
        )
        val snap = repo(ThrowingApi(IOException("offline")), cache).refresh(watchBtc = true)
        assertTrue(snap.fromCache)
        assertEquals("offline", snap.errorMessage)
        assertEquals(listOf("KXBTC15M-CACHED"), snap.btc.map { it.ticker })
    }

    @Test
    fun rateLimitKeepsRetryAfterOnTheCachedSnapshot() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                fetchedAtEpochMs = 50L
            )
        )
        val body = "slow".toResponseBody("text/plain".toMediaType())
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.com/markets").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "30")
            .body(body)
            .build()
        val snap = repo(
            ThrowingApi(HttpException(Response.error<MarketsResponse>("slow".toResponseBody("text/plain".toMediaType()), raw))),
            cache
        ).refresh(watchBtc = true)
        assertEquals(30_000L, snap.retryAfterMs)
        assertEquals(listOf("KXBTC15M-CACHED"), snap.btc.map { it.ticker })
    }

    @Test
    fun offlineWithWatchBitcoinOffDoesNotShowTheCachedBoard() = runBlocking {
        val cache = FailWriteCache(cachedPayload(), IOException("offline"))
        val snap = repo(EmptyApi(), cache).refresh(watchBtc = false)
        assertEquals("offline", snap.errorMessage)
        assertTrue(snap.btc.isEmpty())
        assertEquals(listOf("KXBTC15M-CACHED"), cache.read()?.btc?.map { it.ticker })
    }

    @Test
    fun rateLimitedWithWatchBitcoinOffDoesNotShowTheCachedBoard() = runBlocking {
        val cache = FailWriteCache(cachedPayload(), http(429))
        val snap = repo(EmptyApi(), cache).refresh(watchBtc = false)
        assertTrue(snap.rateLimited)
        assertTrue(snap.btc.isEmpty())
        assertEquals(listOf("KXBTC15M-CACHED"), cache.read()?.btc?.map { it.ticker })
    }

    @Test
    fun coldStartCacheStaysHiddenWhenWatchBitcoinIsOff() = runBlocking {
        val cache = cachedBoard()
        val snap = repo(EmptyApi(), cache).cachedBoard(watchBtc = false).first()
        assertTrue(snap!!.btc.isEmpty())
        assertTrue(snap.fromCache)
        assertEquals(listOf("KXBTC15M-CACHED"), cache.read()?.btc?.map { it.ticker })
        val shown = repo(EmptyApi(), cache).cachedBoard(watchBtc = true).first()
        assertEquals(listOf("KXBTC15M-CACHED"), shown!!.btc.map { it.ticker })
    }

    @Test
    fun loadPurgesEthAndSolFromTheCache() = runBlocking {
        val cache = MemoryCache(
            CachedMarketsPayload(
                btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
                eth = listOf(MarketDto(ticker = "KXETH15M-OLD", status = "active", yesAskDollars = "0.5000")),
                sol = listOf(MarketDto(ticker = "KXSOL15M-OLD", status = "active", yesAskDollars = "0.5000")),
                fetchedAtEpochMs = 50L
            )
        )
        val snap = repo(EmptyApi(), cache).refresh(watchBtc = true)
        assertEquals(listOf("KXBTC15M-CACHED"), snap.btc.map { it.ticker })
        assertTrue(snap.eth.isEmpty())
        assertTrue(snap.sol.isEmpty())
        val stored = cache.read()!!
        assertTrue(stored.eth.isEmpty())
        assertTrue(stored.sol.isEmpty())
        assertEquals(listOf("KXBTC15M-CACHED"), stored.btc.map { it.ticker })
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

    private fun cachedPayload() = CachedMarketsPayload(
        btc = listOf(MarketDto(ticker = "KXBTC15M-CACHED", status = "active", yesAskDollars = "0.4000")),
        fetchedAtEpochMs = 50L
    )

    private fun cachedBoard() = MemoryCache(cachedPayload())

    private class FailWriteCache(
        initial: CachedMarketsPayload?,
        private val error: Exception
    ) : MarketSnapshotCache {
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
            throw error
        }
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

    private class EmptyApi : KalshiApi {
        var calls: Int = 0
        override suspend fun getMarkets(
            seriesTicker: String,
            status: String,
            limit: Int?,
            cursor: String?,
            ticker: String?
        ): MarketsResponse {
            calls += 1
            return MarketsResponse()
        }
    }

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
