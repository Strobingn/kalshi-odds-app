package com.dirk.kalshiodds.data.local.recording

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One UTC day of recordings on disk. */
data class RecordingDay(val day: String, val bytes: Long, val files: Int)

/**
 * Day files under [dir] (see [RecordingFormat]). Only the recorder's writer
 * appends; export / stats may run on other threads, so every method takes
 * the same lock. Never throws to the caller — a failed write drops rows.
 *
 * Gzip kinds keep one open [GZIPOutputStream] (sync-flush) per kind and
 * start a new member on app start, day change, or after [memberMaxAgeMs],
 * so a crash can lose at most the unflushed seconds and one gzip trailer.
 */
class RecordingFiles(
    val dir: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_BYTES,
    private val memberMaxAgeMs: Long = DEFAULT_MEMBER_MAX_AGE_MS
) {
    private class Open(val day: String, val file: File, val stream: GZIPOutputStream, val openedAtMs: Long)

    private val lock = Any()
    private val open = HashMap<String, Open>()

    /** True when today's files alone exceed the cap; the recorder stops appending. */
    @Volatile var overCap: Boolean = false
        private set

    fun append(kind: String, day: String, lines: List<String>, nowMs: Long) {
        if (lines.isEmpty() || kind !in RecordingFormat.GZ_KINDS) return
        synchronized(lock) {
            runCatching {
                val w = writerLocked(kind, day, nowMs) ?: return
                val sb = StringBuilder(lines.size * 64)
                for (line in lines) sb.append(line).append('\n')
                w.stream.write(sb.toString().toByteArray(Charsets.UTF_8))
            }.onFailure { closeLocked(kind) }
        }
    }

    fun appendSettle(day: String, lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val f = File(dir, RecordingFormat.fileName(RecordingFormat.KIND_SETTLE, day))
                val sb = StringBuilder()
                if (!f.exists() || f.length() == 0L) sb.append(RecordingFormat.SETTLE_HEADER).append('\n')
                for (line in lines) sb.append(line).append('\n')
                f.appendText(sb.toString(), Charsets.UTF_8)
            }
        }
    }

    /** Sync-flush open members; finish members older than [memberMaxAgeMs]. */
    fun flush(nowMs: Long) {
        synchronized(lock) {
            for (kind in open.keys.toList()) {
                val w = open[kind] ?: continue
                if (nowMs - w.openedAtMs >= memberMaxAgeMs) {
                    closeLocked(kind)
                } else {
                    runCatching { w.stream.flush() }.onFailure { closeLocked(kind) }
                }
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            for (kind in open.keys.toList()) closeLocked(kind)
        }
    }

    /**
     * Delete whole oldest days until the total is under the cap. Today is
     * never deleted; if today alone is over, [overCap] turns on.
     * Returns the deleted days.
     */
    fun enforceCap(today: String): List<String> = synchronized(lock) {
        val deleted = ArrayList<String>()
        runCatching {
            var days = daysLocked()
            var total = days.sumOf { it.bytes }
            while (total > maxTotalBytes) {
                val oldest = days.firstOrNull { it.day != today } ?: break
                for (kind in RecordingFormat.ALL_KINDS) {
                    if (open[kind]?.day == oldest.day) closeLocked(kind)
                    File(dir, RecordingFormat.fileName(kind, oldest.day)).delete()
                }
                deleted += oldest.day
                days = daysLocked()
                total = days.sumOf { it.bytes }
            }
            overCap = total > maxTotalBytes
        }
        deleted
    }

    /** Days on disk, oldest first. */
    fun days(): List<RecordingDay> = synchronized(lock) { runCatching { daysLocked() }.getOrDefault(emptyList()) }

    fun totalBytes(): Long = days().sumOf { it.bytes }

    /**
     * Zip the files of [days] (all days when empty) into [out]. Open members
     * are sync-flushed first, so the copy of today is readable up to now
     * (its last member may lack a trailer — see [RecordingFormat]).
     * Returns the number of files written.
     */
    fun zipDays(days: Collection<String>, out: OutputStream, nowMs: Long = System.currentTimeMillis()): Int {
        val wanted = days.toSet()
        // Flush + measure under the lock, copy outside it so the writer is
        // not stalled; each copy stops at the flushed length (a flush
        // boundary), so a concurrent append cannot leave half a block.
        val files: List<Pair<File, Long>> = synchronized(lock) {
            flush(nowMs)
            listFilesLocked()
                .filter { f ->
                    val parsed = RecordingFormat.parseFileName(f.name) ?: return@filter false
                    wanted.isEmpty() || parsed.second in wanted
                }
                .sortedBy { it.name }
                .map { it to it.length() }
        }
        if (files.isEmpty()) {
            runCatching { out.close() }
            return 0
        }
        var n = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            for ((f, len) in files) {
                runCatching {
                    zip.putNextEntry(ZipEntry("recordings/${f.name}").apply { time = f.lastModified() })
                    f.inputStream().use { input -> copyPrefix(input, zip, len) }
                    zip.closeEntry()
                    n += 1
                }
            }
        }
        return n
    }

    private fun copyPrefix(input: java.io.InputStream, out: OutputStream, len: Long) {
        val buf = ByteArray(BUFFER)
        var left = len
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r <= 0) break
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun writerLocked(kind: String, day: String, nowMs: Long): Open? {
        val cur = open[kind]
        if (cur != null && cur.day == day) return cur
        if (cur != null) closeLocked(kind)
        dir.mkdirs()
        val f = File(dir, RecordingFormat.fileName(kind, day))
        val fresh = !f.exists() || f.length() == 0L
        val stream = GZIPOutputStream(BufferedOutputStream(FileOutputStream(f, true), BUFFER), BUFFER, true)
        if (fresh) stream.write((RecordingFormat.header(kind) + "\n").toByteArray(Charsets.UTF_8))
        val w = Open(day, f, stream, nowMs)
        open[kind] = w
        return w
    }

    private fun closeLocked(kind: String) {
        val w = open.remove(kind) ?: return
        runCatching { w.stream.close() }
    }

    private fun listFilesLocked(): List<File> =
        dir.listFiles()?.filter { it.isFile && RecordingFormat.parseFileName(it.name) != null }.orEmpty()

    private fun daysLocked(): List<RecordingDay> =
        listFilesLocked()
            .groupBy { RecordingFormat.parseFileName(it.name)!!.second }
            .map { (day, fs) -> RecordingDay(day, fs.sumOf { it.length() }, fs.size) }
            .sortedBy { it.day }

    companion object {
        const val DEFAULT_MAX_BYTES = 300L * 1024L * 1024L
        const val DEFAULT_MEMBER_MAX_AGE_MS = 10L * 60_000L
        private const val BUFFER = 64 * 1024
    }
}
