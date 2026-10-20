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
    @Volatile var versionName: String = ""
    const val SYNCED = "synced_crashes"

    fun install(context: Context, versionName: String = "") {
        dir = File(context.applicationContext.filesDir, DIR).also { it.mkdirs() }
        if (versionName.isNotBlank()) this.versionName = versionName
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread.name, error, versionName) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes synchronously (the process is about to die). Visible for tests. */
    fun write(threadName: String, error: Throwable, versionName: String = "", nowMs: Long = System.currentTimeMillis(), fatal: Boolean = true): File? {
        val d = dir ?: return null
        d.mkdirs()
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val f = File(d, "crash-$nowMs.txt")
        f.writeText(buildString {
            append((if (fatal) "" else "[non-fatal] ") + "${error.javaClass.name}: ${error.message}\n")
            append("time ${stamp(nowMs)} · thread $threadName")
            if (versionName.isNotBlank()) append(" · app $versionName")
            append(" · sdk ${runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0)}\n")
            append(runCatching { HeapInfo.line() }.getOrDefault("Heap ?") + "\n\n")
            append(sw.toString())
        })
        if (fatal) File(d, PENDING).writeText(f.name)
        files().drop(MAX_FILES).forEach { it.delete() }
        return f
    }

    fun attach(context: Context) { dir = File(context.applicationContext.filesDir, DIR).also { it.mkdirs() } }

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

    /** 0.3.64: crash files not yet uploaded, as diphunter_sync rows of kind "crash" (stack trace truncated to 8 KB). */
    fun unsyncedRecords(): List<com.dirk.kalshiodds.data.supabase.SyncMerge.Record> {
        val d = dir ?: return emptyList()
        val done = runCatching { File(d, SYNCED).readLines().toSet() }.getOrDefault(emptySet())
        return files().filter { it.name !in done }.take(10).map { f ->
            val ms = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
            val text = read(f)
            com.dirk.kalshiodds.data.supabase.SyncMerge.Record(key = "crash:$ms", kind = "crash", updatedAtMs = ms,
                payload = org.json.JSONObject().put("atMs", ms).put("version", versionName).put("headline", headline(f))
                    .put("fatal", !text.startsWith("[non-fatal]")).put("trace", text.take(8_000)).toString())
        }
    }
    fun markSynced(keys: Collection<String>) {
        val d = dir ?: return
        val names = keys.map { "crash-" + it.removePrefix("crash:") + ".txt" }
        runCatching { File(d, SYNCED).appendText(names.joinToString("\n", postfix = "\n")) }
    }

    fun clear() { dir?.listFiles()?.forEach { it.delete() } }

    /** "Oct 20, 2026, 1:52:07 PM ET" style through the shared ET formatter. */
    fun stamp(ms: Long): String = com.dirk.kalshiodds.domain.EtTime.fullDateClockSec(ms)
}
