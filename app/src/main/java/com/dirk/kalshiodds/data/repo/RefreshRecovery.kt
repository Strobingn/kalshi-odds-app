package com.dirk.kalshiodds.data.repo

import kotlin.math.max
import kotlin.math.min
import retrofit2.HttpException

/**
 * Turns a failed market refresh into a cached snapshot plus a poll delay.
 * HTTP 429/503 must lengthen the interval; other failures keep the last delay
 * and still surface a message so the spinner can stop.
 */
object RefreshRecovery {
    fun isRateLimited(error: Throwable): Boolean {
        var current: Throwable? = error
        var hops = 0
        while (current != null && hops < 8) {
            val http = current as? HttpException
            if (http != null && (http.code() == 429 || http.code() == 503)) return true
            current = current.cause
            hops++
        }
        return false
    }

    fun messageFor(error: Throwable): String = when {
        isRateLimited(error) -> "Rate limited — backing off"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Network error"
    }

    /**
     * Next poll interval after a refresh attempt.
     * [recovered] means the fetch succeeded with no error message.
     * A rate limit at least doubles the interval, starting from [initialBackoffMs].
     */
    fun nextIntervalMs(
        currentMs: Long,
        rateLimited: Boolean,
        recovered: Boolean,
        wsLive: Boolean,
        baseMs: Long,
        initialBackoffMs: Long,
        maxBackoffMs: Long,
        wsMetadataMs: Long
    ): Long = when {
        rateLimited -> min(max(currentMs * 2, initialBackoffMs), maxBackoffMs)
        recovered && wsLive -> wsMetadataMs
        recovered -> max((currentMs * 4) / 5, baseMs)
        else -> currentMs
    }
}
