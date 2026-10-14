package com.dirk.kalshiodds.data.local.results

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportResult(
    val ok: Boolean,
    val path: String?,
    val message: String
)

/**
 * Writes a CSV under app files + MediaStore Downloads (API 29+) so the
 * user can open it after a crash. Never throws.
 */
object ResultsFileExport {
    fun write(
        context: Context,
        csv: String,
        nowMs: Long = System.currentTimeMillis(),
        namePrefix: String = "diphunter-results"
    ): ExportResult = writeStream(context, nowMs, namePrefix, gzip = false) { it.write(csv) }

    /**
     * 0.3.69: streaming export. [body] writes into a buffered writer backed by the file (gzip when [gzip]); the file
     * is then stream-copied into MediaStore Downloads. Nothing holds the whole export in memory. Never throws.
     */
    fun writeStream(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        namePrefix: String = "diphunter-results",
        gzip: Boolean = false,
        body: (java.io.Writer) -> Unit
    ): ExportResult {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val name = "$namePrefix-$stamp.csv" + if (gzip) ".gz" else ""
        var err: Throwable? = null
        val local = runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "export")
            dir.mkdirs()
            File(dir, name).also { f -> writeTo(f, gzip, body) }
        }.onFailure { err = it }.getOrNull()
        if (local == null) return ExportResult(false, null, "Export failed — ${err?.message ?: "could not write a file"}")
        val media = if (Build.VERSION.SDK_INT >= 29) runCatching { copyToMediaStore(context, name, local, gzip) }.getOrNull() else null
        val path = media ?: local.absolutePath
        return ExportResult(true, path, "Saved $name (${local.length() / 1024} KB) — $path")
    }

    /** Plain JVM part (unit-testable): stream [body] into [f], optionally gzip. */
    fun writeTo(f: File, gzip: Boolean, body: (java.io.Writer) -> Unit) {
        val raw: java.io.OutputStream = java.io.FileOutputStream(f)
        val os = if (gzip) java.util.zip.GZIPOutputStream(raw, 64 * 1024) else raw
        java.io.BufferedWriter(java.io.OutputStreamWriter(os, Charsets.UTF_8), 64 * 1024).use { body(it) }
    }

    private fun copyToMediaStore(context: Context, name: String, src: File, gzip: Boolean): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (gzip) "application/gzip" else "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DipHunter")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/DipHunter/$name"
    }
}
