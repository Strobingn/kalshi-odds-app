package com.dirk.kalshiodds.data.api

import java.io.IOException
import java.net.UnknownHostException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class KalshiResilienceTest {
    @Test
    fun offlineAndRateLimitMessagesDoNotThrow() {
        assertEquals(KalshiRequestStatus.OFFLINE, KalshiRequestStatus.message(UnknownHostException("no dns"), 0L))
        assertEquals(KalshiRequestStatus.OFFLINE, KalshiRequestStatus.message(IOException("timeout"), 4_000L))
        val limited = http(429, "8")
        assertEquals("Rate-limited, retrying in 8s", KalshiRequestStatus.message(limited, 8_000L))
        assertEquals(8_000L, KalshiRequestStatus.retryAfterMs(limited))
        val server = http(503, null)
        assertEquals("Kalshi unavailable, retrying in 2s", KalshiRequestStatus.message(server, 1_200L))
        assertTrue(KalshiRequestStatus.shouldBackoff(limited))
        assertTrue(KalshiRequestStatus.shouldBackoff(IOException("down")))
        assertTrue(KalshiRequestStatus.shouldBackoff(server))
    }

    @Test
    fun backoffHonorsRetryAfterAndAddsJitter() {
        val limiter = KalshiRateLimiter(nowMs = { 1_000L }, randomUnit = { 0.0 })
        assertEquals(1_000L, limiter.backoffDelayMs(0, null, 0.0))
        assertEquals(1_200L, limiter.backoffDelayMs(0, null, 1.0))
        assertEquals(12_000L, limiter.backoffDelayMs(0, 12_000L, 0.0))
        assertTrue(limiter.backoffDelayMs(3, null, 0.0) > limiter.backoffDelayMs(1, null, 0.0))
        val waited = limiter.onFailure(429, 5_000L)
        assertTrue(waited >= 5_000L)
        assertEquals(waited, limiter.remainingHoldMs(1_000L))
        val d3Wait = limiter.reserve(KalshiRateLimiter.Lane.D3)
        val scorecardWait = limiter.reserve(KalshiRateLimiter.Lane.SCORECARD)
        assertTrue("D3 must wait on the shared hold", d3Wait >= 5_000L)
        assertTrue("scorecard must wait on the same hold", scorecardWait >= 5_000L)
    }

    @Test
    fun refreshTapsDoNotStack() {
        var now = 10_000L
        val gate = RefreshGate(debounceMs = 750L, nowMs = { now })
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertFalse("debounce window", gate.tryAcquire())
        now += 800L
        assertTrue(gate.tryAcquire())
        assertTrue(gate.inFlight())
    }

    private fun http(code: Int, retryAfter: String?): HttpException {
        val body = "no".toResponseBody()
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://external-api.kalshi.com/trade-api/v2/markets").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("err")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body)
            .build()
        return HttpException(retrofit2.Response.error<Any>(body, raw))
    }
}
