package com.dirk.kalshiodds.signal.engine

/**
 * Local YES/NO depth for one crypto market, rebuilt from Kalshi
 * `orderbook_snapshot` + incremental `orderbook_delta`.
 *
 * YES levels are bids (buy YES). NO levels are bids for NO, equivalent to
 * YES asks at `(1 − noPrice)`. Analysis only — never places orders.
 */
class LocalOrderBook {
    private val yes = java.util.TreeMap<Double, Double>()
    private val no = java.util.TreeMap<Double, Double>()
    var lastSeq: Int? = null
        private set
    var sawGap: Boolean = false
        private set

    fun isEmpty(): Boolean = yes.isEmpty() && no.isEmpty()

    fun clear() {
        yes.clear()
        no.clear()
        lastSeq = null
        sawGap = false
    }

    fun replaceSnapshot(
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int? = null
    ) {
        yes.clear()
        no.clear()
        for ((price, size) in yesLevels) putLevel(yes, price, size)
        for ((price, size) in noLevels) putLevel(no, price, size)
        lastSeq = seq
        sawGap = false
    }

    /**
     * Apply one incremental size change. Returns false when [seq] skips ahead
     * of [lastSeq] (caller should wait for a fresh snapshot).
     */
    fun applyDelta(price: Double, delta: Double, side: String, seq: Int? = null): Boolean {
        if (seq != null && lastSeq != null && seq > lastSeq!! + 1) {
            sawGap = true
            return false
        }
        val book = when (side.lowercase()) {
            "yes" -> yes
            "no" -> no
            else -> return true
        }
        val next = (book[price] ?: 0.0) + delta
        putLevel(book, price, next)
        if (seq != null) lastSeq = seq
        return true
    }

    fun bestYesBid(): Double? = if (yes.isEmpty()) null else yes.lastKey()

    /** Tightest YES offer implied by the best NO bid. */
    fun bestYesAsk(): Double? = if (no.isEmpty()) null else (1.0 - no.lastKey()).coerceIn(0.0, 1.0)

    fun mid01(): Double? {
        val bid = bestYesBid()
        val ask = bestYesAsk()
        return when {
            bid != null && ask != null -> (bid + ask) / 2.0
            bid != null -> bid
            ask != null -> ask
            else -> null
        }
    }

    /**
     * Bid vs ask pressure in `[-1, 1]`:
     * `(bidSize − askSize) / (bidSize + askSize)`.
     *
     * Prefers size within [bandCents] of mid (YES-price axis). Falls back to
     * the top [topLevels] rungs when the band is empty.
     */
    fun imbalance(bandCents: Double = DEFAULT_BAND_CENTS, topLevels: Int = DEFAULT_TOP_LEVELS): Double? {
        val mid = mid01()
        val band = (bandCents / 100.0).coerceAtLeast(0.0)
        var bidSize = 0.0
        var askSize = 0.0
        if (mid != null && band > 0.0) {
            val lo = mid - band
            val hi = mid + band
            for ((price, size) in yes) {
                if (price >= lo) bidSize += size
            }
            for ((noPrice, size) in no) {
                val yesAsk = 1.0 - noPrice
                if (yesAsk <= hi) askSize += size
            }
        }
        if (bidSize + askSize < EPS) {
            bidSize = topSize(yes, topLevels)
            askSize = topSize(no, topLevels)
        }
        val denom = bidSize + askSize
        if (denom < EPS) return null
        return ((bidSize - askSize) / denom).coerceIn(-1.0, 1.0)
    }

    fun yesLevels(): List<Pair<Double, Double>> = yes.entries.map { it.key to it.value }
    fun noLevels(): List<Pair<Double, Double>> = no.entries.map { it.key to it.value }

    companion object {
        const val DEFAULT_BAND_CENTS = 3.0
        const val DEFAULT_TOP_LEVELS = 3
        private const val EPS = 1e-9

        /**
         * `(bidSize − askSize) / (bidSize + askSize)` for caller-supplied
         * near-touch sizes. Exposed so unit tests can lock the formula.
         */
        fun signedImbalance(bidSize: Double, askSize: Double): Double? {
            val denom = bidSize + askSize
            if (denom < EPS) return null
            return ((bidSize - askSize) / denom).coerceIn(-1.0, 1.0)
        }

        /**
         * Mid velocity in probability units per second: `Δmid / Δt`.
         * [midsNewestLast] is chronological (oldest first).
         */
        fun velocityPerSec(midsNewestLast: List<Double>, timesMs: List<Long>): Double? {
            if (midsNewestLast.size < 2 || timesMs.size != midsNewestLast.size) return null
            val dtMs = timesMs.last() - timesMs.first()
            if (dtMs < 1L) return null
            return (midsNewestLast.last() - midsNewestLast.first()) / (dtMs / 1000.0)
        }

        /**
         * Short acceleration: recent-half velocity minus older-half velocity
         * (probability units per second).
         */
        fun accelerationPerSec(midsNewestLast: List<Double>, timesMs: List<Long>): Double? {
            if (midsNewestLast.size < 4 || timesMs.size != midsNewestLast.size) return null
            val mid = midsNewestLast.size / 2
            val older = velocityPerSec(midsNewestLast.subList(0, mid), timesMs.subList(0, mid))
                ?: return null
            val recent = velocityPerSec(
                midsNewestLast.subList(mid, midsNewestLast.size),
                timesMs.subList(mid, timesMs.size)
            ) ?: return null
            return recent - older
        }

        private fun putLevel(book: MutableMap<Double, Double>, price: Double, size: Double) {
            if (!price.isFinite() || price < 0.0) return
            if (!size.isFinite() || size <= EPS) {
                book.remove(price)
            } else {
                book[price] = size
            }
        }

        private fun topSize(book: java.util.NavigableMap<Double, Double>, n: Int): Double {
            if (n <= 0 || book.isEmpty()) return 0.0
            var sum = 0.0
            var left = n
            val it = book.descendingMap().entries.iterator()
            while (it.hasNext() && left > 0) {
                sum += it.next().value
                left -= 1
            }
            return sum
        }
    }
}
