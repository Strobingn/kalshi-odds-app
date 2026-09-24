package com.dirk.kalshiodds.signal.engine

/**
 * Order-book deltas can arrive tens of times per second. Full Heavy ML
 * on every delta OOMs / ANRs the process. Latest-wins + min interval.
 */
object BookScoreGate {
    const val MIN_INTERVAL_MS = 400L
    const val LIGHT_INTERVAL_MS = 80L
    const val HEAVY_INTERVAL_MS = 400L

    fun intervalMs(heavyEnabled: Boolean): Long =
        if (heavyEnabled) HEAVY_INTERVAL_MS else LIGHT_INTERVAL_MS

    fun shouldPublish(
        ticker: String,
        nowMs: Long,
        lastByTicker: MutableMap<String, Long>,
        minIntervalMs: Long = MIN_INTERVAL_MS
    ): Boolean {
        val last = lastByTicker[ticker] ?: 0L
        if (nowMs - last < minIntervalMs) return false
        lastByTicker[ticker] = nowMs
        return true
    }
}
