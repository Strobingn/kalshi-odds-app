package com.dirk.kalshiodds.signal.engine

/**
 * Order-book deltas can arrive tens of times per second. Full Heavy ML
 * on every delta OOMs / ANRs the process. Latest-wins + min interval.
 */
object BookScoreGate {
    const val MIN_INTERVAL_MS = 400L

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
