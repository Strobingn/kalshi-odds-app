package com.dirk.kalshiodds.release

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 0.3.81 ANR audit: no Worker/Receiver/Application startup work on the main thread. */
class ReleaseGate0381Test {
    private val root = listOf(File("app/src/main/java/com/dirk/kalshiodds"), File("src/main/java/com/dirk/kalshiodds")).first { it.isDirectory }
    private fun src(rel: String) = File(root, rel).readText()

    @Test fun everyWorkerDoWorkRunsOnIoWithoutBlocking() {
        val workers = root.walkTopDown().filter { it.extension == "kt" && Regex(":\\s*(Coroutine|Listenable)?Worker\\(").containsMatchIn(it.readText()) }.toList()
        assertTrue(workers.size >= 4)
        for (f in workers) {
            val t = f.readText()
            assertTrue(f.name, t.contains(": CoroutineWorker("))
            val doWork = t.substringAfter("override suspend fun doWork()").substringBefore("companion object")
            assertTrue(f.name + " doWork must hop to IO", doWork.contains("Dispatchers.IO"))
            assertFalse(f.name, doWork.contains("runBlocking"))
        }
    }

    @Test fun syncAndRefreshWorkersHaveATimeCap() {
        assertTrue(src("worker/SyncWorker.kt").contains("withTimeoutOrNull(MAX_RUN_MS)"))
        assertTrue(src("worker/MarketRefreshWorker.kt").contains("withTimeoutOrNull(MAX_RUN_MS)"))
    }

    @Test fun applicationOnCreateDoesNotBuildTheContainerOnMain() {
        val app = src("KalshiOddsApp.kt")
        val onCreate = app.substringAfter("override fun onCreate()").substringBefore("private fun startContainer()")
        assertFalse(onCreate.contains("AppContainer("))
        assertTrue(onCreate.contains("appScope.launch(Dispatchers.IO) { startContainer() }"))
        assertTrue(app.contains("lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }"))
        assertTrue(onCreate.contains("StartupStrictMode.enable()"))
    }

    @Test fun bootReceiverAndServiceDoNotBlockMain() {
        val r = src("signal/service/LiveSignalsBootReceiver.kt")
        assertTrue(r.contains("goAsync()")); assertTrue(r.contains("Thread {"))
        val s = src("signal/service/LiveSignalsService.kt")
        assertFalse(s.contains("android.os.Handler(mainLooper).post { runCatching { KalshiOddsApp.from(this).ensureAutopilotHost() } }"))
    }
}
