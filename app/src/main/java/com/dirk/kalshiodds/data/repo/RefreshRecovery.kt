package com.dirk.kalshiodds.data.repo

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
     * [retryAfterMs] (HTTP Retry-After) is honored, and that backoff is the floor
     * so a short Retry-After cannot poll sooner than the existing delay.
     */
    fun nextIntervalMs(
        currentMs: Long,
        rateLimited: Boolean,
        recovered: Boolean,
        wsLive: Boolean,
        baseMs: Long,
        initialBackoffMs: Long,
        maxBackoffMs: Long,
        wsMetadataMs: Long,
        retryAfterMs: Long? = null
    ): Long {
        val base = when {
            rateLimited -> min(max(currentMs * 2, initialBackoffMs), maxBackoffMs)
            recovered && wsLive -> wsMetadataMs
            recovered -> max((currentMs * 4) / 5, baseMs)
            else -> currentMs
        }
        val header = retryAfterMs?.takeIf { it > 0L } ?: return base
        if (!rateLimited) return base
        return max(base, header).coerceAtMost(RETRY_AFTER_CAP_MS)
    }

    /** Seconds (`30`) or an HTTP-date. Null when the header is missing or unreadable. */
    fun parseRetryAfterHeader(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = text.toLongOrNull()
        if (seconds != null) return seconds.coerceAtLeast(0L) * 1_000L
        return runCatching {
            val whenMs = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (whenMs - nowMs).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun retryAfterMs(error: Throwable, nowMs: Long = System.currentTimeMillis()): Long? {
        var current: Throwable? = error
        var hops = 0
        while (current != null && hops < 8) {
            val http = current as? HttpException
            if (http != null) {
                val parsed = parseRetryAfterHeader(http.response()?.headers()?.get("Retry-After"), nowMs)
                if (parsed != null) return parsed
            }
            current = current.cause
            hops++
        }
        return null
    }

    private const val RETRY_AFTER_CAP_MS = 15L * 60L * 1000L
}
