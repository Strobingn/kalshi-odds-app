package com.dirk.kalshiodds.signal.scalper

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Appends the AI supervisor's JSON lines to `supervisor_<kind>.jsonl` under [dir] on one background
 * thread (it is called from the trade-print path, so it never blocks). A file past [maxBytes] is moved
 * to `.1.jsonl` (replacing the previous one) and a fresh file starts.
 */
class SupervisorLog(
    private val dir: File,
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "supervisor-log").apply { isDaemon = true } },
    private val maxBytes: Long = 2_000_000L
) {
    fun append(kind: String, line: String) {
        io.execute {
            runCatching {
                dir.mkdirs()
                val f = File(dir, "supervisor_$kind.jsonl")
                if (f.length() > maxBytes) f.renameTo(File(dir, "supervisor_$kind.1.jsonl"))
                f.appendText(line + "\n")
            }
        }
    }
}
