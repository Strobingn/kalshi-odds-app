package com.dirk.kalshiodds.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 0.3.47 persistent crash log. Every uncaught exception is written to filesDir/crashes/crash-<ms>.txt (newest
 * [MAX_FILES] kept) with the full stack trace, then handed to the previous handler (the app still dies normally).
 * The newest crash is flagged once for the next launch; More → Crash log lists, shows and shares them.
 */
object CrashLog {
    const val DIR = "crashes"
    const val PENDING = "pending_crash"
    const val MAX_FILES = 20
    @Volatile private var dir: File? = null
    @Volatile private var installed = false

    fun install(context: Context, versionName: String = "") {
        dir = File(context.applicationContext.filesDir, DIR).also { it.mkdirs() }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread.name, error, versionName) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes synchronously (the process is about to die). Visible for tests. */
    fun write(threadName: String, error: Throwable, versionName: String = "", nowMs: Long = System.currentTimeMillis()): File? {
        val d = dir ?: return null
        d.mkdirs()
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val f = File(d, "crash-$nowMs.txt")
        f.writeText(buildString {
            append("${error.javaClass.name}: ${error.message}\n")
            append("time ${stamp(nowMs)} · thread $threadName")
            if (versionName.isNotBlank()) append(" · app $versionName")
            append(" · sdk ${runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0)}\n\n")
            append(sw.toString())
        })
        File(d, PENDING).writeText(f.name)
        files().drop(MAX_FILES).forEach { it.delete() }
        return f
    }

    fun attach(context: Context) { if (dir == null) dir = File(context.applicationContext.filesDir, DIR).also { it.mkdirs() } }

    /** Newest first. */
    fun files(): List<File> = dir?.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
        ?.sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L }.orEmpty()

    /** The crash to announce on this launch, at most once; null if none. */
    fun consumePending(): File? {
        val d = dir ?: return null
        val marker = File(d, PENDING)
        if (!marker.exists()) return null
        val name = runCatching { marker.readText().trim() }.getOrDefault("")
        marker.delete()
        return File(d, name).takeIf { name.isNotBlank() && it.exists() }
    }

    fun headline(f: File): String = runCatching { f.useLines { it.firstOrNull().orEmpty() } }.getOrDefault(f.name)

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("")

    /** All crash files, newest first, as one shareable text. */
    fun exportAll(): String = files().joinToString("\n\n==========\n\n") { read(it) }

    fun clear() { dir?.listFiles()?.forEach { it.delete() } }

    fun stamp(ms: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.of("America/New_York")))
}
