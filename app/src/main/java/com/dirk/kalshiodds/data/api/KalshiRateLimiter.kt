package com.dirk.kalshiodds.data.api

import kotlin.math.min

/**
 * One client-side gate for every Kalshi poller (ticker REST, settlement,
 * D3, scorecard). A 429 / 5xx / network failure holds every lane until
 * the backoff elapses. [RefreshGate] stops Refresh taps from stacking.
 */
class KalshiRateLimiter(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val randomUnit: () -> Double = { Math.random() },
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val baseBackoffMs: Long = BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS
) {
    enum class Lane { TICKER, SETTLEMENT, D3, SCORECARD, REFRESH }

    private val lock = Any()
    private var nextSlotMs = 0L
    private var holdUntilMs = 0L
    private var attempt = 0

    /** Milliseconds the caller should wait before issuing a request. */
    fun reserve(lane: Lane): Long {
        synchronized(lock) {
            val now = nowMs()
            val earliest = maxOf(now, nextSlotMs, holdUntilMs)
            nextSlotMs = earliest + minSpacingMs
            return (earliest - now).coerceAtLeast(0L)
        }
    }

    fun onSuccess() {
        synchronized(lock) {
            attempt = 0
        }
    }

    /**
     * Records a global hold. Retry-After is a floor: the wait is never
     * shorter than the server asked for, even when that is longer than
     * the exponential cap.
     */
    fun onFailure(httpCode: Int?, retryAfterMs: Long?, local: Boolean = false): Long {
        synchronized(lock) {
            // 0.3.44: our own token bucket said "wait" (synthetic 429). Honor exactly that short wait; never escalate
            // the exponential backoff — that is what turned brief local throttling into "retrying in 70s".
            if (local) {
                // 0.3.45: no shared hold either — local waits never block other lanes or the UI.
                val wait = (retryAfterMs ?: 250L).coerceIn(50L, 2_000L)
                localThrottles++
                return wait
            }
            realFailures++
            val wait = backoffDelayMs(attempt, retryAfterMs, randomUnit())
            attempt = (attempt + 1).coerceAtMost(8)
            holdUntilMs = maxOf(holdUntilMs, nowMs() + wait)
            return wait
        }
    }

    @Volatile var localThrottles: Int = 0
        private set
    @Volatile var realFailures: Int = 0
        private set

    fun remainingHoldMs(now: Long = nowMs()): Long =
        synchronized(lock) { (holdUntilMs - now).coerceAtLeast(0L) }

    fun backoffDelayMs(attemptIndex: Int, retryAfterMs: Long?, unit: Double = randomUnit()): Long {
        val shift = attemptIndex.coerceIn(0, 6)
        val exp = baseBackoffMs shl shift
        val capped = min(exp, maxBackoffMs)
        val jitter = (capped * JITTER_FRACTION * unit.coerceIn(0.0, 1.0)).toLong()
        val computed = capped + jitter
        val retry = retryAfterMs?.takeIf { it > 0L }
        return if (retry != null) maxOf(computed, retry) else computed
    }

    companion object {
        const val MIN_SPACING_MS = 50L
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 15_000L
        const val JITTER_FRACTION = 0.20
    }
}

/**
 * User Refresh taps: ignore a second tap while one refresh is in flight,
 * and ignore taps inside [debounceMs] after a refresh starts.
 */
class RefreshGate(
    private val debounceMs: Long = 750L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var inFlight = false
    private var lastStartMs = Long.MIN_VALUE / 4

    fun tryAcquire(): Boolean {
        synchronized(lock) {
            val now = nowMs()
            if (inFlight) return false
            if (now - lastStartMs < debounceMs) return false
            inFlight = true
            lastStartMs = now
            return true
        }
    }

    fun release() {
        synchronized(lock) { inFlight = false }
    }

    fun inFlight(): Boolean = synchronized(lock) { inFlight }
}
