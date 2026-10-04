package com.dirk.kalshiodds.data.api

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 15-minute foreground phone session (no websocket, API key present,
 * two resting D3 bids, 12 unsettled KXBTC15M tickets) counted against a
 * fake clock and a fake HTTP stack.
 *
 * Before: home refresh every 750ms also pulled positions and balance,
 * settlement issued one GET per ticker, D3 trades polled every 5s, and
 * rollover issued its own market-list GET.
 * After: those callers share [KalshiHttpGate].
 */
class KalshiSessionTrafficTest {

    @Test
    fun fifteenMinuteSessionStaysUnderEightReadsPerSecond() {
        val before = countLegacy()
        val after = countGated()
        val report = """
            Kalshi REST in a 15-minute foreground session
            before: ${before.calls} calls, ${"%.3f".format(before.perSec)} req/s, peak ${before.peakPerSec}/s
            after:  ${after.calls} calls, ${"%.3f".format(after.perSec)} req/s, peak ${after.peakPerSec}/s
            main offenders before: home market list every 750ms (${before.byCaller["home"]}), positions+balance on that same cadence (${before.byCaller["positions"]}+${before.byCaller["balance"]}), per-ticker settlement (${before.byCaller["settlement"]}), D3 trades every 5s (${before.byCaller["d3_trades"]}), duplicate rollover market list (${before.byCaller["rollover"]})
        """.trimIndent()
        val dir = File("/opt/cursor/artifacts")
        if (dir.mkdirs() || dir.isDirectory) {
            File(dir, "kalshi_rate_report.txt").writeText(report + "\n")
        }
        assertTrue(report, after.calls < before.calls / 2)
        assertTrue(report, after.perSec <= 8.0)
        assertTrue(report, after.peakPerSec <= KalshiPollBudget.READ_BURST.toInt() + 4)
        assertTrue(report, before.perSec > after.perSec)
        assertTrue(before.byCaller.getValue("settlement") > after.byCaller.getValue("settlement") * 5)
    }

    @Test
    fun read429IsNotRetriedInATightLoop() {
        var now = 0L
        val hits = AtomicInteger()
        val bucket = KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 })
        val gate = KalshiHttpGate(bucket, nowMs = { now }, sleep = { ms -> now += ms })
        val client = client(gate) {
            hits.incrementAndGet()
            response(it, 429, """{"error":{"code":"too_many_requests"}}""", "5")
        }
        val first = client.newCall(get(MARKETS)).execute()
        assertEquals(429, first.code)
        first.close()
        val second = client.newCall(get(MARKETS)).execute()
        assertEquals(429, second.code)
        second.close()
        assertEquals("second GET must not hit the network during Retry-After", 1, hits.get())
        assertTrue(bucket.readHoldRemainingMs(now) > 1_000L)
    }

    @Test
    fun identicalGetsShareOneCallAndAShortCache() {
        var now = 0L
        val hits = AtomicInteger()
        val gate = KalshiHttpGate(
            KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 }),
            nowMs = { now },
            sleep = { ms -> now += ms }
        )
        val client = client(gate) {
            hits.incrementAndGet()
            response(it, 200, """{"markets":[]}""")
        }
        client.newCall(get(MARKETS)).execute().close()
        client.newCall(get(MARKETS)).execute().close()
        assertEquals(1, hits.get())
        now += KalshiPollBudget.GET_CACHE_MS + 1
        client.newCall(get(MARKETS)).execute().close()
        assertEquals(2, hits.get())
    }

    @Test
    fun successfulOrderOrCancelClearsTheGetCache() {
        var now = 0L
        val hits = AtomicInteger()
        val gate = KalshiHttpGate(
            KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 }),
            nowMs = { now },
            sleep = { ms -> now += ms }
        )
        val client = client(gate) {
            hits.incrementAndGet()
            response(it, 200, """{"order_id":"x"}""")
        }
        client.newCall(get(MARKETS)).execute().close()
        client.newCall(get(MARKETS)).execute().close()
        assertEquals(1, hits.get())
        val post = Request.Builder().url(ORDER).post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(post).execute().close()
        client.newCall(get(MARKETS)).execute().close()
        assertEquals(3, hits.get())
        val delete = Request.Builder().url(ORDER).delete().build()
        client.newCall(delete).execute().close()
        client.newCall(get(MARKETS)).execute().close()
        assertEquals(5, hits.get())
    }

    @Test
    fun writeDeniedDoesNotHitNetworkAndNamesApprove() {
        var now = 0L
        val hits = AtomicInteger()
        val bucket = KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 })
        val gate = KalshiHttpGate(bucket, nowMs = { now }, sleep = { ms -> now += ms })
        val client = client(gate) {
            hits.incrementAndGet()
            response(it, 200, """{"order_id":"x"}""")
        }
        val post = Request.Builder().url(ORDER).post("{}".toRequestBody("application/json".toMediaType())).build()
        repeat(KalshiPollBudget.WRITE_BURST.toInt()) {
            client.newCall(post).execute().close()
        }
        val sent = hits.get()
        val denied = client.newCall(post).execute()
        val body = denied.body?.string().orEmpty()
        assertEquals(429, denied.code)
        denied.close()
        assertEquals("rate-limited real order must not be sent", sent, hits.get())
        assertTrue(body.contains("Approve"))
        assertTrue(body.contains("too_many_requests"))
    }

    private fun countLegacy(): Report {
        val events = schedule(legacy = true)
        val times = events.map { it.atMs }
        return report(events.size, times, events)
    }

    private fun countGated(): Report {
        var now = 0L
        val hits = ArrayList<Long>()
        val byCaller = HashMap<String, Int>()
        val gate = KalshiHttpGate(
            KalshiTokenBucket(nowMs = { now }, randomUnit = { 0.0 }),
            nowMs = { now },
            sleep = { ms -> now += ms }
        )
        val client = client(gate) { req ->
            hits += now
            val caller = callerOf(req.url.toString())
            byCaller[caller] = (byCaller[caller] ?: 0) + 1
            response(req, 200, """{"markets":[],"orders":[]}""")
        }
        for (event in schedule(legacy = false)) {
            if (now < event.atMs) now = event.atMs
            client.newCall(get(event.url)).execute().close()
        }
        return Report(hits.size, hits.size / 900.0, peak(hits), byCaller)
    }

    private fun report(calls: Int, times: List<Long>, events: List<Event>): Report {
        val by = HashMap<String, Int>()
        events.forEach { by[it.caller] = (by[it.caller] ?: 0) + 1 }
        return Report(calls, calls / 900.0, peak(times), by)
    }

    private fun peak(times: List<Long>): Int {
        if (times.isEmpty()) return 0
        val buckets = HashMap<Long, Int>()
        times.forEach { t ->
            val key = t / 1_000L
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets.values.maxOrNull() ?: 0
    }

    private fun schedule(legacy: Boolean): List<Event> {
        val duration = 900_000L
        val out = ArrayList<Event>()
        fun every(interval: Long, caller: String, url: String, copies: Int = 1) {
            var t = 0L
            while (t < duration) {
                repeat(copies) { i ->
                    val distinct = if (copies == 1) url else "$url&i=$i"
                    out += Event(t, caller, distinct)
                }
                t += interval
            }
        }
        every(if (legacy) 750L else KalshiPollBudget.HOME_VISIBLE_MS, "home", MARKETS)
        every(if (legacy) 750L else KalshiPollBudget.POSITIONS_MS, "positions", POSITIONS)
        every(if (legacy) 750L else KalshiPollBudget.POSITIONS_MS, "balance", BALANCE)
        every(
            if (legacy) 15_000L else KalshiPollBudget.SETTLEMENT_MS,
            "settlement",
            SETTLED,
            copies = if (legacy) 12 else 1
        )
        every(15_000L, "d3_quotes", D3_PAGE_1)
        every(15_000L, "d3_quotes", D3_PAGE_2)
        every(if (legacy) 5_000L else KalshiPollBudget.D3_TRADES_MS, "d3_trades", TRADE_1)
        every(if (legacy) 5_000L else KalshiPollBudget.D3_TRADES_MS, "d3_trades", TRADE_2)
        every(2_500L, "rollover", MARKETS)
        out += Event(0L, "chart", CANDLE)
        out += Event(0L, "d3_schedule", SERIES)
        out += Event(840_000L, "worker", MARKETS)
        return out.sortedWith(compareBy({ it.atMs }, { it.caller }))
    }

    private fun callerOf(url: String): String = when {
        url.contains("candlesticks") -> "chart"
        url.contains("/series/KXBTCD") -> "d3_schedule"
        url.contains("trades") -> "d3_trades"
        url.contains("KXBTCD") -> "d3_quotes"
        url.contains("status=settled") -> "settlement"
        url.contains("positions") -> "positions"
        url.contains("balance") -> "balance"
        else -> "home"
    }

    private fun client(gate: KalshiHttpGate, network: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(gate)
            .addInterceptor { chain -> network(chain.request()) }
            .build()

    private fun get(url: String): Request = Request.Builder().url(url).build()

    private fun response(request: Request, code: Int, json: String, retryAfter: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Too Many Requests")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private data class Event(val atMs: Long, val caller: String, val url: String)
    private data class Report(
        val calls: Int,
        val perSec: Double,
        val peakPerSec: Int,
        val byCaller: Map<String, Int>
    )

    companion object {
        private const val MARKETS =
            "https://external-api.kalshi.com/trade-api/v2/markets?series_ticker=KXBTC15M&status=open"
        private const val POSITIONS =
            "https://external-api.kalshi.com/trade-api/v2/portfolio/positions"
        private const val BALANCE =
            "https://external-api.kalshi.com/trade-api/v2/portfolio/balance"
        private const val SETTLED =
            "https://external-api.kalshi.com/trade-api/v2/markets?series_ticker=KXBTC15M&status=settled&limit=200"
        private const val D3_PAGE_1 =
            "https://external-api.kalshi.com/trade-api/v2/markets?series_ticker=KXBTCD&status=open&limit=200"
        private const val D3_PAGE_2 =
            "https://external-api.kalshi.com/trade-api/v2/markets?series_ticker=KXBTCD&status=open&limit=200&cursor=p2"
        private const val TRADE_1 =
            "https://external-api.kalshi.com/trade-api/v2/markets/trades?ticker=KXBTCD-1"
        private const val TRADE_2 =
            "https://external-api.kalshi.com/trade-api/v2/markets/trades?ticker=KXBTCD-2"
        private const val CANDLE =
            "https://api.elections.kalshi.com/trade-api/v2/series/KXBTC15M/markets/KXBTC15M-T/candlesticks?period_interval=1"
        private const val SERIES =
            "https://api.elections.kalshi.com/trade-api/v2/series/KXBTCD"
        private const val ORDER =
            "https://external-api.kalshi.com/trade-api/v2/portfolio/events/orders"
    }
}
