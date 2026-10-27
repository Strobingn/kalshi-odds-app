package com.dirk.kalshiodds.decision

/**
 * Paper buy band and take-profit shared by every scalper.
 *
 * Factory, reset, and a fresh install use [BUY_MIN]–[BUY_MAX] and [PROFIT_CENTS].
 * A stored band only one or two cents wide (31–32¢, 34–35¢) is the collapsed
 * AI slider. It is put back on load. A stored take-profit above 30¢ is put back
 * to [PROFIT_CENTS]. A wider band the owner set (for example 20–40¢) stays.
 */
object PaperFleetDefaults {
    const val BUY_MIN = 15
    const val BUY_MAX = 55
    const val PROFIT_CENTS = 25
    /** Stored targets above this are the tuner walk, not a take-profit. */
    const val PROFIT_HEAL_ABOVE = 30
    /** 31–32 and 34–35. Wider than this is not the collapsed slider. */
    const val COLLAPSED_WIDTH = 2

    fun factory(): ScalpStrategySettings = ScalpStrategySettings(
        minEntryCents = BUY_MIN,
        maxEntryCents = BUY_MAX,
        takeProfitCents = PROFIT_CENTS
    )

    /** Untouched slider row. The old default was lowest-buy 1¢ and no ceiling. */
    fun untouchedBand(s: ScalpStrategySettings): Boolean = s.minEntryCents <= 1 && s.maxEntryCents == null

    fun collapsed(min: Int, max: Int?): Boolean = max != null && max - min <= COLLAPSED_WIDTH

    /** Algo overlay or a row that already passed the floor clamp. Does not invent a factory profit of 0. */
    fun heal(s: ScalpStrategySettings): ScalpStrategySettings {
        val collapsed = collapsed(s.minEntryCents, s.maxEntryCents)
        val hot = s.takeProfitCents != null && s.takeProfitCents > PROFIT_HEAL_ABOVE
        if (!collapsed && !hot) return s
        return s.copy(
            minEntryCents = if (collapsed) BUY_MIN else s.minEntryCents,
            maxEntryCents = if (collapsed) BUY_MAX else s.maxEntryCents,
            takeProfitCents = if (hot) PROFIT_CENTS else s.takeProfitCents
        )
    }
}
