package com.dirk.kalshiodds.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/** OS kills/ANRs do not pass through Java's uncaught exception handler. Read them on next boot, off Main. */
object ProcessExitLog {
    private const val MAX_TRACE_BYTES = 64 * 1024
    internal data class Entry(val atMs: Long, val pid: Int, val process: String, val reason: Int,
        val status: Int, val importance: Int, val pssKb: Long, val rssKb: Long, val description: String?)

    fun capture(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        exits.filter { it.processName == context.packageName }.sortedBy { it.timestamp }.forEach { exit ->
            val entry = Entry(exit.timestamp, exit.pid, exit.processName, exit.reason, exit.status,
                exit.importance, exit.pss, exit.rss, exit.description)
            record(context, entry) {
                val native = Build.VERSION.SDK_INT >= 31 && exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                if (exit.reason != ApplicationExitInfo.REASON_ANR && !native) ""
                else runCatching { exit.traceInputStream?.use { readTrace(it, native) }
                    ?: "Android did not retain a trace." }.getOrElse { "Trace unavailable: ${it.javaClass.simpleName}" }
            }
        }
    }

    /** Native streams contain protobuf bytes, not UTF-8. Preserve a bounded binary payload losslessly. */
    internal fun readTrace(input: java.io.InputStream, native: Boolean): String {
        val buffer = ByteArray(MAX_TRACE_BYTES)
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        val truncated = total == buffer.size && input.read() != -1
        return if (native) {
            "Native tombstone protobuf · base64 · $total bytes · truncated=$truncated\n" +
                android.util.Base64.encodeToString(buffer.copyOf(total), android.util.Base64.NO_WRAP)
        } else String(buffer, 0, total, Charsets.UTF_8) + if (truncated) "\n[ANR trace truncated at 64 KiB]" else ""
    }

    /** Does not replace a saved exception or announce routine user/update kills as an app crash. */
    internal fun record(context: Context, entry: Entry, trace: () -> String = { "" }): Boolean {
        val dir = File(context.applicationContext.filesDir, CrashLog.DIR).also { it.mkdirs() }
        val marker = File(context.applicationContext.filesDir, "kashi-last-system-exit")
        val last = runCatching { marker.readText().toLong() }.getOrDefault(0L)
        if (entry.atMs <= last) return false
        val nearbyJava = if (entry.reason == ApplicationExitInfo.REASON_CRASH) dir.listFiles { f ->
            f.name.startsWith("crash-") && f.name.endsWith(".txt")
        }.orEmpty().filter { f ->
            val at = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L
            kotlin.math.abs(entry.atMs - at) <= 30_000 &&
                f.useLines { lines -> lines.firstOrNull().orEmpty().let { !it.startsWith("[") } }
        }.minByOrNull { f -> kotlin.math.abs(entry.atMs - (f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L)) } else null
        val file = nearbyJava ?: File(dir, "crash-${entry.atMs}.txt")
        if (nearbyJava != null) {
            nearbyJava.appendText("\n[Android exit metadata] ${reasonLabel(entry.reason)} · status ${entry.status} · pid ${entry.pid} · time ${CrashLog.stamp(entry.atMs)}\n")
        } else if (!file.exists()) file.writeText(buildString {
            val fatal = entry.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            if (!fatal) append("[non-fatal] ")
            append("[system exit] ${reasonLabel(entry.reason)} · status ${entry.status}\n")
            append("time ${CrashLog.stamp(entry.atMs)} · process ${entry.process} · pid ${entry.pid}\n")
            append("Android reason ${entry.reason} · importance ${entry.importance}\n")
            append("Last sampled PSS ${entry.pssKb} KB · RSS ${entry.rssKb} KB\n")
            append("Collected on app ${CrashLog.versionName}; the exited version may differ.\n")
            append("${entry.description.orEmpty().take(4000)}\n\n")
            append(trace().take(if (entry.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) 90_000 else MAX_TRACE_BYTES))
        })
        marker.writeText(entry.atMs.toString())
        dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0 }
            .drop(CrashLog.MAX_FILES).forEach { it.delete() }
        return true
    }

    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android low-memory kill"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_SIGNALED -> "Process killed by signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User-requested stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Process exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        else -> "Other Android exit (reason $reason)"
    }
}
