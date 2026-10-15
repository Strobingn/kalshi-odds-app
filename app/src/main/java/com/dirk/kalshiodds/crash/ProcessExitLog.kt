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
                if (exit.reason != ApplicationExitInfo.REASON_ANR) ""
                else runCatching { exit.traceInputStream?.use {
                    val buffer = ByteArray(MAX_TRACE_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val n = it.read(buffer, total, buffer.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    String(buffer, 0, total, Charsets.UTF_8)
                }.orEmpty() }.getOrDefault("")
            }
        }
    }

    /** Does not replace a saved exception or announce routine user/update kills as an app crash. */
    internal fun record(context: Context, entry: Entry, trace: () -> String = { "" }): Boolean {
        val dir = File(context.applicationContext.filesDir, CrashLog.DIR).also { it.mkdirs() }
        val marker = File(dir, "last-system-exit")
        val last = runCatching { marker.readText().toLong() }.getOrDefault(0L)
        if (entry.atMs <= last) return false
        val file = File(dir, "crash-${entry.atMs}.txt")
        if (!file.exists()) file.writeText(buildString {
            append("[system exit] ${reasonLabel(entry.reason)} · status ${entry.status}\n")
            append("time ${CrashLog.stamp(entry.atMs)} · process ${entry.process} · pid ${entry.pid}\n")
            append("Android reason ${entry.reason} · importance ${entry.importance}\n")
            append("Last sampled PSS ${entry.pssKb} KB · RSS ${entry.rssKb} KB\n")
            append("Collected on app ${CrashLog.versionName}; the exited version may differ.\n")
            append("${entry.description.orEmpty().take(4000)}\n\n")
            append(trace().take(MAX_TRACE_BYTES))
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
