package com.dirk.kalshiodds.data.repo

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RefreshRecoveryTest {
    @Test
    fun rateLimitBackoffStartsAtTwoSecondsThenDoubles() {
        val first = RefreshRecovery.nextIntervalMs(
            currentMs = 750L,
            rateLimited = true,
            recovered = false,
            wsLive = false,
            baseMs = 750L,
            initialBackoffMs = 2_000L,
            maxBackoffMs = 60_000L,
            wsMetadataMs = 15_000L
        )
        assertEquals(2_000L, first)
        val second = RefreshRecovery.nextIntervalMs(
            currentMs = first,
            rateLimited = true,
            recovered = false,
            wsLive = false,
            baseMs = 750L,
            initialBackoffMs = 2_000L,
            maxBackoffMs = 60_000L,
            wsMetadataMs = 15_000L
        )
        assertEquals(4_000L, second)
    }

    @Test
    fun wrappedHttp429IsRateLimitedAndNamesTheBackoff() {
        val http = HttpException(
            Response.error<String>(429, "slow".toResponseBody("text/plain".toMediaType()))
        )
        val wrapped = RuntimeException("refresh failed", http)
        assertTrue(RefreshRecovery.isRateLimited(wrapped))
        assertEquals("Rate limited — backing off", RefreshRecovery.messageFor(wrapped))
        assertFalse(RefreshRecovery.isRateLimited(IllegalStateException("offline")))
    }

    @Test
    fun retryAfterSecondsIsTheWaitAndBackoffIsTheFloor() {
        val waited = RefreshRecovery.nextIntervalMs(
            currentMs = 750L,
            rateLimited = true,
            recovered = false,
            wsLive = false,
            baseMs = 750L,
            initialBackoffMs = 2_000L,
            maxBackoffMs = 60_000L,
            wsMetadataMs = 15_000L,
            retryAfterMs = 30_000L
        )
        assertEquals(30_000L, waited)
        val floored = RefreshRecovery.nextIntervalMs(
            currentMs = 750L,
            rateLimited = true,
            recovered = false,
            wsLive = false,
            baseMs = 750L,
            initialBackoffMs = 2_000L,
            maxBackoffMs = 60_000L,
            wsMetadataMs = 15_000L,
            retryAfterMs = 1_000L
        )
        assertEquals(2_000L, floored)
        val header = http429("30")
        assertEquals(30_000L, RefreshRecovery.retryAfterMs(RuntimeException("refresh failed", header)))
    }

    @Test
    fun retryAfterHttpDateIsHonored() {
        val now = 1_700_000_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(now + 30_000L).atZone(ZoneOffset.UTC)
        )
        assertEquals(30_000L, RefreshRecovery.parseRetryAfterHeader(date, now))
        assertEquals(30_000L, RefreshRecovery.retryAfterMs(http429(date), now))
        assertNull(RefreshRecovery.parseRetryAfterHeader("not-a-date", now))
    }

    private fun http429(header: String): HttpException {
        val body = "slow".toResponseBody("text/plain".toMediaType())
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.com/markets").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", header)
            .body(body)
            .build()
        return HttpException(Response.error<String>("slow".toResponseBody("text/plain".toMediaType()), raw))
    }
}
