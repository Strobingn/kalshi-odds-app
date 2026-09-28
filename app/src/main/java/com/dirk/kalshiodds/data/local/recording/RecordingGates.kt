package com.dirk.kalshiodds.data.local.recording

import com.dirk.kalshiodds.signal.engine.TopOfBook

/**
 * At most one spot row per product per [windowMs] bucket (epoch-aligned),
 * keeping the **latest** print in the bucket. A print in a later bucket
 * releases the held one; [drainDue] releases buckets that have closed.
 * Thread-safe: prints arrive on the Coinbase reader thread, drains on the
 * recorder's writer.
 */
class SpotThrottle(private val windowMs: Long = 250L) {
    data class Print(val tsMs: Long, val product: String, val price: Double)

    private val held = HashMap<String, Print>()

    /** Returns the print released by this one (previous bucket), if any. */
    @Synchronized
    fun offer(tsMs: Long, product: String, price: Double): Print? {
        if (!price.isFinite() || price <= 0.0) return null
        val prev = held[product]
        val next = Print(tsMs, product, price)
        if (prev == null) {
            held[product] = next
            return null
        }
        return if (bucket(tsMs) == bucket(prev.tsMs)) {
            // Same window: keep the latest (clock can step back — keep newest arrival).
            held[product] = next
            null
        } else {
            held[product] = next
            prev
        }
    }

    /** Held prints whose bucket ended before [nowMs] (or all when [all]). */
    @Synchronized
    fun drainDue(nowMs: Long, all: Boolean = false): List<Print> {
        if (held.isEmpty()) return emptyList()
        val out = ArrayList<Print>()
        val it = held.entries.iterator()
        while (it.hasNext()) {
            val p = it.next().value
            if (all || bucket(nowMs) != bucket(p.tsMs)) {
                out += p
                it.remove()
            }
        }
        out.sortBy { it.tsMs }
        return out
    }

    private fun bucket(tsMs: Long): Long = Math.floorDiv(tsMs, windowMs)
}

/**
 * Book-row cadence per ticker: write when the top of book changed and at
 * least [minIntervalMs] passed since the last row, or as a heartbeat after
 * [heartbeatMs] without a row. Called only from the recorder's writer.
 */
class BookRowGate(
    private val minIntervalMs: Long = 1_000L,
    private val heartbeatMs: Long = 5_000L
) {
    private data class Last(val tsMs: Long, val top: TopOfBook)

    private val last = HashMap<String, Last>()

    fun shouldWrite(ticker: String, top: TopOfBook, nowMs: Long): Boolean {
        val prev = last[ticker] ?: return true
        val age = nowMs - prev.tsMs
        if (age < 0L) return false
        if (age >= heartbeatMs) return true
        return age >= minIntervalMs && prev.top != top
    }

    fun markWritten(ticker: String, top: TopOfBook, nowMs: Long) {
        last[ticker] = Last(nowMs, top)
    }

    fun retain(tickers: Set<String>) {
        last.keys.retainAll(tickers)
    }
}
