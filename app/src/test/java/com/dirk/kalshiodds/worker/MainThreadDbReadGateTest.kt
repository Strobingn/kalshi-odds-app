package com.dirk.kalshiodds.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.3.88 release gate: job 5101 onStartJob ANR. Main thread waited on a SQLite connection in
 * SqliteResultsStore.chartTicks <- ChartWindowService.seriesForCard <- OddsViewModel.attachHistory
 * <- applyScoreOverlay. Card history must be memory-only on main; disk reads run on IO; results DB uses WAL.
 */
class MainThreadDbReadGateTest {
    private fun src(rel: String): String {
        val roots = listOf("src/main/java/com/dirk/kalshiodds", "app/src/main/java/com/dirk/kalshiodds")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    private fun fn(s: String, sig: String): String {
        val i = s.indexOf(sig)
        assertTrue("missing $sig", i >= 0)
        val rest = s.substring(i + sig.length)
        val next = Regex("\n    (private |internal |override |suspend |public )?fun ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, next)
    }

    private val dbCalls = listOf("container.archive.", "container.resultsStore.", "chartWindows.seriesForCard(",
        "chartWindows.storedForCard(", "chartWindows.restoreWindow(", "chartWindows.needsBackfill(", "chartWindows.trimActive(")

    private fun assertNoDb(name: String, body: String) {
        for (c in dbCalls) assertFalse("$name must not call $c on main", body.contains(c))
    }

    private val vm get() = src("ui/OddsViewModel.kt")

    @Test fun attachHistoryIsMemoryOnly() {
        assertNoDb("attachHistory", fn(vm, "private fun attachHistory(snap: MarketsSnapshot)"))
        val cached = fn(vm, "private fun attachHistoryCached(snap: MarketsSnapshot)")
        assertNoDb("attachHistoryCached", cached)
        assertTrue(cached.contains("chartWindows.seriesForCardFrom("))
    }

    @Test fun historyDiskReadsRunOnIoLane() {
        assertTrue(vm.contains("Dispatchers.IO.limitedParallelism(1)"))
        assertTrue(fn(vm, "private fun scheduleHistoryRefresh(").contains("vmScope.launch(chartLane)"))
        assertTrue(fn(vm, "private fun scheduleChartBackfill(").contains("vmScope.launch(chartLane)"))
        assertTrue(fn(vm, "private fun applyRolloverCharts(").contains("vmScope.launch(chartLane)"))
        assertTrue(fn(vm, "fun seedChartWindows()").contains("vmScope.launch(chartLane)"))
    }

    @Test fun mainThreadCallersDoNoDiskReads() {
        for (sig in listOf("private fun applyScoreOverlay(", "internal fun applyRolloverEvent(",
                "private fun applyResult(", "private fun scheduleChartBackfill(", "private fun applyRolloverCharts(",
                "fun seedChartWindows()")) {
            // Anything after the hop onto chartLane runs on IO; the part before it runs on the caller (main).
            assertNoDb(sig, fn(vm, sig).substringBefore("vmScope.launch(chartLane)"))
        }
    }

    @Test fun restoreSeedsOnIo() {
        val body = fn(vm, "private suspend fun restorePersistedState()")
        val lines = body.lines()
        for ((i, l) in lines.withIndex()) {
            if (l.contains("seedOddsHistory()") || l.contains("seedChartWindowsBlocking()")) {
                val ctx = lines.subList(maxOf(0, i - 2), i + 1).joinToString("\n")
                assertTrue("seed must be inside withContext(Dispatchers.IO): $l", ctx.contains("withContext(Dispatchers.IO)"))
            }
        }
    }

    @Test fun seriesForCardFromDoesNotTouchArchive() {
        val s = src("chart/ChartWindowService.kt")
        assertFalse(fn(s, "fun seriesForCardFrom(").contains("archive."))
    }

    @Test fun resultsDbUsesWal() {
        val s = src("data/local/results/SqliteResultsStore.kt")
        assertTrue(fn(s, "override fun onConfigure(db: SQLiteDatabase)").contains("enableWriteAheadLogging()"))
    }
}
