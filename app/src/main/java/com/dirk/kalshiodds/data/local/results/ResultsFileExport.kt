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
    fun write(context: Context, csv: String, nowMs: Long = System.currentTimeMillis(),
              prefix: String = "diphunter-results"): ExportResult {
        require(prefix.matches(Regex("[a-z0-9-]+"))) { "Invalid export name" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val name = "$prefix-$stamp.csv"
        val local = runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "export")
            dir.mkdirs()
            val f = File(dir, name)
            f.writeText(csv)
            f
        }.getOrNull()
        val media = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { writeMediaStore(context, name, csv) }.getOrNull()
        } else {
            null
        }
        val path = media ?: local?.absolutePath
        return if (path != null) {
            ExportResult(true, path, "Saved $name — $path")
        } else {
            ExportResult(false, null, "Export failed — could not write a file")
        }
    }

    private fun writeMediaStore(context: Context, name: String, csv: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DipHunter-GTP")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/DipHunter-GTP/$name"
    }
}
