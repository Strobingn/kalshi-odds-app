package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.signal.trade.LivePosition
import com.dirk.kalshiodds.signal.trade.PositionParser
import java.util.Locale
import kotlin.math.abs

/**
 * One readable line per open position. Display only.
 * Example: `BTC · 3:30 PM window · UP · 50 contracts · avg 32¢ · now 0.1¢ · -$15.95`
 */
object PositionCopy {
    const val NO_BUYERS = "No buyers right now"

    fun sellBlockedMessage(reason: String?): String =
        if (reason == com.dirk.kalshiodds.signal.trade.TicketBuilder.NO_BUYERS) {
            NO_BUYERS
        } else {
            reason?.trim().orEmpty()
        }

    fun row(pos: LivePosition): String {
        val window = WindowLabel.of(pos.ticker, pos.closeTimeEpochMs)
        val side = SignalCopy.callLabel(pos.side)
        val contracts = PositionParser.heldContracts(pos)
        val avg = pos.avgCost?.let { KalshiQuoteDisplay.formatPriceCents(it) } ?: "—"
        val now = pos.bestBid?.let { KalshiQuoteDisplay.formatPriceCents(it) } ?: "—"
        return "$window · $side · $contracts contracts · avg $avg · now $now · ${money(pos.unrealizedPnlUsd)}"
    }

    fun money(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return "—"
        return if (v < 0.0) {
            String.format(Locale.US, "-$%.2f", abs(v))
        } else {
            String.format(Locale.US, "+$%.2f", v)
        }
    }
}
