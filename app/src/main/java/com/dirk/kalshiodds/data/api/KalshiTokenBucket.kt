package com.dirk.kalshiodds.data.api

import kotlin.math.ceil
import kotlin.math.min

/**
 * Process-wide token bucket. Reads and writes do not share tokens.
 * A 429 on reads holds only the read lane. Writes are never queued:
 * [tryAcquireWrite] either grants now or tells the caller how long to wait
 * so a real order can surface the error instead of being sent later.
 */
class KalshiTokenBucket(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val randomUnit: () -> Double = { Math.random() },
    private val readPerSec: Double = KalshiPollBudget.READ_PER_SEC,
    private val readBurst: Double = KalshiPollBudget.READ_BURST,
    private val writePerSec: Double = KalshiPollBudget.WRITE_PER_SEC,
    private val writeBurst: Double = KalshiPollBudget.WRITE_BURST,
    private val baseBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 60_000L
) {
    private val lock = Any()
    private var readTokens = readBurst
    private var writeTokens = writeBurst
    private var lastReadMs = nowMs()
    private var lastWriteMs = nowMs()
    private var readHoldUntilMs = 0L
    private var writeHoldUntilMs = 0L
    private var readAttempt = 0

    /**
     * 0 when a read token was consumed. Otherwise milliseconds the caller
     * should wait before trying again. Does not consume a token on wait.
     */
    fun reserveRead(): Long {
        synchronized(lock) {
            val now = nowMs()
            if (now < readHoldUntilMs) return (readHoldUntilMs - now).coerceAtLeast(1L)
            refillRead(now)
            if (readTokens >= 1.0) {
                readTokens -= 1.0
                return 0L
            }
            val missing = 1.0 - readTokens
            return ceil(missing / readPerSec * 1_000.0).toLong().coerceAtLeast(1L)
        }
    }

    /** Null when a write token was consumed. Otherwise a suggested wait. Never sends. */
    fun tryAcquireWrite(): Long? {
        synchronized(lock) {
            val now = nowMs()
            if (now < writeHoldUntilMs) return (writeHoldUntilMs - now).coerceAtLeast(1L)
            refillWrite(now)
            if (writeTokens >= 1.0) {
                writeTokens -= 1.0
                return null
            }
            val missing = 1.0 - writeTokens
            return ceil(missing / writePerSec * 1_000.0).toLong().coerceAtLeast(1L)
        }
    }

    fun noteRead429(retryAfterMs: Long?): Long {
        synchronized(lock) {
            val wait = backoffDelayMs(readAttempt, retryAfterMs)
            readAttempt = (readAttempt + 1).coerceAtMost(8)
            val until = nowMs() + wait
            readHoldUntilMs = maxOf(readHoldUntilMs, until)
            readTokens = 0.0
            lastReadMs = until
            return wait
        }
    }

    /** Records the failure for the UI. Does not schedule another POST. */
    fun noteWrite429(retryAfterMs: Long?): Long {
        synchronized(lock) {
            val wait = backoffDelayMs(0, retryAfterMs)
            writeHoldUntilMs = maxOf(writeHoldUntilMs, nowMs() + wait)
            return wait
        }
    }

    fun readHoldRemainingMs(now: Long = nowMs()): Long =
        synchronized(lock) { (readHoldUntilMs - now).coerceAtLeast(0L) }

    fun backoffDelayMs(attemptIndex: Int, retryAfterMs: Long?, unit: Double = randomUnit()): Long {
        val shift = attemptIndex.coerceIn(0, 6)
        val exp = baseBackoffMs shl shift
        val capped = min(exp, maxBackoffMs)
        val jitter = (capped * JITTER_FRACTION * unit.coerceIn(0.0, 1.0)).toLong()
        val computed = capped + jitter
        val retry = retryAfterMs?.takeIf { it > 0L }
        return if (retry != null) maxOf(computed, retry) else computed
    }

    private fun refillRead(now: Long) {
        val elapsed = (now - lastReadMs).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        readTokens = (readTokens + elapsed * readPerSec / 1_000.0).coerceAtMost(readBurst)
        lastReadMs = now
    }

    private fun refillWrite(now: Long) {
        val elapsed = (now - lastWriteMs).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        writeTokens = (writeTokens + elapsed * writePerSec / 1_000.0).coerceAtMost(writeBurst)
        lastWriteMs = now
    }

    companion object {
        const val JITTER_FRACTION = 0.20
        const val WRITE_DENIED = "Rate limited — wait and Approve again"
    }
}
