package com.dirk.kalshiodds.data.backfill

/**
 * Tiny GET transport so backfill pagination can be unit-tested
 * against a mock without standing up Retrofit.
 */
fun interface HistoryTransport {
    /**
     * @return response body, or null on 404. Throws [RateLimited] / [HttpFail].
     */
    fun get(path: String, query: Map<String, String>): String?

    class RateLimited(val retryAfterMs: Long = 2_000L) : RuntimeException("rate limited")
    class HttpFail(val code: Int, override val message: String) : RuntimeException(message)
    class Cancelled : RuntimeException("cancelled")
}
