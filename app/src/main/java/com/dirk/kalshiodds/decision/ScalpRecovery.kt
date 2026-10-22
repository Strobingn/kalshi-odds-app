package com.dirk.kalshiodds.decision

/**
 * Which 15-minute contract the paper scalpers should be on right now.
 * A missing REST close time falls back to the ticker stamp. A closed window
 * and the next window (not open yet) are not current, so an outage that
 * skips a rollover cannot leave the book on the expired market.
 */
object ScalpRecovery {
    data class Card(val ticker: String, val closeMs: Long?)

    fun closeOf(card: Card): Long? = card.closeMs?.takeIf { it > 0L } ?: ScalpTicker.closeMs(card.ticker)

    /** The open window that contains [nowMs]. Soonest close wins when two overlap. */
    fun currentTicker(cards: List<Card>, nowMs: Long): String? {
        val live = cards.mapNotNull { card ->
            val close = closeOf(card) ?: return@mapNotNull null
            val open = close - 900_000L
            if (nowMs < open || nowMs >= close) null else card.ticker to close
        }
        return live.minByOrNull { it.second }?.first
    }
}
