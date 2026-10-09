package com.dirk.kalshiodds.data.local.results

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tiny on-disk crash / fail-soft log so a mid-session death is not silent.
 * Never throws. Last few lines are also kept in memory for tests / Settings.
 */
object CrashBreadcrumb {
    const val FILE_NAME = "crash_breadcrumb.log"
    const val MAX_MEMORY = 40
    const val MAX_FILE_BYTES = 64_000L
    const val RECENT_CRASH_MS = 90_000L

    private val memory = CopyOnWriteArrayList<String>()
    @Volatile private var file: File? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        file = File(context.applicationContext.filesDir, FILE_NAME)
        record("boot pid=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT}")
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("FATAL thread=${thread.name} ${error.javaClass.name}: ${error.message}", error)
            previousHandler?.uncaughtException(thread, error)
        }
    }

    fun record(message: String, error: Throwable? = null) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(' ')
            append(message)
            if (error != null) {
                append('\n')
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                append(sw.toString().take(1_500))
            }
        }
        memory.add(line)
        while (memory.size > MAX_MEMORY) memory.removeAt(0)
        runCatching { Log.e(TAG, message, error) }
        val target = file ?: return
        runCatching {
            if (target.exists() && target.length() > MAX_FILE_BYTES) {
                val bak = File(target.parentFile, "$FILE_NAME.1")
                if (bak.exists()) bak.delete()
                target.renameTo(bak)
            }
            target.appendText(line.trimEnd() + "\n")
        }
    }

    fun recent(): List<String> = memory.toList()

    /**
     * True when the on-disk log has a FATAL / OOM line within [RECENT_CRASH_MS].
     * Used at process start to auto-disable Heavy ML after a native/OOM death.
     */
    fun recentFatalHint(nowMs: Long = System.currentTimeMillis(), windowMs: Long = RECENT_CRASH_MS): Boolean {
        val target = file ?: return memory.any { looksFatal(it) }
        val text = runCatching { target.readText() }.getOrElse { return memory.any { looksFatal(it) } }
        val lines = text.lineSequence().toList().takeLast(30)
        return lines.any { line ->
            val ts = line.substringBefore(' ').toLongOrNull() ?: return@any false
            nowMs - ts <= windowMs && looksFatal(line)
        }
    }

    fun looksFatal(line: String): Boolean {
        val u = line.uppercase()
        return u.contains("FATAL") ||
            u.contains("OUTOFMEMORY") ||
            u.contains("OOM") ||
            u.contains("SIGSEGV") ||
            u.contains("TFLITE") ||
            u.contains("INTERPRETER")
    }

    fun resetForTest() {
        memory.clear()
        file = null
        installed = false
    }

    private const val TAG = "DipHunterCrash"
}
