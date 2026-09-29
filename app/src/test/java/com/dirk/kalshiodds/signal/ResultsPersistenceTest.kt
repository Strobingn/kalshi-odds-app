package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.RollingTextLog
import com.dirk.kalshiodds.data.local.results.ScorecardRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.signal.engine.BookScoreGate
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.ml.SafeMl
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResultsPersistenceTest {
    @Before
    fun setUp() {
        HeavyMlGuard.reset()
        CrashBreadcrumb.resetForTest()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
        CrashBreadcrumb.resetForTest()
    }

    @Test
    fun storeWriteReadRoundTrip() {
        val store = InMemoryResultsStore()
        store.insertSnapshots(
            listOf(
                ScoredSnapshotRow(
                    ticker = "KXBTC15M-A",
                    series = "KXBTC15M",
                    side = "YES",
                    edgePp = 6.5,
                    fairPp = 42.0,
                    marketPp = 35.5,
                    regime = "TREND",
                    uncertainty = 0.08,
                    createdAtMs = 1_000L
                )
            )
        )
        store.insertAlert(
            AlertRow(
                alertId = "a1",
                ticker = "KXBTC15M-A",
                series = "KXBTC15M",
                side = "YES",
                edgePp = 6.5,
                fairPp = 42.0,
                marketPp = 35.5,
                reason = "edge",
                regime = "TREND",
                createdAtMs = 1_100L
            )
        )
        store.insertScorecard(
            ScorecardRow(
                ticker = "KXBTC15M-A",
                series = "KXBTC15M",
                outcome = "yes",
                score = 1,
                brier = 0.04,
                edgePp = 6.5,
                policyRoi = 0.12,
                createdAtMs = 2_000L
            )
        )
        store.insertTicket(
            TicketAttemptRow(
                ticker = "KXBTC15M-A",
                side = "YES",
                stakeUsd = 5.0,
                approved = true,
                result = "submitted",
                createdAtMs = 3_000L,
                note = "Approve-gated — never unsupervised"
            )
        )
        val snaps = store.recentSnapshots(10)
        assertEquals(1, snaps.size)
        assertEquals("KXBTC15M-A", snaps[0].ticker)
        assertEquals(6.5, snaps[0].edgePp, 1e-9)
        assertEquals(1, store.recentAlerts(5).size)
        assertEquals(1, store.recentScorecards(5).size)
        val tickets = store.recentTickets(5)
        assertEquals(1, tickets.size)
        assertTrue(tickets[0].approved)
        assertTrue(tickets[0].note!!.contains("never unsupervised"))
    }

    @Test
    fun exportCsvContainsHeadersAndEscapesCommas() {
        val store = InMemoryResultsStore()
        store.insertSnapshots(
            listOf(
                ScoredSnapshotRow(
                    ticker = "KXETH15M-B",
                    series = "KXETH15M",
                    side = "NO",
                    edgePp = -4.2,
                    fairPp = 30.0,
                    marketPp = 34.2,
                    regime = "CHOP",
                    uncertainty = 0.11,
                    createdAtMs = 9L,
                    note = "cnn+gbm, unc 0.11"
                )
            )
        )
        val csv = ResultsExporter.csv(store.exportBundle())
        assertTrue(csv.contains(ResultsExporter.SNAPSHOT_HEADER))
        assertTrue(csv.contains(ResultsExporter.ALERT_HEADER))
        assertTrue(csv.contains(ResultsExporter.TICKET_HEADER))
        assertTrue(csv.contains("KXETH15M-B"))
        assertTrue(csv.contains("\"cnn+gbm, unc 0.11\""))
        assertTrue(csv.contains("Approve-gated") || csv.contains("never unsupervised"))
        assertEquals("\"a,b\"", ResultsExporter.csv("a,b"))
    }

    @Test
    fun asyncWriterBatchesWithoutBlockingCaller() {
        val store = InMemoryResultsStore()
        val writer = AsyncResultsWriter(
            store = store,
            textLog = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            flushDelayMs = 0L
        )
        repeat(5) { i ->
            writer.enqueueSnapshot(
                ScoredSnapshotRow(
                    ticker = "KXBTC15M-$i",
                    series = "KXBTC15M",
                    side = "YES",
                    edgePp = i.toDouble(),
                    fairPp = 50.0,
                    marketPp = 48.0,
                    regime = "QUIET",
                    uncertainty = null,
                    createdAtMs = i.toLong()
                )
            )
        }
        writer.flushNow()
        assertEquals(5, store.recentSnapshots(20).size)
    }

    @Test
    fun resultsTextFileIsWrittenDuringDrainNotOnTheScoringCaller() {
        val file = File.createTempFile("diphunter-results", ".log")
        try {
            val writer = AsyncResultsWriter(
                store = InMemoryResultsStore(), textLog = RollingTextLog(file),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                flushDelayMs = 60_000L
            )
            writer.enqueueSnapshot(ScoredSnapshotRow(
                ticker = "KXBTC15M-A", series = "KXBTC15M", side = "YES", edgePp = 5.0,
                fairPp = 55.0, marketPp = 50.0, regime = null, uncertainty = null, createdAtMs = 1L
            ))
            assertEquals("", file.readText())
            writer.flushNow()
            assertTrue(file.readText().contains("KXBTC15M-A"))
        } finally {
            file.delete()
        }
    }
}

class CrashHardenTest {
    @Before
    fun setUp() {
        HeavyMlGuard.reset()
        HeavyMlGuard.persistHook = null
        CrashBreadcrumb.resetForTest()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
        HeavyMlGuard.persistHook = null
        CrashBreadcrumb.resetForTest()
    }

    @Test
    fun safeMlReturnsFallbackAndTripsGuardAfterLimit() {
        val out = SafeMl.run("boom", fallback = { "light" }) {
            error("native-ish")
        }
        assertEquals("light", out)
        assertEquals(1, HeavyMlGuard.failureCount())
        assertFalse(HeavyMlGuard.sessionDisabled)
        repeat(HeavyMlGuard.FAIL_LIMIT - 1) {
            SafeMl.run("boom", fallback = { 0 }) { error("again") }
        }
        assertTrue(HeavyMlGuard.sessionDisabled)
        val settings = com.dirk.kalshiodds.signal.config.SignalSettings(
            heavyMlEnabled = true,
            extendedAiEnabled = true
        )
        val applied = HeavyMlGuard.apply(settings)
        assertFalse(applied.heavyMlEnabled)
        assertFalse(applied.extendedAiEnabled)
    }

    @Test
    fun firstOomImmediatelyDisablesAndPersists() {
        val reasons = mutableListOf<String>()
        HeavyMlGuard.persistHook = { reasons.add(it) }
        val out = SafeMl.run("heap", fallback = { "light" }) {
            throw OutOfMemoryError("Failed to allocate a 32 byte allocation")
        }
        assertEquals("light", out)
        assertTrue(HeavyMlGuard.sessionDisabled)
        assertEquals(1, reasons.size)
        assertTrue(reasons[0].contains("OOM"))
        val applied = HeavyMlGuard.apply(
            com.dirk.kalshiodds.signal.config.SignalSettings(
                heavyMlEnabled = true,
                extendedAiEnabled = true
            )
        )
        assertFalse(applied.heavyMlEnabled)
        assertFalse(applied.extendedAiEnabled)
    }

    @Test
    fun latestWinsMailboxCoalescesAndReschedules() {
        val box = com.dirk.kalshiodds.signal.engine.LatestWinsMailbox<Int>()
        assertTrue(box.offer("a", 1))
        assertFalse(box.offer("a", 2))
        assertFalse(box.offer("b", 3))
        val batch = box.drain()
        assertEquals(2, batch.size)
        assertEquals(2, batch.first { it.first == "a" }.second)
        assertEquals(3, batch.first { it.first == "b" }.second)
        assertFalse(box.markIdleAndNeedsRerun())
        assertTrue(box.offer("c", 4))
        box.offer("c", 5)
        val second = box.drain()
        assertEquals(1, second.size)
        assertEquals(5, second[0].second)
        box.offer("d", 6)
        assertTrue(box.markIdleAndNeedsRerun())
    }

    @Test
    fun heapGuardRatiosMatch256MbDeviceBudget() {
        assertEquals(0.80, com.dirk.kalshiodds.signal.ml.HeapGuard.TIGHT_RATIO, 1e-9)
        assertEquals(0.90, com.dirk.kalshiodds.signal.ml.HeapGuard.CRITICAL_RATIO, 1e-9)
        assertTrue(com.dirk.kalshiodds.signal.ml.HeapGuard.maxBytes() > 0L)
        assertTrue(com.dirk.kalshiodds.signal.ml.SequenceBuffer.MAX_TICKERS == 12)
        assertTrue(com.dirk.kalshiodds.signal.ml.PathSimulator.DEFAULT_PATHS == 16)
        assertTrue(com.dirk.kalshiodds.signal.ml.NewsPulseCache.MAX_RSS_BYTES == 48_000)
        assertTrue(com.dirk.kalshiodds.signal.ml.HeavyMlRuntime.MAX_PENDING == 24)
    }

    @Test
    fun bookScoreGateThrottlesSameTicker() {
        val last = mutableMapOf<String, Long>()
        assertTrue(BookScoreGate.shouldPublish("T", 1_000L, last, minIntervalMs = 400L))
        assertFalse(BookScoreGate.shouldPublish("T", 1_200L, last, minIntervalMs = 400L))
        assertTrue(BookScoreGate.shouldPublish("T", 1_450L, last, minIntervalMs = 400L))
        assertTrue(BookScoreGate.shouldPublish("U", 1_450L, last, minIntervalMs = 400L))
    }

    @Test
    fun breadcrumbLooksFatalAndRecent() {
        assertTrue(CrashBreadcrumb.looksFatal("123 FATAL thread=diphunter-ticks java.lang.OutOfMemoryError"))
        assertTrue(CrashBreadcrumb.looksFatal("tflite Interpreter SIGSEGV"))
        assertFalse(CrashBreadcrumb.looksFatal("tick ticker=BTC mid=0.4"))
        CrashBreadcrumb.record("FATAL oom test")
        assertTrue(CrashBreadcrumb.recent().any { it.contains("FATAL") })
    }

    @Test
    fun scoreDoesNotThrowWhenGuardDisabled() {
        HeavyMlGuard.disableForSession("unit-test")
        val engine = com.dirk.kalshiodds.signal.engine.ScoringEngine(idFactory = { "id" })
        val now = System.currentTimeMillis()
        val tick = com.dirk.kalshiodds.signal.model.MarketTick(
            ticker = "KXBTC15M-TEST",
            series = "KXBTC15M",
            yesBid = 0.40,
            yesAsk = 0.42,
            lastPrice = 0.41,
            volume = 10_000.0,
            openInterest = 1_000.0,
            closeTimeEpochMs = now + 600_000,
            source = com.dirk.kalshiodds.signal.model.TickSource.WS_TICKER,
            receiveElapsedNanos = 1L
        )
        val settings = com.dirk.kalshiodds.signal.config.SignalSettings(
            watchBtc = true,
            heavyMlEnabled = true,
            extendedAiEnabled = true
        )
        val score = engine.score(tick, settings, now)
        assertTrue(score != null)
        assertFalse(score!!.heavyMl)
    }

    @Test
    fun concurrentHeavyInferDoesNotThrow() {
        val rt = com.dirk.kalshiodds.signal.ml.HeavyMlRuntime()
        val settings = com.dirk.kalshiodds.signal.config.SignalSettings(heavyMlEnabled = true)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val done = AtomicInteger(0)
        val threads = (0 until 6).map { t ->
            Thread {
                try {
                    start.await(2, TimeUnit.SECONDS)
                    repeat(25) { i ->
                        val out = rt.infer(
                            com.dirk.kalshiodds.signal.ml.HeavyMlRuntime.Input(
                                ticker = "KXBTC15M-T$t",
                                series = "KXBTC15M",
                                mid = 0.4 + i * 0.01,
                                volume = 1000.0,
                                openInterest = 100.0,
                                tteFrac = 0.5,
                                tte = com.dirk.kalshiodds.signal.engine.TteRegime.EARLY,
                                spread = 0.02,
                                imbalance = 0.1,
                                aggressor = 0.0,
                                depthQuality = 0.4,
                                leadLag = 0.0,
                                spot = 0.0,
                                mlpYes = 0.45,
                                volatility = 0.03,
                                momentum = 0.01,
                                bookSnap = null,
                                velocityPerSec = 0.001,
                                mids = listOf(0.4, 0.41, 0.42),
                                nowMs = 1_000L + i
                            ),
                            settings
                        )
                        check(out.ensembleYes in 0.0..1.0)
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(6, done.get())
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
    }
}
