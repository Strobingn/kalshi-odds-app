package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.api.KalshiHttpGate
import com.dirk.kalshiodds.data.api.KalshiTokenBucket
import com.dirk.kalshiodds.data.api.StreamJsonConverterFactory
import com.dirk.kalshiodds.data.dto.MarketsResponse
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpMemory
import com.dirk.kalshiodds.decision.ScalpRule
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0352Test {
    private fun src(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }.readText()

    private fun body(kb: Int) = "{\"markets\":[" + (0 until kb).joinToString(",") { "{\"ticker\":\"KXBTC15M-26OCT10${it}-00\",\"rules_primary\":\"${"x".repeat(900)}\"}" } + "],\"cursor\":\"\"}"

    /**
     * SOAK (6 simulated hours): 0.3.50 kept every distinct GET URL's full body in the gate cache forever (expired entries
     * were only removed when the same URL was read again). 1 req/s of changing URLs × ~100 KB = GBs. Now bounded.
     */
    @Test fun sixHourPollingSoakKeepsGateCacheAndHeapFlat() {
        var now = 1_000_000L
        val bucket = KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 })
        val gate = KalshiHttpGate(bucket, nowMs = { now }, sleep = { ms -> now += ms })
        val payload = body(100) // ~100 KB like a settled/markets page
        val client = OkHttpClient.Builder().addInterceptor(gate).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val rt = Runtime.getRuntime()
        fun used(): Long { System.gc(); Thread.sleep(50); return rt.totalMemory() - rt.freeMemory() }
        var baseline = 0L
        var maxBytes = 0L
        val seconds = 6 * 3600
        for (i in 0 until seconds) {
            now += 1_000L
            val url = "https://api.elections.kalshi.com/trade-api/v2/markets/trades?ticker=KXBTC15M-26OCT10${i / 900}-00&min_ts=${now / 1000}"
            client.newCall(Request.Builder().url(url).build()).execute().close()
            if (i % 2 == 0) client.newCall(Request.Builder().url("https://api.elections.kalshi.com/trade-api/v2/markets?series_ticker=KXETH15M&status=open&limit=10&cursor=c$i").build()).execute().close()
            maxBytes = maxOf(maxBytes, gate.cacheBytes())
            if (i == 600) baseline = used()
        }
        val end = used()
        assertTrue("cache entries ${gate.cacheEntries()}", gate.cacheEntries() <= KalshiHttpGate.MAX_CACHE_ENTRIES)
        assertTrue("cache bytes $maxBytes", maxBytes <= KalshiHttpGate.MAX_CACHE_BYTES)
        // 32 400 bodies × 100 KB would be ~3 GB if leaked; heap must stay within a few MB of the 10-minute baseline.
        assertTrue("heap grew ${(end - baseline) shr 20} MB", end - baseline < 48L * 1024 * 1024)
    }

    @Test fun scalpLedgerFlatOverSixHoursOfWindows() {
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val t0 = 1_791_000_000_000L
        var n = 0
        for (s in 0 until 6 * 3600 step 2) {
            val now = t0 + s * 1000L
            val close = now - (now % 900_000L) + 900_000L
            for (coin in listOf("BTC", "ETH", "SOL")) {
                val mid = 0.5 + 0.3 * kotlin.math.sin((s + coin.hashCode()) / 37.0)
                book.onQuote(ScalpRule.Quote("KX${coin}15M-W${close / 900_000L}-00", now, close, now - 50, mid - 0.01, 50.0, mid + 0.01, 50.0, 100.0, 100.0, 0.0005), true)
                n++
            }
        }
        assertTrue(book.allTrades().size <= (ScalpMemory.MAX_CLOSED + ScalpMemory.MAX_NO_FILL) * 105 / 100 + 200)
        assertTrue(book.marks.value.size <= ScalpMemory.MAX_TICKERS + 3)
        assertTrue(n > 30_000)
    }

    @Test fun streamConverterDecodes() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val f = StreamJsonConverterFactory(json, "application/json".toMediaType())
        val conv = f.responseBodyConverter(MarketsResponse::class.java, emptyArray(), retrofit2.Retrofit.Builder().baseUrl("https://x.kalshi.com/").build())!!
        val r = conv.convert(body(3).toResponseBody("application/json".toMediaType())) as MarketsResponse
        assertEquals(3, r.markets.size)
    }

    @Test fun leanFetchesAndSafetyMargin() {
        val repo = src("java/com/dirk/kalshiodds/data/repo/MarketRepository.kt")
        assertEquals(4, Regex("limit = OPEN_15M_LIMIT").findAll(repo).count())
        val net = src("java/com/dirk/kalshiodds/data/api/NetworkModule.kt")
        assertTrue(net.contains("StreamJsonConverterFactory"))
        assertTrue(!net.contains("asConverterFactory("))
        assertTrue(src("AndroidManifest.xml").contains("android:largeHeap=\"true\""))
        assertTrue(src("java/com/dirk/kalshiodds/crash/CrashLog.kt").contains("HeapInfo.line()"))
        assertTrue(src("java/com/dirk/kalshiodds/ui/DataScreen.kt").contains("HeapInfo.line()"))
    }
}
