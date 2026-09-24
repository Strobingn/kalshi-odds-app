package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.KalshiPrice

/**
 * Rejects empty, placeholder, crossed, and stale quotes so a single
 * 0.0000 / 1.0000 / one-sided print cannot flip the hero or the chart.
 */
object QuoteSanity {

    /** Ignore a print older than this when a newer mid exists. */
    const val STALE_MS = 8_000L

    fun isPlaceholder(price: Double?): Boolean {
        if (price == null || !price.isFinite()) return true
        if (price <= 0.0 + 1e-12 || price >= 1.0 - 1e-12) return true
        return KalshiPrice.usable(price) == null
    }

    fun isCrossed(bid: Double?, ask: Double?): Boolean {
        val b = KalshiPrice.usable(bid) ?: return false
        val a = KalshiPrice.usable(ask) ?: return false
        return b > a + 1e-9
    }

    /** Best bid/ask after dropping placeholders and crossed books. */
    fun usablePair(bid: Double?, ask: Double?): Pair<Double?, Double?> {
        val b = if (isPlaceholder(bid)) null else KalshiPrice.usable(bid)
        val a = if (isPlaceholder(ask)) null else KalshiPrice.usable(ask)
        if (isCrossed(b, a)) return null to null
        return b to a
    }

    fun isStale(printMs: Long?, nowMs: Long, lastGoodMs: Long? = null): Boolean {
        if (printMs == null) return true
        if (nowMs - printMs > STALE_MS) return true
        if (lastGoodMs != null && printMs + 1 < lastGoodMs) return true
        return false
    }

    /**
     * Mid of usable bid/ask, else last trade, else null.
     * Never returns 0 or 1.
     */
    fun robustMid(bid: Double?, ask: Double?, last: Double? = null): Double? {
        val (b, a) = usablePair(bid, ask)
        if (b != null && a != null) return ((b + a) / 2.0).coerceIn(KalshiPrice.MIN_TICK_DOLLARS, KalshiPrice.MAX_TICK_DOLLARS)
        if (a != null) return a
        if (b != null) return b
        return if (isPlaceholder(last)) null else KalshiPrice.usable(last)
    }

    /** Short median of recent good mids — one tick cannot move the needle. */
    fun medianFilter(values: List<Double>, window: Int = 5): Double? {
        val clean = values.filter { !isPlaceholder(it) }.takeLast(window.coerceAtLeast(1))
        if (clean.isEmpty()) return null
        val s = clean.sorted()
        return s[s.size / 2]
    }

    fun usableCents(cents: Float?): Float? {
        if (cents == null || !cents.isFinite()) return null
        if (cents <= 0.05f || cents >= 99.95f) return null
        return cents
    }
}
