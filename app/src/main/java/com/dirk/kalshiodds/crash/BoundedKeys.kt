package com.dirk.kalshiodds.crash

/**
 * 0.3.84 memory cap: per-ticker in-memory maps (15-minute windows mint ~288 new tickers a day) were never
 * evicted, so a long always-on session grew without limit. Drops the oldest keys once a map exceeds [max].
 * Kalshi tickers embed the window date/time, so lexicographic order within a series is chronological.
 * In-memory caches only; never touches stored data.
 */
object BoundedKeys {
    const val DEFAULT_MAX = 192

    fun <V> trim(map: MutableMap<String, V>, max: Int = DEFAULT_MAX) {
        if (map.size <= max) return
        val drop = map.size - max * 3 / 4 // hysteresis: trim to 75% so we do not sort on every write
        runCatching { map.keys.sorted().take(drop).forEach { map.remove(it) } }
    }
}
