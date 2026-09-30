package com.dirk.kalshiodds.signal.d3

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import kotlin.math.floor

/**
 * Bid-side queue and depth for a D3 resting limit. YES bids are the YES
 * book; NO bids are the NO book (YES asks at `1 − noPrice`).
 */
object D3Book {

    /**
     * Contracts already resting at [limit] on [side] when we join.
     * Improving the bid (new best) is 0 ahead.
     */
    fun sizeAhead(
        side: String,
        limit: Double,
        quote: D3Quote,
        book: BookLevelSnapshot?
    ): Double {
        val p = KalshiPrice.usable(limit) ?: return 0.0
        val levels = bidLevels(side, book)
        if (levels.isNotEmpty()) {
            val at = levels.filter { kotlin.math.abs(it.first - p) < 1e-9 }.sumOf { it.second }
            val better = levels.filter { it.first > p + 1e-9 }.sumOf { it.second }
            return (at + better).coerceAtLeast(0.0)
        }
        val quoted = quotedBidSize(side, quote)
        val best = D3Strategy.bestBid(quote, side)
        return if (best != null && kotlin.math.abs(best - p) < 1e-9) {
            quoted ?: 0.0
        } else {
            0.0
        }
    }

    /** Visible size we may rest without exceeding the book-depth cap. */
    fun depthCap(side: String, quote: D3Quote, book: BookLevelSnapshot?): Int? {
        val levels = bidLevels(side, book)
        if (levels.isNotEmpty()) {
            val raw = levels.sumOf { it.second }
            return floor(raw + 1e-9).toInt().coerceAtLeast(0).takeIf { it > 0 }
                ?: quotedAskSize(side, quote)?.let { floor(it + 1e-9).toInt() }
        }
        val quoted = quotedAskSize(side, quote) ?: quotedBidSize(side, quote) ?: return null
        return floor(quoted + 1e-9).toInt().coerceAtLeast(0)
    }

    fun bidLevels(side: String, book: BookLevelSnapshot?): List<Pair<Double, Double>> {
        if (book == null || book.isEmpty()) return emptyList()
        val yesSide = !side.equals("NO", true)
        val raw = if (yesSide) book.yes else book.no
        return raw.mapNotNull { (px, size) ->
            KalshiPrice.usable(px)?.let { it to size }
        }.sortedByDescending { it.first }
    }

    private fun quotedBidSize(side: String, quote: D3Quote): Double? =
        if (side.equals("NO", true)) quote.noBidSize else quote.yesBidSize

    private fun quotedAskSize(side: String, quote: D3Quote): Double? =
        if (side.equals("NO", true)) quote.noAskSize else quote.yesAskSize
}
