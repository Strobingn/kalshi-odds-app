package com.dirk.kalshiodds.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.3.42 release gate — the 0.3.41 emulator run still crashed with ForegroundServiceDidNotStartInTimeException:
 * the system tore the service down after a missed start window (main thread stalled), and onDestroy
 * immediately called startForegroundService() again. 0.3.42:
 *  - foreground-UI starts use plain startService() (no start-foreground deadline),
 *  - onDestroy / onTaskRemoved never call startForegroundService (WorkManager restart only),
 *  - stop never spins the service up, and settings-driven starts are gated.
 */
class ReleaseGate0342Test {
    private fun kt(rel: String): String =
        listOf(File("app/src/main/java/com/dirk/kalshiodds/$rel"), File("src/main/java/com/dirk/kalshiodds/$rel"))
            .first { it.exists() }.readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var i = source.indexOf('{', start)
        var depth = 0
        val from = i
        while (i < source.length) {
            when (source[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return source.substring(from, i + 1) } }
            i++
        }
        return source.substring(from)
    }

    private fun code(s: String) = s.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun foregroundUiStartUsesPlainStartService() {
        val keep = kt("signal/service/LiveSignalsKeepAlive.kt")
        val start = code(body(keep, "fun startService(context: Context, fromForegroundUi: Boolean = false): Boolean"))
        val plain = start.indexOf("app.startService(intent)")
        val fgs = start.indexOf("app.startForegroundService(intent)")
        assertTrue(plain in 0 until fgs)
        assertTrue(start.contains("fromForegroundUi && uiInForeground.get()"))
        assertTrue(code(body(keep, "fun ensureServiceFromUi(")).contains("startService(context, fromForegroundUi = true)"))
    }

    @Test
    fun destroyAndTaskRemovedNeverRestartViaForegroundService() {
        val svc = kt("signal/service/LiveSignalsService.kt")
        listOf("override fun onDestroy()", "override fun onTaskRemoved(").forEach { sig ->
            val b = code(body(svc, sig))
            assertFalse(sig, b.contains("startService("))
            assertFalse(sig, b.contains("startForegroundService"))
            assertTrue(sig, b.contains("enqueueSoon"))
        }
    }

    @Test
    fun stopNeverSpinsUpTheService() {
        val stop = code(body(kt("signal/service/LiveSignalsKeepAlive.kt"), "fun stopService(context: Context)"))
        val guard = stop.indexOf("if (!LiveSignalsService.isRunning)")
        assertTrue(guard >= 0)
        assertTrue(guard < stop.indexOf("startForegroundService"))
    }

    @Test
    fun settingsDrivenStartIsGated() {
        val keep = kt("signal/service/LiveSignalsKeepAlive.kt")
        val req = code(body(keep, "fun requestStart(context: Context)"))
        assertTrue(req.contains("if (LiveSignalsService.isRunning) return"))
        assertTrue(req.contains("if (!firstFrameDrawn.get()) return"))
        assertTrue(kt("signal/service/LiveSignalsService.kt").contains("fun start(context: Context) = LiveSignalsKeepAlive.requestStart(context)"))
    }
}
