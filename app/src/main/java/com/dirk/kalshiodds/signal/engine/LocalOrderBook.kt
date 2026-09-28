package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.tanh

/**
 * Local YES/NO depth for one crypto market, rebuilt from Kalshi
 * `orderbook_snapshot` + incremental `orderbook_delta`.
 *
 * YES levels are bids (buy YES). NO levels are bids for NO, equivalent to
 * YES asks at `(1 − noPrice)`. Analysis only — never places orders.
 *
 * Also tracks depth near mid, depth decay, and a decaying pulse for
 * sudden quote pulls / large cancel spikes.
 */
/** Immutable YES/NO rungs copied off the live TreeMap for UI / tickets. */
data class BookLevelSnapshot(
    val yes: List<Pair<Double, Double>> = emptyList(),
    val no: List<Pair<Double, Double>> = emptyList()
) {
    fun isEmpty(): Boolean = yes.isEmpty() && no.isEmpty()
}

/**
 * Top of book in dollars (0–1) with contract quantities, both sides.
 * YES ask = 1 − best NO bid (size = that NO bid's size); NO ask = 1 − best
 * YES bid (size = that YES bid's size) — the same derivation as
 * [LocalOrderBook.bestYesAsk] / [LocalOrderBook.bestNoAsk]. A side with no
 * levels leaves its fields null. Quantities are null when the quote came
 * from a ticker print rather than the local book.
 */
data class TopOfBook(
    val yesBid: Double? = null,
    val yesBidQty: Double? = null,
    val yesAsk: Double? = null,
    val yesAskQty: Double? = null,
    val noBid: Double? = null,
    val noBidQty: Double? = null,
    val noAsk: Double? = null,
    val noAskQty: Double? = null
) {
    fun isEmpty(): Boolean = yesBid == null && yesAsk == null && noBid == null && noAsk == null
}

class LocalOrderBook {
    data class Pulse(
        /** [-1, 1] — negative = YES-side cancels (bid support withdrawn). */
        val cancelSpike: Double = 0.0,
        /** [-1, 1] — negative = bid pulled; positive = ask pulled. */
        val quotePull: Double = 0.0
    )

    private val yes = java.util.TreeMap<Double, Double>()
    private val no = java.util.TreeMap<Double, Double>()
    var lastSeq: Int? = null
        private set
    var sawGap: Boolean = false
        private set
    private var lastBid: Double? = null
    private var lastAsk: Double? = null
    private var cancelEma: Double = 0.0
    private var pullEma: Double = 0.0

    @Synchronized
    fun isEmpty(): Boolean = yes.isEmpty() && no.isEmpty()

    @Synchronized
    fun clear() {
        yes.clear()
        no.clear()
        lastSeq = null
        sawGap = false
        lastBid = null
        lastAsk = null
        cancelEma = 0.0
        pullEma = 0.0
    }

    @Synchronized
    fun pulse(): Pulse = Pulse(cancelSpike = cancelEma, quotePull = pullEma)

    @Synchronized
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
        noteQuoteMove()
    }

    /**
     * Apply one incremental size change. Returns false when [seq] skips ahead
     * of [lastSeq] (caller should wait for a fresh snapshot).
     */
    @Synchronized
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
        if (delta <= -SignalConstants.CANCEL_SPIKE_SIZE) {
            val signed = if (side.lowercase() == "yes") -1.0 else 1.0
            val mag = tanh((-delta) / 40.0)
            cancelEma = (0.72 * cancelEma + 0.28 * signed * mag).coerceIn(-1.0, 1.0)
        } else {
            cancelEma *= 0.96
        }
        noteQuoteMove()
        return true
    }

    @Synchronized
    fun bestYesBid(): Double? = if (yes.isEmpty()) null else yes.lastKey()

    /** Tightest YES offer implied by the best NO bid. */
    @Synchronized
    fun bestYesAsk(): Double? = if (no.isEmpty()) null else (1.0 - no.lastKey()).coerceIn(0.0, 1.0)

    /** Highest NO buy offer. Official orderbook lists bids only. */
    @Synchronized
    fun bestNoBid(): Double? = if (no.isEmpty()) null else no.lastKey()

    /** Tightest NO offer implied by the best YES bid: $1 − best YES bid. */
    @Synchronized
    fun bestNoAsk(): Double? = if (yes.isEmpty()) null else (1.0 - yes.lastKey()).coerceIn(0.0, 1.0)

    /** Best rung on each side in one lock hold (see [TopOfBook]). */
    @Synchronized
    fun top(): TopOfBook {
        val yesTop = if (yes.isEmpty()) null else yes.lastEntry()
        val noTop = if (no.isEmpty()) null else no.lastEntry()
        return TopOfBook(
            yesBid = yesTop?.key,
            yesBidQty = yesTop?.value,
            yesAsk = noTop?.let { (1.0 - it.key).coerceIn(0.0, 1.0) },
            yesAskQty = noTop?.value,
            noBid = noTop?.key,
            noBidQty = noTop?.value,
            noAsk = yesTop?.let { (1.0 - it.key).coerceIn(0.0, 1.0) },
            noAskQty = yesTop?.value
        )
    }

    @Synchronized
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
    @Synchronized
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

    /** Total size (YES bids + implied YES asks) within [bandCents] of mid. */
    @Synchronized
    fun depthNearMid(bandCents: Double = SignalConstants.DEPTH_NEAR_CENTS): Double {
        val mid = mid01() ?: return 0.0
        val band = (bandCents / 100.0).coerceAtLeast(0.0)
        var size = 0.0
        for ((price, qty) in yes) {
            if (price >= mid - band) size += qty
        }
        for ((noPrice, qty) in no) {
            val yesAsk = 1.0 - noPrice
            if (yesAsk <= mid + band) size += qty
        }
        return size
    }

    /**
     * Near-mid size / far-mid size in `[0, 1]`. High = size concentrated
     * at the touch (better displayed liquidity). Null when the book is empty.
     */
    @Synchronized
    fun depthDecay(
        nearCents: Double = SignalConstants.DEPTH_NEAR_CENTS,
        farCents: Double = SignalConstants.DEPTH_FAR_CENTS
    ): Double? {
        val far = depthNearMid(farCents)
        if (far < EPS) return null
        val near = depthNearMid(nearCents)
        return (near / far).coerceIn(0.0, 1.0)
    }

    @Synchronized
    fun yesLevels(): List<Pair<Double, Double>> = yes.entries.map { it.key to it.value }

    @Synchronized
    fun noLevels(): List<Pair<Double, Double>> = no.entries.map { it.key to it.value }

    /** Copy rungs so another thread can read them without racing [applyDelta]. */
    @Synchronized
    fun snapshot(): BookLevelSnapshot = BookLevelSnapshot(yes = yesLevelsUnlocked(), no = noLevelsUnlocked())

    private fun yesLevelsUnlocked(): List<Pair<Double, Double>> = yes.entries.map { it.key to it.value }

    private fun noLevelsUnlocked(): List<Pair<Double, Double>> = no.entries.map { it.key to it.value }

    private fun noteQuoteMove() {
        val bid = bestYesBid()
        val ask = bestYesAsk()
        var pull = 0.0
        val prevBid = lastBid
        val prevAsk = lastAsk
        if (prevBid != null && bid != null && bid < prevBid - 1e-6) pull -= 1.0
        if (prevAsk != null && ask != null && ask > prevAsk + 1e-6) pull += 1.0
        pullEma = if (pull != 0.0) {
            (0.70 * pullEma + 0.30 * pull).coerceIn(-1.0, 1.0)
        } else {
            pullEma * 0.96
        }
        lastBid = bid
        lastAsk = ask
    }

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
