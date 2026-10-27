package com.dirk.kalshiodds.decision

/**
 * Paper buy band and take-profit shared by every scalper.
 *
 * Factory, reset, and a fresh install use [BUY_MIN]–[BUY_MAX] and [PROFIT_CENTS],
 * unless this scalper's floor is already above [BUY_MAX]. That book (a late
 * favourite) cannot buy inside 15–55¢, so the heal uses [band] instead.
 * A stored band only one or two cents wide (31–32¢, 34–35¢) is the collapsed
 * AI slider. It is put back on load. A stored take-profit above 30¢ is put back
 * to [PROFIT_CENTS]. A wider band the owner set (for example 20–40¢) stays
 * when its ceiling still clears the strategy floor.
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

    /**
     * Buy band a factory or collapsed row should wear.
     * 15–55¢ when that ceiling is above the strategy floor. Otherwise the
     * strategy's own book, so a 70¢+ favourite is not stored under a 55¢ cap.
     */
    fun band(code: String): Pair<Int, Int> {
        val floor = ScalpFloors.cents(code)
        val ceil = ScalpFloors.ceilingCents(code)
        if (floor > BUY_MAX) {
            val min = floor.coerceAtMost(ceil - 1)
            return min to ceil.coerceAtLeast(min + 1)
        }
        return BUY_MIN to BUY_MAX
    }

    /** Untouched slider row. The old default was lowest-buy 1¢ and no ceiling. */
    fun untouchedBand(s: ScalpStrategySettings): Boolean = s.minEntryCents <= 1 && s.maxEntryCents == null

    fun collapsed(min: Int, max: Int?): Boolean = max != null && max - min <= COLLAPSED_WIDTH

    /** Algo overlay or a row that already passed the floor clamp. Does not invent a factory profit of 0. */
    fun heal(s: ScalpStrategySettings, code: String? = null): ScalpStrategySettings {
        val collapsed = collapsed(s.minEntryCents, s.maxEntryCents)
        val hot = s.takeProfitCents != null && s.takeProfitCents > PROFIT_HEAL_ABOVE
        if (!collapsed && !hot) return s
        val (lo, hi) = if (collapsed && code != null) band(code) else BUY_MIN to BUY_MAX
        return s.copy(
            minEntryCents = if (collapsed) lo else s.minEntryCents,
            maxEntryCents = if (collapsed) hi else s.maxEntryCents,
            takeProfitCents = if (hot) PROFIT_CENTS else s.takeProfitCents
        )
    }
}
