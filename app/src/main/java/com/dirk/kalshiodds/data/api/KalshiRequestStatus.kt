package com.dirk.kalshiodds.data.api

import java.io.IOException
import retrofit2.HttpException

/**
 * Turns Kalshi / network failures into a non-crashing home state.
 * IO and unknown network failures are [OFFLINE]. HTTP 429 and 5xx
 * back off and say when the next try is.
 */
object KalshiRequestStatus {
    const val OFFLINE = "Offline"

    fun rateLimited(retryInMs: Long): String {
        val seconds = ((retryInMs.coerceAtLeast(1L) + 999L) / 1000L).coerceAtLeast(1L)
        return "Rate-limited, retrying in ${seconds}s"
    }

    fun unavailable(retryInMs: Long): String {
        val seconds = ((retryInMs.coerceAtLeast(1L) + 999L) / 1000L).coerceAtLeast(1L)
        return "Kalshi unavailable, retrying in ${seconds}s"
    }

    /** Gray status copy. Not an error color and not yellow. */
    fun isQuietStatus(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return isFeedBanner(message) || message.startsWith("Backing off")
    }

    fun isFeedBanner(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message == OFFLINE ||
            message.startsWith("Rate-limited, retrying in ") ||
            message.startsWith("Kalshi unavailable, retrying in ")
    }

    fun httpCode(error: Throwable?): Int? {
        var current = error
        var guard = 0
        while (current != null && guard < 6) {
            if (current is HttpException) return current.code()
            current = current.cause
            guard += 1
        }
        return null
    }

    fun isRateLimited(error: Throwable): Boolean = httpCode(error) == 429

    fun isServerError(error: Throwable): Boolean {
        val code = httpCode(error) ?: return false
        return code in 500..599
    }

    fun isTransport(error: Throwable): Boolean {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 6) {
            if (current is IOException) return true
            current = current.cause
            guard += 1
        }
        return false
    }

    fun shouldBackoff(error: Throwable): Boolean =
        isRateLimited(error) || isServerError(error) || isTransport(error)

    fun retryAfterMs(error: Throwable?): Long? {
        var current = error
        var guard = 0
        while (current != null && guard < 6) {
            if (current is HttpException) {
                val header = current.response()?.headers()?.get("Retry-After")
                parseRetryAfter(header)?.let { return it }
            }
            current = current.cause
            guard += 1
        }
        return null
    }

    /** 0.3.44: true for the gate's own synthetic 429 (local token bucket), not a real Kalshi 429. */
    fun isLocalThrottle(error: Throwable?): Boolean {
        var current = error
        var guard = 0
        while (current != null && guard < 6) {
            if (current is HttpException) {
                return current.response()?.headers()?.get(KalshiHttpGate.LOCAL_HEADER) == "1"
            }
            current = current.cause
            guard++
        }
        return false
    }

    fun parseRetryAfter(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull() ?: return null
        if (seconds < 0L) return null
        return seconds * 1_000L
    }

    fun message(error: Throwable, retryInMs: Long): String = when {
        isRateLimited(error) -> rateLimited(retryInMs)
        isServerError(error) -> unavailable(retryInMs)
        else -> OFFLINE
    }
}
