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
    /**
     * Strategies the Scalping AI switched off and did not switch back on, from its journal (both files, oldest
     * first). Blocking file read: call it off the main thread.
     */
    fun turnedOffByAi(): Set<String> {
        val off = LinkedHashSet<String>()
        for (name in listOf("supervisor_journal.1.jsonl", "supervisor_journal.jsonl")) {
            val f = File(dir, name)
            if (!f.isFile) continue
            runCatching {
                f.forEachLine { line ->
                    if (!line.contains("\"enabled\"") || !line.contains("\"applied\"")) return@forEachLine
                    val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (o.optString("event") != "applied" || o.optString("param") != "enabled") return@forEachLine
                    val st = o.optString("strategy").takeIf { it.isNotBlank() } ?: return@forEachLine
                    if (o.optDouble("new", 1.0) < 0.5) off += st else off -= st
                }
            }
        }
        return off
    }

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
