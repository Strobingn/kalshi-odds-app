package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Storage the decision runtime needs. SqliteResultsStore implements it in the app. */
interface LedgerSink {
    fun insertLedger(row: LedgerRow): Long
    fun settledLedger(sinceMs: Long, limit: Int): List<LedgerRow>
    fun unsettledLedgerTickers(nowMs: Long, sinceMs: Long, limit: Int): List<String>
}

/**
 * Holds the current calibration, writes the prediction ledger (throttled
 * per ticker so the table stays bounded without deleting anything), and
 * refits the regime calibration from settled ledger rows.
 */
class DecisionRuntime(
    private val sink: LedgerSink?,
    private val scope: CoroutineScope?,
    longshot: LongshotResidualModel = LongshotResidualModel.EMPTY,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    data class Summary(
        val calibrationRows: Int = 0,
        val calibrationFittedAtMs: Long = 0L,
        val ledgerWrites: Int = 0,
        val lastReason: Map<String, String> = emptyMap()
    )

    private val calibrationRef = AtomicReference(RegimeCalibration.Model())
    private val longshotRef = AtomicReference(longshot)
    private val lastWrite = ConcurrentHashMap<String, Pair<Long, String>>()
    private val latest = ConcurrentHashMap<String, DecisionPipeline.Assessment>()
    private val unsettled = AtomicReference<Set<String>>(emptySet())
    private val settledSinceFit = AtomicInteger(0)
    private val refitting = AtomicBoolean(false)
    private val writes = AtomicInteger(0)
    @Volatile private var lastFitMs = 0L
    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    val calibration: RegimeCalibration.Model get() = calibrationRef.get()
    val longshot: LongshotResidualModel get() = longshotRef.get()

    fun context(): DecisionPipeline.Context = DecisionPipeline.Context(calibrationRef.get(), longshotRef.get())

    fun setLongshot(model: LongshotResidualModel) = longshotRef.set(model)

    fun latest(ticker: String): DecisionPipeline.Assessment? = latest[ticker.uppercase()]

    fun latestAll(): Map<String, DecisionPipeline.Assessment> = HashMap(latest)

    fun assess(input: DecisionPipeline.Input): DecisionPipeline.Assessment {
        val a = DecisionPipeline.assess(input, context())
        latest[input.ticker.uppercase()] = a
        record(input, a)
        return a
    }

    /** Ledger cadence: 15m every 60s (15s in the last 3 min), daily every 15 min near the money; always on a decision change. */
    fun shouldWrite(input: DecisionPipeline.Input, a: DecisionPipeline.Assessment): Boolean {
        val daily = input.series.uppercase().endsWith("D")
        if (daily) {
            val m = a.marketYes ?: return false
            if (m < 0.03 || m > 0.97) return false
        }
        val sig = "${a.verdict.decision}|${a.chosen?.side}"
        val prev = lastWrite[input.ticker.uppercase()] ?: return true
        if (prev.second != sig) return true
        val left = input.secondsRemaining ?: Double.MAX_VALUE
        val cadence = when {
            daily -> 900_000L
            left <= 180.0 -> 15_000L
            else -> 60_000L
        }
        return input.nowMs - prev.first >= cadence
    }

    fun record(input: DecisionPipeline.Input, a: DecisionPipeline.Assessment) {
        val s = sink ?: return
        if (!shouldWrite(input, a)) return
        lastWrite[input.ticker.uppercase()] = input.nowMs to "${a.verdict.decision}|${a.chosen?.side}"
        val row = DecisionPipeline.ledgerRow(input, a, RegimeCalibration.VERSION + "@" + calibrationRef.get().rows)
        val write = {
            runCatching { s.insertLedger(row) }
            val n = writes.incrementAndGet()
            _summary.value = _summary.value.copy(
                ledgerWrites = n,
                lastReason = _summary.value.lastReason + (input.ticker.uppercase() to a.verdict.headline)
            )
        }
        if (scope != null) scope.launch { write() } else write()
    }

    fun onSettled() {
        settledSinceFit.incrementAndGet()
    }

    fun unsettledTickers(): Set<String> = unsettled.get()

    /** Refit from settled ledger rows (last [lookbackDays]). Cheap enough to run every few minutes off the main thread. */
    fun refitNow(lookbackDays: Int = 30) {
        val s = sink ?: return
        if (!refitting.compareAndSet(false, true)) return
        try {
            val now = nowMs()
            val rows = runCatching { s.settledLedger(now - lookbackDays * 86_400_000L, MAX_FIT_ROWS) }.getOrElse { emptyList() }
            val model = RegimeCalibration.fit(samplesFrom(rows), now)
            calibrationRef.set(model)
            unsettled.set(
                runCatching { s.unsettledLedgerTickers(now, now - 3L * 86_400_000L, 60) }.getOrElse { emptyList() }
                    .map { it.uppercase() }.toSet()
            )
            lastFitMs = now
            settledSinceFit.set(0)
            _summary.value = _summary.value.copy(calibrationRows = model.rows, calibrationFittedAtMs = now)
        } finally {
            refitting.set(false)
        }
    }

    fun refitIfDue(minIntervalMs: Long = 10 * 60_000L) {
        val now = nowMs()
        // Refit on schedule (also refreshes the unsettled tickers the poller must settle).
        if (lastFitMs != 0L && now - lastFitMs < minIntervalMs) return
        if (scope != null) scope.launch { refitNow() } else refitNow()
    }

    companion object {
        /** Most recent settled rows used per fit (bounded for phone memory; nothing is deleted). */
        const val MAX_FIT_ROWS = 25_000

        /**
         * One calibration sample per (market, time bucket): the latest
         * prediction in that bucket. Stops a single window from dominating.
         */
        fun samplesFrom(rows: List<LedgerRow>): List<RegimeCalibration.Sample> {
            val best = LinkedHashMap<String, LedgerRow>()
            for (r in rows) {
                if (r.outcomeYes == null) continue
                val raw = r.rawModelProb ?: continue
                val mkt = r.marketMid ?: continue
                if (!raw.isFinite() || !mkt.isFinite()) continue
                val key = RegimeCalibration.keyOf(r.series, r.secondsRemaining, r.zDistance)
                val id = "${r.ticker.uppercase()}|${key.time.name}"
                val prev = best[id]
                if (prev == null || r.timestampMs > prev.timestampMs) best[id] = r
            }
            return best.values.map { r ->
                RegimeCalibration.Sample(
                    rawProbability = r.rawModelProb!!,
                    marketProbability = r.marketMid!!,
                    outcomeYes = r.outcomeYes!!,
                    key = RegimeCalibration.keyOf(r.series, r.secondsRemaining, r.zDistance),
                    timestampMs = r.timestampMs,
                    cluster = r.ticker.uppercase()
                )
            }
        }
    }
}
