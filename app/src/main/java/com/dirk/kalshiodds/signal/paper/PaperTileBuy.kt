package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.HomeCopy
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Card-level $10 paper buy. Same contract / fee / profit math as
 * [HomeCopy.tenDollarWins]. Never touches Kalshi portfolio or the
 * live order client.
 */
object PaperTileBuy {
    const val SOURCE = "tile $10"
    const val NO_ASK_UP = "No ask to paper UP"
    const val NO_ASK_DOWN = "No ask to paper DOWN"

    fun sideKey(side: String): String =
        if (side.equals("NO", true) || side.equals("DOWN", true)) "NO" else "YES"

    fun displaySide(side: String): String =
        if (sideKey(side) == "NO") "DOWN" else "UP"

    fun askOf(market: MarketUiModel, side: String): Double? {
        val quotes = MarketQuoteView.of(market)
        return if (sideKey(side) == "NO") quotes.noAsk else quotes.yesAsk
    }

    fun sized(market: MarketUiModel, side: String): HomeCopy.TenDollarWins =
        HomeCopy.tenDollarWins(askOf(market, side))

    fun enabled(market: MarketUiModel, side: String): Boolean = sized(market, side).hasAsk

    fun disabledReason(market: MarketUiModel, side: String): String? {
        if (enabled(market, side)) return null
        return if (sideKey(side) == "NO") NO_ASK_DOWN else NO_ASK_UP
    }

    fun openFill(paper: PaperBookState, ticker: String): PaperFill? =
        paper.fills.firstOrNull { !it.settled && it.ticker.equals(ticker, ignoreCase = true) }

    fun positionLine(fill: PaperFill?): String? {
        if (fill == null || fill.settled) return null
        val cents = (fill.limitPrice * 100.0).roundToInt()
        return "Paper: ${displaySide(fill.side)} ${fill.contracts} @ ${cents}c"
    }

    fun confirmMessage(side: String, sized: HomeCopy.TenDollarWins): String {
        val profit = sized.profitUsd ?: 0.0
        return String.format(
            Locale.US,
            "Paper %s · %d ct · $%.2f · +$%.2f if it wins",
            displaySide(side),
            sized.contracts,
            sized.costUsd,
            profit
        )
    }

    /**
     * Write one $10 paper fill at the live best ask. Isolated from
     * [com.dirk.kalshiodds.data.api.KalshiTradeClient] and portfolio GETs.
     */
    fun place(book: PaperBook, market: MarketUiModel, side: String): PaperBuy.Outcome {
        val want = sideKey(side)
        val clip = sized(market, side)
        if (!clip.hasAsk || clip.ask == null || clip.profitUsd == null) {
            return PaperBuy.Outcome(
                ok = false,
                message = disabledReason(market, side) ?: HomeCopy.TEN_WINS_DASH
            )
        }
        return book.fillTenDollar(
            ticker = market.ticker,
            side = want,
            ask = clip.ask,
            contracts = clip.contracts,
            feeUsd = clip.feeUsd,
            allInUsd = clip.costUsd,
            source = SOURCE,
            message = confirmMessage(want, clip)
        )
    }
}
