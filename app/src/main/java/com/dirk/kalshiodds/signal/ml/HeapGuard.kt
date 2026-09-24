package com.dirk.kalshiodds.signal.ml

/**
 * 0.3.0 on SM-S928U died at the 256MB growth limit with <1% free after GC.
 * Skip Heavy ML / Extended AI when the heap is already tight so a single
 * infer cannot push us over.
 */
object HeapGuard {
    const val TIGHT_RATIO = 0.80
    const val CRITICAL_RATIO = 0.90

    fun usedBytes(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
    }

    fun maxBytes(): Long = Runtime.getRuntime().maxMemory().coerceAtLeast(1L)

    fun usedRatio(): Double = usedBytes().toDouble() / maxBytes().toDouble()

    fun isTight(threshold: Double = TIGHT_RATIO): Boolean = usedRatio() >= threshold

    fun isCritical(): Boolean = usedRatio() >= CRITICAL_RATIO
}
