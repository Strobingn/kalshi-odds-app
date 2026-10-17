package com.dirk.kalshiodds.release

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 0.3.82 ANR fix (kashi:crash:1792246321109, WorkManager job didn't answer onStopJob): stop/cancel paths and
 * main-thread callbacks never wait on a lock, DB, or join — cancel cooperatively and return immediately.
 */
class ReleaseGate0382Test {
    private val root = listOf(File("app/src/main/java/com/dirk/kalshiodds"), File("src/main/java/com/dirk/kalshiodds")).first { it.isDirectory }
    private fun src(rel: String) = File(root, rel).readText()
    private val all by lazy { root.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() } }

    @Test fun noRunBlockingOrCancelAndJoinAnywhereInTheApp() {
        for ((name, t) in all) {
            assertFalse("$name uses runBlocking", Regex("\\brunBlocking\\s*[({]").containsMatchIn(t))
            assertFalse("$name uses cancelAndJoin", t.contains("cancelAndJoin("))
        }
    }

    @Test fun workersNeverOverrideStopAndCancelCooperatively() {
        val workers = all.filter { Regex(":\\s*CoroutineWorker\\(").containsMatchIn(it.value) }
        assertTrue(workers.size >= 4)
        for ((name, t) in workers) {
            assertFalse("$name must not override onStopped", t.contains("fun onStopped"))
            assertFalse("$name must not shield work from cancellation", t.contains("NonCancellable"))
        }
    }

    @Test fun serviceTeardownPeeksTheContainerWithoutWaiting() {
        val s = src("signal/service/LiveSignalsService.kt")
        val destroy = s.substringAfter("override fun onDestroy()").substringBefore("private fun acquireWakeLock()")
        assertFalse(destroy, Regex("\\.container\\b(?!OrNull)").containsMatchIn(destroy))
        assertTrue(destroy.contains("containerOrNull"))
        val stop = s.substringAfter("private fun handleExplicitStop()").substringBefore("private fun promoteToForeground()")
        assertTrue(stop.contains("scope.launch(Dispatchers.IO)"))
    }

    @Test fun appExposesNonBlockingContainerAccess() {
        val app = src("KalshiOddsApp.kt")
        assertTrue(app.contains("val containerOrNull: AppContainer? get() = if (containerLazy.isInitialized())"))
        assertTrue(app.contains("withContext(Dispatchers.IO) { containerLazy.value }"))
    }

    @Test fun mainActivityAwaitsTheContainerOffMain() {
        val a = src("MainActivity.kt")
        val onCreate = a.substringAfter("override fun onCreate(").substringBefore("private fun showReadyUi()")
        assertFalse(onCreate, onCreate.contains(".container."))
        assertTrue(onCreate.contains(".awaitContainer()"))
        assertTrue(onCreate.indexOf(".awaitContainer()") < onCreate.indexOf("showReadyUi()"))
    }
}
