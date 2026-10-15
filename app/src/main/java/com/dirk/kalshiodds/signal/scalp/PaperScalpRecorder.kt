package com.dirk.kalshiodds.signal.scalp

import android.content.Context
import android.net.Uri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded local recordings contain market data and virtual ledgers, never keys or live-account data. */
class PaperScalpRecorder(private val context: Context) {
    private val root = File(context.filesDir, "paper-replays-v3")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val current = mutableMapOf<String, File>()
    var note: String = "Recording market depth locally"
        private set

    @Synchronized fun append(source: String, frame: PaperReplayFrame) {
        try {
            val dir = File(root, source).apply { check(isDirectory || mkdirs()) }
            var file = current[source]
            if (file == null || !file.exists() || file.length() >= 8L * 1024 * 1024) {
                file = File(dir, "${System.currentTimeMillis()}-${System.nanoTime()}.jsonl")
                val start = PaperReplayEvent("checkpoint", frame.event.atMs, checkpoint = frame.before, history = frame.history)
                file.writeText(json.encodeToString(start) + "\n")
                current[source] = file
                dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.name }.drop(4).forEach { check(it.delete()) }
            }
            file.appendText(json.encodeToString(frame.event) + "\n")
            note = "Replay recording on · about 32 MB per feed"
        } catch (e: Exception) {
            // Continue the virtual ledger, but explicitly mark this segment as incomplete and start a new checkpoint.
            current.remove(source)
            note = "Recording error: ${e.message}"
        }
    }

    @Synchronized fun export(uri: Uri, state: PaperScalpState): String {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
        require(files.isNotEmpty()) { "No books recorded yet. Start the monitor first." }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open export destination")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("paper-state.json")); zip.write(json.encodeToString(state).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("Bitcoin Edge paper recording. Production and demo are separate directories. Each JSONL segment starts with an exact state/history checkpoint. Replay segments separately using PaperScalpReplay; verify every event's totals before analyzing returns. This is observed paper execution, not a profitable backtest. No credentials included.\n".toByteArray()); zip.closeEntry()
            files.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry("${file.parentFile!!.name}/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return "Exported ${files.size} replay segments and the paper ledger"
    }
}
