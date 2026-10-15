package com.dirk.kalshiodds.crash

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProcessExitLogTest {
    @Test fun osExitReportIsBoundedDeduplicatedAndKeepsTheOriginalStack() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashLog.attach(ctx); CrashLog.clear()
        val entry = ProcessExitLog.Entry(1000, 99, ctx.packageName, ApplicationExitInfo.REASON_ANR,
            0, 125, 1234, 5678, "Timed out")
        assertTrue(ProcessExitLog.record(ctx, entry) { "x".repeat(100_000) })
        assertFalse(ProcessExitLog.record(ctx, entry) { error("must not reread trace") })
        assertTrue(CrashLog.exportAll().contains("App not responding (ANR)"))
        assertTrue(CrashLog.exportAll().contains("PSS 1234 KB"))
        assertTrue(CrashLog.exportAll().length < 70_000)
        assertNull(CrashLog.consumePending())
        CrashLog.write("main", IllegalStateException("original stack"), nowMs = 2000)
        ProcessExitLog.record(ctx, entry.copy(atMs = 2000, reason = ApplicationExitInfo.REASON_CRASH))
        assertTrue(File(ctx.filesDir, "${CrashLog.DIR}/crash-2000.txt").readText().contains("original stack"))
        repeat(30) { ProcessExitLog.record(ctx, entry.copy(atMs = 3000L + it)) }
        assertEquals(CrashLog.MAX_FILES, CrashLog.files().size)
        assertEquals("Android low-memory kill", ProcessExitLog.reasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY))
        CrashLog.clear()
    }
}
