package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import kotlin.math.floor

/**
 * Real ask-side size for paper Kelly. Never invents depth from volume /
 * open interest / liquidity. Unknown depth → skip the fill.
 *
 *  1. Live order book: contracts at or below the ask being paid.
 *  2. Displayed best-ask size (`yes_ask_size_fp`) when the book is empty.
 *  3. Otherwise null — caller must skip.
 */
object PaperAskDepth {

    fun contracts(
        side: String,
        ask: Double,
        book: BookLevelSnapshot? = null,
        market: MarketUiModel? = null
    ): Int? {
        val px = KalshiPrice.usable(ask) ?: return null
        val fromBook = fromBook(side, px, book)
        if (fromBook != null) return fromBook
        return displayedBestAskSize(side, market)
    }

    /**
     * Sum of size on ask levels with price ≤ [ask]. Returns 0 when the book
     * is present but nothing sits at that price. Returns null when there is
     * no book at all.
     */
    fun fromBook(side: String, ask: Double, book: BookLevelSnapshot?): Int? {
        if (book == null || book.isEmpty()) return null
        val px = KalshiPrice.usable(ask) ?: return 0
        val wantYes = !side.equals("NO", true)
        val levels = if (wantYes) {
            book.no.mapNotNull { (noPx, size) ->
                KalshiPrice.impliedAskFromOppositeBid(noPx)?.let { it to size }
            }
        } else {
            book.yes.mapNotNull { (yesPx, size) ->
                KalshiPrice.impliedAskFromOppositeBid(yesPx)?.let { it to size }
            }
        }
        val contracts = levels.filter { it.first <= px + 1e-9 }.sumOf { it.second }
        return floor(contracts + 1e-9).toInt().coerceAtLeast(0)
    }

    fun displayedBestAskSize(side: String, market: MarketUiModel?): Int? {
        if (market == null) return null
        if (side.equals("NO", true)) return null
        val n = market.yesAskSize
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { floor(it + 1e-9).toInt() }
            ?: return null
        return n.takeIf { it > 0 }
    }
}
