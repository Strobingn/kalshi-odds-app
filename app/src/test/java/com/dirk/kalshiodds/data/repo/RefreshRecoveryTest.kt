package com.dirk.kalshiodds.data.repo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
