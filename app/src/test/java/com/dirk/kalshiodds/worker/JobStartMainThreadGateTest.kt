package com.dirk.kalshiodds.worker

import com.dirk.kalshiodds.crash.BoundedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.3.84 release gate: job 5040 onStartJob ANR (main thread busy during WorkManager start) + memory caps. */
class JobStartMainThreadGateTest {
    private fun src(rel: String): String {
        val roots = listOf("src/main/java/com/dirk/kalshiodds", "app/src/main/java/com/dirk/kalshiodds")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    @Test fun applicationOnCreateDoesNoDiskOrBinderWorkOnMain() {
        val body = src("KalshiOddsApp.kt").substringAfter("override fun onCreate()").substringBefore("private fun startContainer")
        val beforeDeferred = body.substringBefore("val earlyInit")
        assertFalse(beforeDeferred.contains("CrashBreadcrumb.install"))
        assertFalse(beforeDeferred.contains("CrashLog.install"))
        assertFalse(beforeDeferred.contains("ensureChannel"))
        assertTrue(body.contains("appScope.launch(Dispatchers.IO) { earlyInit() }"))
    }

    @Test fun autopilotHostIsNotBuiltOnMainFirst() {
        val s = src("signal/service/LiveSignalsService.kt")
        val i = s.indexOf("val built = runCatching { KalshiOddsApp.from(this@LiveSignalsService).ensureAutopilotHost() }")
        assertTrue(i > 0)
        assertTrue(s.indexOf("Handler(mainLooper).post { runCatching { KalshiOddsApp.from(this@LiveSignalsService).ensureAutopilotHost()") > i)
    }

    @Test fun periodicWorkUsesKeepNotUpdate() {
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.KEEP, MarketRefreshWorker.POLICY)
        for (f in listOf("worker/MarketRefreshWorker.kt", "worker/SyncWorker.kt", "signal/service/LiveSignalsKeepAlive.kt")) {
            val s = src(f)
            assertFalse(f, s.contains("ExistingPeriodicWorkPolicy.UPDATE") || s.contains("ExistingWorkPolicy.REPLACE"))
        }
    }

    @Test fun workersAreCoroutineWorkersOnIo() {
        for (f in listOf("worker/MarketRefreshWorker.kt", "worker/SyncWorker.kt", "worker/BackfillWorker.kt")) {
            val s = src(f)
            assertTrue(f, s.contains(": CoroutineWorker("))
            assertTrue(f, s.contains("doWork(): Result = withContext(Dispatchers.IO)"))
        }
    }

    @Test fun marketRefreshNeverRunsTwiceConcurrently() {
        assertTrue(MarketRefreshWorker.running.compareAndSet(false, true))
        assertFalse(MarketRefreshWorker.running.compareAndSet(false, true))
        MarketRefreshWorker.running.set(false)
    }

    @Test fun perTickerMapsAreBounded() {
        val m = HashMap<String, Long>()
        for (d in 0 until 5000) { m["KXBTC15M-%05d".format(d)] = d.toLong(); BoundedKeys.trim(m) }
        assertTrue(m.size <= BoundedKeys.DEFAULT_MAX)
        assertTrue(m.containsKey("KXBTC15M-04999")) // newest window kept
        assertFalse(m.containsKey("KXBTC15M-00000")) // oldest evicted
    }
}
