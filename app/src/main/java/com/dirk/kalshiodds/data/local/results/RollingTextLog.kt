package com.dirk.kalshiodds.data.local.results

import java.io.File
import java.nio.charset.Charset

/**
 * Append-only `results.log` with a simple size cap. Never throws to the caller.
 */
class RollingTextLog(
    private val file: File?,
    private val maxBytes: Long = 256_000L
) {
    private val lock = Any()

    fun append(line: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                target.parentFile?.mkdirs()
                if (target.exists() && target.length() > maxBytes) {
                    rotate(target)
                }
                target.appendText(line.trimEnd() + "\n", Charset.forName("UTF-8"))
            }
        }
    }

    fun path(): String? = file?.absolutePath

    private fun rotate(target: File) {
        val bak = File(target.parentFile, target.name + ".1")
        runCatching { if (bak.exists()) bak.delete() }
        runCatching { target.renameTo(bak) }
    }
}
