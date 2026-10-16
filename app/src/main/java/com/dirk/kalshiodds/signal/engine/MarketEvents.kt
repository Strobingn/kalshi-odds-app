package com.dirk.kalshiodds.signal.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.3.49 event-driven evaluation. Every WS ticker / orderbook snapshot+delta / CF tick for a market calls [fire].
 * Events per market are coalesced for [debounceMs] (default 150 ms), then [eval] runs once for the batch of markets
 * that changed. No polling floor: latency from the first event to eval done ≈ debounce + eval time.
 */
class MarketEventDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val eval: suspend (Set<String>) -> Unit
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Long>()
    private var job: Job? = null

    @Volatile var events: Long = 0L; private set
    @Volatile var evals: Long = 0L; private set
    @Volatile var lastLatencyMs: Double = 0.0; private set
    @Volatile var avgLatencyMs: Double = 0.0; private set
    @Volatile var lastEventNanos: Long = 0L; private set

    fun fire(ticker: String) {
        if (ticker.isBlank()) return
        val now = nowNanos()
        synchronized(lock) {
            events++
            lastEventNanos = now
            pending.putIfAbsent(ticker, now)
            scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        if (job?.isActive == true || pending.isEmpty()) return
        job = scope.launch {
            delay(debounceMs)
            val batch = synchronized(lock) { LinkedHashMap(pending).also { pending.clear() } }
            if (batch.isNotEmpty()) {
                runCatching { eval(batch.keys) }
                val lat = (nowNanos() - batch.values.min()) / 1_000_000.0
                evals++
                lastLatencyMs = lat
                avgLatencyMs = if (evals == 1L) lat else avgLatencyMs * 0.9 + lat * 0.1
            }
            // Events that arrived during eval get their own debounce window.
            synchronized(lock) { job = null; scheduleLocked() }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 150L
        /** Timer only for exits / time stops (and Home status); entries are event-driven. */
        const val EXIT_TIMER_MS = 1_000L
    }
}

/** 0.3.49 WS reconnect schedule: 0.5 s, 1 s, 2 s, 4 s, 8 s, then 10 s cap, each ±20 % jitter. */
object WsReconnectSchedule {
    const val INITIAL_MS = 500L
    const val CAP_MS = 10_000L
    const val JITTER = 0.2

    fun baseMs(attempt: Int): Long = (INITIAL_MS shl attempt.coerceIn(0, 10)).coerceAtMost(CAP_MS)

    /** [unit] in [-1, 1]. Never exceeds [CAP_MS]. */
    fun delayMs(attempt: Int, unit: Double): Long {
        val b = baseMs(attempt)
        return (b + b * JITTER * unit.coerceIn(-1.0, 1.0)).toLong().coerceIn(1L, CAP_MS)
    }
}

/** 0.3.49 Home status line copy. */
object FeedStatus {
    fun line(wsConnected: Boolean, lastTickAge: String, evalMs: Double?, restDelayMs: Long, real429LastHour: Int): String =
        String.format(
            java.util.Locale.US, "WS %s · last tick %s · AI eval %s · REST %s · 429s/1h %d",
            if (wsConnected) "connected" else "down", lastTickAge,
            evalMs?.let { String.format(java.util.Locale.US, "%.0f ms", it) } ?: "—",
            if (restDelayMs >= 1_000L) String.format(java.util.Locale.US, "%.1fs", restDelayMs / 1000.0) else "${restDelayMs}ms",
            real429LastHour
        )
}
