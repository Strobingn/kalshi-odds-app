package com.dirk.kalshiodds.chart

/**
 * Per-card chart series so a StateFlow emission can paint from memory.
 * The SQLite read that fills an entry runs on a caller-supplied IO dispatcher.
 */
class CardSeriesCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    data class Entry(
        val closeMs: Long,
        val bids: List<BidPoint>,
        val settledYes: List<Boolean>,
        val atMs: Long
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun get(ticker: String, closeMs: Long): Entry? =
        entries[ticker]?.takeIf { it.closeMs == closeMs }

    fun fresh(ticker: String, closeMs: Long, now: Long = nowMs()): Boolean {
        val entry = get(ticker, closeMs) ?: return false
        return now - entry.atMs < ttlMs
    }

    fun put(
        ticker: String,
        closeMs: Long,
        bids: List<BidPoint>,
        settledYes: List<Boolean>,
        now: Long = nowMs()
    ) {
        entries[ticker] = Entry(closeMs, bids, settledYes, now)
    }

    fun invalidate(ticker: String) {
        entries.remove(ticker)
    }

    companion object {
        const val DEFAULT_TTL_MS = 2_000L
    }
}
