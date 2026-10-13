package com.dirk.kalshiodds.data.local.recording

import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecordingStats(
    val active: Boolean = false,
    val todayBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val days: List<RecordingDay> = emptyList(),
    val droppedRows: Long = 0L,
    val overCap: Boolean = false
)

/**
 * Second-by-second market-data recorder for research (lead-lag study,
 * maker-order research): Coinbase spot prints, Kalshi top of book, public
 * trades and settlements for the watched KXBTC15M markets, written to
 * gzip'd CSV day files by [RecordingFiles] in the format of [RecordingFormat].
 *
 * Tick-path contract: [onSpot] / [onTick] do a flag check, a tiny throttle
 * and a bounded queue offer — never I/O. A single writer coroutine samples
 * the book every [sampleMs] (rows via [BookRowGate]: on change ≤ 1/s per
 * ticker, heartbeat every 5 s), and appends + sync-flushes every
 * [flushMs]. Memory is bounded by [maxQueued] rows; excess rows are dropped
 * and counted. Runs while Live signals is on and "Record market data" is.
 */
class MarketDataRecorder(
    private val files: RecordingFiles,
    private val topOf: (ticker: String) -> TopOfBook?,
    private val metaOf: (ticker: String) -> Pair<Double?, Long?>,
    private val watchedTickers: () -> Set<String>,
    private val scope: CoroutineScope = defaultScope(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tickerFilter: (String) -> Boolean = { isRecordedTicker(it) },
    private val spotProducts: Set<String> = setOf("BTC-USD"),
    private val sampleMs: Long = 250L,
    private val flushMs: Long = 3_000L,
    private val capCheckMs: Long = 60_000L,
    private val maxQueued: Int = 50_000,
    private val settleGraceMs: Long = 30_000L,
    private val pendingFile: File? = File(files.dir, PENDING_FILE)
) {
    private sealed class Rec(val tsMs: Long) {
        class Spot(ts: Long, val product: String, val price: Double) : Rec(ts)
        class Book(ts: Long, val ticker: String, val strike: Double?, val closeMs: Long?, val top: TopOfBook) : Rec(ts)
        class Trade(ts: Long, val ticker: String, val yesPrice: Double?, val count: Double?, val side: String?) : Rec(ts)
        class Index(ts: Long, val asset: String, val indexId: String, val value: Double, val avg60: Double?, val finalMinuteAvg: Double?) : Rec(ts)
    }

    private data class Pending(val closeMs: Long, val strike: Double?)

    @Volatile var active: Boolean = false
        private set

    private val queue = ConcurrentLinkedQueue<Rec>()
    private val queued = AtomicInteger(0)
    private val dropped = AtomicLong(0L)
    private val spot = SpotThrottle(250L)
    private val indexGate = KeyThrottle(1000L)
    private val gate = BookRowGate()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val pendingLock = Any()
    @Volatile private var pendingLoaded = false
    @Volatile private var pendingDirty = false
    private var lastFlushMs = 0L
    private var lastCapMs = 0L
    private val jobLock = Any()
    private var job: Job? = null

    /** Start / stop the writer. Stopping flushes and closes the day files. */
    fun setActive(on: Boolean) {
        synchronized(jobLock) {
            if (on == active) return
            active = on
            val prev = job
            job = if (on) {
                scope.launch {
                    prev?.join()
                    lastFlushMs = clock()
                    lastCapMs = 0L
                    while (isActive) {
                        delay(sampleMs)
                        runCatching { step(clock()) }
                            .onFailure { CrashBreadcrumb.record("recorder", it) }
                    }
                }
            } else {
                scope.launch {
                    prev?.cancel()
                    prev?.join()
                    runCatching { finish(clock()) }
                }
            }
        }
    }

    /** Coinbase print for [product] (`BTC-USD`). Tick path: no I/O. */
    fun onSpot(product: String, price: Double) {
        if (!active || product !in spotProducts) return
        spot.offer(clock(), product, price)?.let { enqueue(Rec.Spot(it.tsMs, it.product, it.price)) }
    }

    /**
     * CF Benchmarks settlement-index print (the index the 15m markets
     * actually settle on). Throttled to ~1/s per asset; tick path, no I/O.
     * Training on the real settlement source needs 2-4 weeks of these.
     */
    fun onIndex(asset: String, indexId: String, value: Double, avg60: Double?, finalMinuteAvg: Double?, nowMs: Long = clock()) {
        if (!active || !value.isFinite() || value <= 0.0) return
        // One row per ~1 s bucket per index: offer() reports the bucket
        // change, and the row carries the CURRENT timestamp and values.
        if (indexGate.offer("$asset|$indexId", nowMs) != null) {
            enqueue(Rec.Index(nowMs, asset, indexId, value, avg60, finalMinuteAvg))
        }
    }

    /** Kalshi WS tick; only public trades are recorded. Tick path: no I/O. */
    fun onTick(tick: MarketTick) {
        if (!active || tick.source != TickSource.WS_TRADE) return
        if (!tickerFilter(tick.ticker)) return
        enqueue(Rec.Trade(clock(), tick.ticker, tick.lastPrice ?: tick.yesBid, tick.tradeSize, tick.takerSide))
    }

    /**
     * Recorded markets that closed at least [settleGraceMs] ago and have no
     * settle row yet — fed to the app's settlement lookup as extra tickers.
     */
    fun pendingSettlementTickers(nowMs: Long = clock()): Set<String> {
        ensurePendingLoaded()
        val stale = nowMs - PENDING_MAX_AGE_MS
        var removed = false
        val out = HashSet<String>()
        for ((ticker, p) in pending) {
            if (p.closeMs < stale) {
                pending.remove(ticker)
                removed = true
            } else if (p.closeMs + settleGraceMs <= nowMs) {
                out += ticker
            }
        }
        if (removed) pendingDirty = true
        return out
    }

    /** Settlement result from the app's lookup: append `yes` / `no` rows. */
    fun onSettled(ticker: String, result: String) {
        ensurePendingLoaded()
        val p = pending.remove(ticker) ?: return
        pendingDirty = true
        val r = result.trim().lowercase()
        if (r == "yes" || r == "no") {
            val day = RecordingFormat.utcDay(p.closeMs)
            runCatching { files.appendSettle(day, listOf(RecordingFormat.settleRow(ticker, p.closeMs, p.strike, r))) }
        }
        runCatching { savePendingIfDirty() }
    }

    fun stats(nowMs: Long = clock()): RecordingStats {
        val days = files.days()
        val today = RecordingFormat.utcDay(nowMs)
        return RecordingStats(
            active = active,
            todayBytes = days.firstOrNull { it.day == today }?.bytes ?: 0L,
            totalBytes = days.sumOf { it.bytes },
            days = days,
            droppedRows = dropped.get(),
            overCap = files.overCap
        )
    }

    /** Zip [days] (all when empty) into [out]; open members are sync-flushed first (rows still queued are not). */
    fun export(days: Collection<String>, out: java.io.OutputStream): Int {
        return files.zipDays(days, out, clock())
    }

    /** One writer pass (sample books, release spot, maybe flush). Writer thread only. */
    internal fun step(nowMs: Long) {
        sampleBooks(nowMs)
        for (p in spot.drainDue(nowMs)) enqueue(Rec.Spot(p.tsMs, p.product, p.price))
        if (nowMs - lastFlushMs >= flushMs) {
            writeOut(nowMs)
            files.flush(nowMs)
            savePendingIfDirty()
            lastFlushMs = nowMs
        }
        if (nowMs - lastCapMs >= capCheckMs) {
            files.enforceCap(RecordingFormat.utcDay(nowMs))
            lastCapMs = nowMs
        }
    }

    /** Final drain + close (stop / tests). */
    internal fun finish(nowMs: Long) {
        for (p in spot.drainDue(nowMs, all = true)) enqueue(Rec.Spot(p.tsMs, p.product, p.price), force = true)
        writeOut(nowMs)
        files.closeAll()
        savePendingIfDirty()
    }

    private fun sampleBooks(nowMs: Long) {
        val tickers = watchedTickers().filter(tickerFilter).toSet()
        gate.retain(tickers)
        for (t in tickers) {
            val top = runCatching { topOf(t) }.getOrNull() ?: continue
            if (top.isEmpty() || !gate.shouldWrite(t, top, nowMs)) continue
            val (strike, closeMs) = runCatching { metaOf(t) }.getOrDefault(null to null)
            if (enqueue(Rec.Book(nowMs, t, strike, closeMs, top))) {
                gate.markWritten(t, top, nowMs)
                if (closeMs != null) rememberForSettle(t, closeMs, strike)
            }
        }
    }

    private fun rememberForSettle(ticker: String, closeMs: Long, strike: Double?) {
        ensurePendingLoaded()
        val cur = pending[ticker]
        if (cur != null && cur.closeMs == closeMs && (cur.strike != null || strike == null)) return
        pending[ticker] = Pending(closeMs, strike ?: cur?.strike)
        pendingDirty = true
    }

    private fun enqueue(rec: Rec, force: Boolean = false): Boolean {
        if (!force && queued.incrementAndGet() > maxQueued) {
            queued.decrementAndGet()
            dropped.incrementAndGet()
            return false
        }
        if (force) queued.incrementAndGet()
        queue.add(rec)
        return true
    }

    private fun writeOut(nowMs: Long) {
        if (queue.isEmpty()) return
        val grouped = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        while (true) {
            val rec = queue.poll() ?: break
            queued.decrementAndGet()
            val (kind, line) = when (rec) {
                is Rec.Spot -> RecordingFormat.KIND_SPOT to RecordingFormat.spotRow(rec.tsMs, rec.product, rec.price)
                is Rec.Book -> RecordingFormat.KIND_BOOK to
                    RecordingFormat.bookRow(rec.tsMs, rec.ticker, rec.strike, rec.closeMs, rec.top)
                is Rec.Trade -> RecordingFormat.KIND_TRADES to
                    RecordingFormat.tradeRow(rec.tsMs, rec.ticker, rec.yesPrice, rec.count, rec.side)
                is Rec.Index -> RecordingFormat.KIND_INDEX to
                    RecordingFormat.indexRow(rec.tsMs, rec.asset, rec.indexId, rec.value, rec.avg60, rec.finalMinuteAvg)
            }
            grouped.getOrPut(kind to RecordingFormat.utcDay(rec.tsMs)) { ArrayList() }.add(line)
        }
        if (files.overCap) {
            dropped.addAndGet(grouped.values.sumOf { it.size }.toLong())
            return
        }
        for ((key, lines) in grouped) files.append(key.first, key.second, lines, nowMs)
    }

    private fun ensurePendingLoaded() {
        if (pendingLoaded) return
        synchronized(pendingLock) {
            if (pendingLoaded) return
            runCatching {
                val f = pendingFile
                if (f != null && f.exists()) {
                    for (line in f.readLines()) {
                        val parts = line.split(',')
                        if (parts.size < 2) continue
                        val close = parts[1].trim().toLongOrNull() ?: continue
                        val strike = parts.getOrNull(2)?.trim()?.toDoubleOrNull()
                        pending.putIfAbsent(parts[0].trim(), Pending(close, strike))
                    }
                }
            }
            pendingLoaded = true
        }
    }

    private fun savePendingIfDirty() {
        if (!pendingDirty) return
        val f = pendingFile ?: return
        synchronized(pendingLock) {
            pendingDirty = false
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(
                    pending.entries.sortedBy { it.value.closeMs }.joinToString("") { (t, p) ->
                        "$t,${p.closeMs},${RecordingFormat.num(p.strike)}\n"
                    }
                )
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    companion object {
        const val DIR_NAME = "recordings"
        const val PENDING_FILE = "pending_settle.csv"
        const val RECORDED_SERIES = "KXBTC15M"
        private const val PENDING_MAX_AGE_MS = 3L * 24L * 60L * 60_000L

        /** `KXBTC15M-…` only (not hourly / other BTC series). */
        fun isRecordedTicker(ticker: String): Boolean =
            ticker.trim().uppercase().startsWith("$RECORDED_SERIES-")

        fun defaultScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
                CrashBreadcrumb.record("recorder", t)
            }
        )
    }
}
