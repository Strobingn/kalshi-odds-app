package com.dirk.kalshiodds.data.local.results

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Batches SQLite / text-log writes off the scoring and UI threads.
 * A failed write never reaches the caller — scoring stays live.
 */
class AsyncResultsWriter(
    private val store: ResultsStore,
    private val textLog: RollingTextLog? = null,
    scope: CoroutineScope? = null,
    private val flushDelayMs: Long = 250L,
    private val maxBatch: Int = 48
) {
    private val job = SupervisorJob()
    private val scope = scope ?: CoroutineScope(job + Dispatchers.IO)
    private val snapshots = ConcurrentLinkedQueue<ScoredSnapshotRow>()
    private val alerts = ConcurrentLinkedQueue<AlertRow>()
    private val scorecards = ConcurrentLinkedQueue<ScorecardRow>()
    private val tickets = ConcurrentLinkedQueue<TicketAttemptRow>()
    private val odds = ConcurrentLinkedQueue<OddsMidRow>()
    private val settlementIndex = ConcurrentLinkedQueue<SettlementIndexRow>()
    private val chartTicks = ConcurrentLinkedQueue<com.dirk.kalshiodds.data.local.archive.ChartTickRow>()
    private val forwardTests = ConcurrentLinkedQueue<ForwardTestRow>()
    private val ticketForward = ConcurrentLinkedQueue<TicketForwardRow>()
    private val flushScheduled = AtomicBoolean(false)
    private val _forwardRevision = MutableStateFlow(0L)
    val forwardRevision = _forwardRevision.asStateFlow()

    fun enqueueSnapshot(row: ScoredSnapshotRow) {
        snapshots.add(row)
        schedule()
    }

    fun enqueueAlert(row: AlertRow) {
        alerts.add(row)
        schedule()
    }

    fun enqueueScorecard(row: ScorecardRow) {
        scorecards.add(row)
        schedule()
    }

    fun enqueueTicket(row: TicketAttemptRow) {
        tickets.add(row)
        schedule()
    }

    fun enqueueOddsMid(row: OddsMidRow) {
        odds.add(row)
        schedule()
    }

    fun enqueueSettlementIndex(row: SettlementIndexRow) {
        settlementIndex.add(row)
        schedule()
    }

    fun enqueueChartTick(row: com.dirk.kalshiodds.data.local.archive.ChartTickRow) {
        chartTicks.add(row)
        schedule()
    }

    fun enqueueForwardTest(row: ForwardTestRow) {
        forwardTests.add(row)
        schedule()
    }

    fun enqueueTicketForward(row: TicketForwardRow) {
        ticketForward.add(row)
        schedule()
    }

    fun flushNow() {
        drain()
    }

    private fun schedule() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                delay(flushDelayMs)
                drain()
            }
            flushScheduled.set(false)
            if (pending()) schedule()
        }
    }

    private fun pending(): Boolean =
        snapshots.isNotEmpty() || alerts.isNotEmpty() || scorecards.isNotEmpty() ||
            tickets.isNotEmpty() || odds.isNotEmpty() || settlementIndex.isNotEmpty() || chartTicks.isNotEmpty() ||
            forwardTests.isNotEmpty() || ticketForward.isNotEmpty()

    @Synchronized
    private fun drain() {
        runCatching {
            val batch = ArrayList<ForwardTestRow>(maxBatch)
            while (batch.size < maxBatch) {
                val next = forwardTests.poll() ?: break
                batch.add(next)
            }
            if (batch.isNotEmpty()) {
                store.insertForwardTests(batch)
                _forwardRevision.update { it + 1 }
            }
        }
        runCatching {
            val batch = ArrayList<TicketForwardRow>(maxBatch)
            while (batch.size < maxBatch) {
                val next = ticketForward.poll() ?: break
                batch.add(next)
            }
            if (batch.isNotEmpty()) {
                store.insertTicketForward(batch)
                _forwardRevision.update { it + 1 }
            }
        }
        runCatching {
            val batch = ArrayList<ScoredSnapshotRow>(maxBatch)
            while (batch.size < maxBatch) {
                val next = snapshots.poll() ?: break
                batch.add(next)
            }
            if (batch.isNotEmpty()) {
                batch.forEach { textLog?.append(ResultsExporter.logLine(it)) }
                store.insertSnapshots(batch)
            }
        }
        runCatching {
            while (true) {
                val next = alerts.poll() ?: break
                textLog?.append(ResultsExporter.logLine(next))
                store.insertAlert(next)
            }
        }
        runCatching {
            while (true) {
                val next = scorecards.poll() ?: break
                store.insertScorecard(next)
            }
        }
        runCatching {
            while (true) {
                val next = tickets.poll() ?: break
                textLog?.append(ResultsExporter.logLine(next))
                store.insertTicket(next)
            }
        }
        runCatching {
            val batch = ArrayList<OddsMidRow>(maxBatch)
            while (batch.size < maxBatch) {
                val next = odds.poll() ?: break
                batch.add(next)
            }
            if (batch.isNotEmpty()) store.insertOddsMids(batch)
        }
        runCatching {
            val batch = ArrayList<SettlementIndexRow>(maxBatch)
            while (batch.size < maxBatch) {
                val next = settlementIndex.poll() ?: break
                batch.add(next)
            }
            if (batch.isNotEmpty()) store.insertSettlementIndex(batch)
        }
        runCatching {
            val archive = store as? com.dirk.kalshiodds.data.local.archive.DataArchive
            if (archive != null) {
                val batch = ArrayList<com.dirk.kalshiodds.data.local.archive.ChartTickRow>(maxBatch)
                while (batch.size < maxBatch) {
                    val next = chartTicks.poll() ?: break
                    batch.add(next)
                }
                if (batch.isNotEmpty()) archive.insertChartTicks(batch)
            } else {
                while (chartTicks.poll() != null) Unit
            }
        }
    }
}
