package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.worker.SyncWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source guard: the ANR stack must not query SQLite on the caller that publishes UI state. */
class AnrDispatcherGuardTest {
    private fun source(path: String) = File(path).readText()

    @Test fun homeHistoryDoesNotQueryChartTicksOnTheCaller() {
        val vm = source("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        val attach = vm.substringAfter("private fun attachHistory").substringBefore("private fun scoreLabel")
        assertFalse(attach.contains("seriesForCard"))
        assertFalse(attach.contains("chartTicks"))
        assertFalse(attach.contains("archive.bidHistory"))
        assertFalse(attach.contains("recentSettled"))
        val overlay = vm.substringAfter("private fun applyScoreOverlay").substringBefore("private fun scheduleRebuildTickets")
        assertFalse(overlay.contains("seriesForCard"))
        assertFalse(overlay.contains("chartTicks"))
        val refresh = vm.substringAfter("private suspend fun refreshCardSeries").substringBefore("private fun attachHistory")
        assertTrue(refresh.contains("seriesForCard"))
        assertTrue(vm.contains("vmScope.launch(Dispatchers.IO)"))
        assertTrue(vm.contains("withContext(Dispatchers.Main.immediate)"))
    }

    @Test fun arenaChartTicksRunOnIo() {
        val vm = source("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        val arena = vm.substringAfter("private suspend fun refreshScalpTab").substringBefore("private fun byCoinPerModel")
        assertTrue(arena.contains("withContext(Dispatchers.IO)"))
        assertTrue(arena.contains("container.archive.chartTicks"))
        val algo = vm.substringAfter("private suspend fun refreshScalpAlgo").substringBefore("fun ladderPlace")
        assertTrue(algo.contains("withContext(Dispatchers.IO)"))
        assertTrue(algo.contains("container.archive.chartTicks"))
        assertTrue(vm.contains("scalpTabJob = vmScope.launch(Dispatchers.IO)"))
    }

    @Test fun syncWorkerCooperatesAndStaysOffMain() {
        val worker = source("src/main/java/com/dirk/kalshiodds/worker/SyncWorker.kt")
        assertTrue(worker.contains(": CoroutineWorker"))
        assertTrue(worker.contains("withContext(Dispatchers.IO)"))
        assertTrue(worker.contains("isStopped"))
        assertTrue(worker.contains("ensureActive()"))
        assertTrue(worker.contains("yield()"))
        assertTrue(worker.contains("snapshotPageAfter"))
        assertTrue(worker.contains("if (result.snapshotsOk) com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.commit(picked)"))
        assertTrue(SyncWorker.SCALP_CHUNKS * SyncWorker.SCALP_BATCH >= 6_000)
        val store = source("src/main/java/com/dirk/kalshiodds/data/local/results/SqliteResultsStore.kt")
        assertTrue(store.contains("setWriteAheadLoggingEnabled(true)"))
        assertTrue(store.contains("enableWriteAheadLogging()"))
        assertTrue(store.contains("idx_chart_ticks_ticker"))
        assertTrue(store.contains("LIMIT \$cap"))
        assertTrue(store.contains("insertLedgerBatch"))
        assertTrue(store.contains("backgroundWriter.execute"))
        val app = source("src/main/java/com/dirk/kalshiodds/KalshiOddsApp.kt")
        assertTrue(app.contains("detectDiskReads()"))
        assertTrue(app.contains("detectDiskWrites()"))
        assertTrue(app.contains("BuildConfig.DEBUG"))
    }
}
