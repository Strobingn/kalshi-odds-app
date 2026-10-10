package com.dirk.kalshiodds.crash

import java.util.Locale

/** 0.3.52 heap line for Data and crash reports. largeHeap is only a safety margin; the leaks are fixed. */
object HeapInfo {
    data class Snap(val usedBytes: Long, val maxBytes: Long) {
        val usedPct: Double get() = if (maxBytes > 0) usedBytes * 100.0 / maxBytes else 0.0
    }

    fun now(): Snap {
        val rt = Runtime.getRuntime()
        return Snap(rt.totalMemory() - rt.freeMemory(), rt.maxMemory())
    }

    fun line(s: Snap = now()): String =
        String.format(Locale.US, "Heap %d MB used / %d MB max (%.0f%%)", s.usedBytes shr 20, s.maxBytes shr 20, s.usedPct)
}
