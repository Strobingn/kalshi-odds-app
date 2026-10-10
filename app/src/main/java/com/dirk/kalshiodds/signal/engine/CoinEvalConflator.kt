package com.dirk.kalshiodds.signal.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.3.50 crash fix: per-coin tick conflation for event-driven AI/scalp evaluation.
 *
 * 0.3.49 ran the whole AI + six-scalper pipeline (and its SQLite writes) on the MAIN thread every 150 ms of WS
 * traffic. This replaces it:
 *  - every WS ticker/book/CF event only marks its ticker dirty in a bounded per-coin set (newest wins; stale
 *    intermediate ticks are dropped, never queued);
 *  - at most ONE evaluation job per coin exists at any time (no coroutine per tick); events that arrive while a job
 *    is running are coalesced into its next pass;
 *  - jobs run on [dispatcher] (a single background lane, never Main).
 */
class CoinEvalConflator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val minGapMs: Long = MIN_GAP_MS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val coinOf: (String) -> String = { com.dirk.kalshiodds.decision.ScalpParams.coinOf(it) },
    private val eval: suspend (coin: String, tickers: Set<String>) -> Unit
) {
    private class Lane {
        val dirty = LinkedHashMap<String, Long>()
        var job: Job? = null
    }

    private val lock = Any()
    private val lanes = HashMap<String, Lane>()

    @Volatile var events: Long = 0L; private set
    @Volatile var evals: Long = 0L; private set
    /** Events folded into an already-pending pass (= stale ticks dropped). */
    @Volatile var conflated: Long = 0L; private set
    @Volatile var jobsLaunched: Long = 0L; private set
    @Volatile var avgLatencyMs: Double = 0.0; private set
    @Volatile var maxConcurrentPerCoin: Int = 0; private set
    @Volatile var errors: Long = 0L; private set
    private val running = HashMap<String, Int>()

    fun fire(ticker: String) {
        if (ticker.isBlank()) return
        val coin = coinOf(ticker)
        val now = nowNanos()
        synchronized(lock) {
            events++
            val lane = lanes.getOrPut(coin) { Lane() }
            if (lane.dirty.containsKey(ticker)) conflated++
            else if (lane.dirty.size < MAX_DIRTY_PER_COIN) lane.dirty[ticker] = now
            else conflated++
            if (lane.job?.isActive != true) {
                jobsLaunched++
                lane.job = scope.launch(dispatcher) { runLane(coin, lane) }
            }
        }
    }

    private suspend fun runLane(coin: String, lane: Lane) {
        synchronized(lock) {
            val n = (running[coin] ?: 0) + 1
            running[coin] = n
            if (n > maxConcurrentPerCoin) maxConcurrentPerCoin = n
        }
        try {
            while (true) {
                delay(minGapMs)
                val batch = synchronized(lock) {
                    if (lane.dirty.isEmpty()) { lane.job = null; null }
                    else LinkedHashMap(lane.dirty).also { lane.dirty.clear() }
                } ?: return
                try {
                    eval(coin, batch.keys)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    errors++
                }
                val lat = (nowNanos() - batch.values.min()) / 1_000_000.0
                evals++
                avgLatencyMs = if (evals == 1L) lat else avgLatencyMs * 0.9 + lat * 0.1
            }
        } finally {
            val me = kotlin.coroutines.coroutineContext[Job]
            synchronized(lock) {
                running[coin] = (running[coin] ?: 1) - 1
                if (lane.job === me) lane.job = null
            }
        }
    }

    fun pendingFor(coin: String): Int = synchronized(lock) { lanes[coin]?.dirty?.size ?: 0 }
    fun activeJobs(): Int = synchronized(lock) { lanes.values.count { it.job?.isActive == true } }

    companion object {
        const val MIN_GAP_MS = 150L
        /** BTC/ETH/SOL have a handful of live markets each; cap anyway so no burst can grow memory. */
        const val MAX_DIRTY_PER_COIN = 64
    }
}
