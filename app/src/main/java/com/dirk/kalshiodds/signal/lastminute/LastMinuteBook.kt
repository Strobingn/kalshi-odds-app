package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import kotlin.math.floor

/**
 * Walk the live YES/NO book and count contracts resting **at or below**
 * the taker ask [limitP]. Matches Kalshi's inverted book:
 * YES asks = `1 − NO bids`; NO asks = `1 − YES bids`.
 */
object LastMinuteBook {

    /**
     * Whole contracts available at or below [limitP] on [side] (YES/NO).
     * Null when there is no book to walk (caller should not claim a depth cap).
     */
    fun depthAtOrBelow(
        side: String,
        limitP: Double,
        book: BookLevelSnapshot?,
        quotedSize: Double? = null
    ): Int? {
        val p = KalshiPrice.usable(limitP) ?: return 0
        val levels = askLevels(side, book)
        if (levels.isNotEmpty()) {
            val raw = levels.filter { it.first <= p + 1e-9 }.sumOf { it.second }
            return floor(raw + 1e-9).toInt().coerceAtLeast(0)
        }
        val quoted = quotedSize?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return floor(quoted + 1e-9).toInt().coerceAtLeast(0)
    }

    fun askLevels(side: String, book: BookLevelSnapshot?): List<Pair<Double, Double>> {
        if (book == null || book.isEmpty()) return emptyList()
        val yesSide = !side.equals("NO", true)
        val raw = if (yesSide) book.no else book.yes
        return raw.mapNotNull { (px, size) ->
            KalshiPrice.impliedAskFromOppositeBid(px)?.let { it to size }
        }.sortedBy { it.first }
    }

    fun capContracts(want: Int, depth: Int?): Pair<Int, Boolean> {
        val w = want.coerceAtLeast(0)
        if (depth == null) return w to false
        val capped = minOf(w, depth.coerceAtLeast(0))
        return capped to (w > 0 && depth < w)
    }
}
