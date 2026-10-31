package com.dirk.kalshiodds.decision

/**
 * Paper buy band and take-profit shared by every scalper.
 *
 * Factory, reset, and a fresh install use [BUY_MIN]–[BUY_MAX] and [PROFIT_CENTS].
 * Open-at-once starts at [OPEN_AT_ONCE] (the slider's "unlimited").
 * A stored band tighter than [NARROW_WIDTH] (the 10¢ AI windows, and the old
 * 1–2¢ collapse) is put back on load. AI-owned rows are also put back when
 * lowest buy sits above 15¢ or highest buy sits under 55¢. A hand band at
 * least [NARROW_WIDTH] wide (for example 20–40¢) stays. A stored take-profit
 * above 30¢ is put back to [PROFIT_CENTS].
 *
 * [BUY_MAX] is the factory highest buy. [BUY_CEILING] is the highest the
 * slider, prefs, or a healer may store (75¢). A hand max from 56¢ through
 * 75¢ stays. Anything above the ceiling is clamped down to it. AI Learn
 * does not walk the max above [BUY_MAX]. Late window uses [LATE_BUY_MAX]
 * for both its factory max and its ceiling.
 */
object PaperFleetDefaults {
    const val BUY_MIN = 15
    const val BUY_MAX = 55
    /** Highest buy the owner may set on every scalper except late window. */
    const val BUY_CEILING = 75
    /** Late window (<5 min): factory max and ceiling. Min stays [BUY_MIN]. */
    const val LATE_BUY_MAX = 95
    /** Contract pays at most this many cents. A take-profit target cannot sit above it. */
    const val PAYOUT_CENTS = 99
    const val PROFIT_CENTS = 25
    /** Slider value 0. The label is "unlimited"; it is not a count of clips to open. */
    const val OPEN_AT_ONCE = 0
    /** Full factory window. AI learns may not leave a tighter buy band. */
    const val MIN_WIDTH = BUY_MAX - BUY_MIN
    /** Stored targets above this are the tuner walk, not a take-profit. */
    const val PROFIT_HEAL_ABOVE = 30
    /** 31–32 and 34–35. Wider than this is not the collapsed slider. */
    const val COLLAPSED_WIDTH = 2
    /**
     * 20–30 and 23–33. A hand window this wide or wider (20–40¢) is not healed
     * just for its width. Anything tighter is the AI floor and is put back.
     */
    const val NARROW_WIDTH = 20

    fun isLate(code: String?): Boolean = code != null && LateWindowScalper.matches(code)

    /** Factory highest buy. 55¢ for the fleet, 95¢ for late window. */
    fun buyMax(code: String?): Int = if (isLate(code)) LATE_BUY_MAX else BUY_MAX

    /**
     * Highest cents the owner, prefs, or a healer may store on this scalper.
     * Late window is 95¢. A favourite or research-late leg keeps its own higher
     * ceiling. Everyone else stops at [BUY_CEILING].
     */
    fun buyCeiling(code: String?): Int {
        if (code == null) return BUY_CEILING
        if (isLate(code)) return LATE_BUY_MAX
        return maxOf(BUY_CEILING, ScalpFloors.ceilingCents(code))
    }

    /**
     * Dollar ask cap for [code]. [maxCents] null means the factory max.
     * The result never sits above [buyCeiling].
     */
    fun askLimit(code: String?, maxCents: Int?): Double {
        val ceiling = buyCeiling(code)
        val band = (maxCents ?: buyMax(code)).coerceIn(1, ceiling)
        return band / 100.0
    }

    fun overCeilingReason(cents: Int): String = "ask above ${cents}¢ ceiling"

    fun factory(code: String? = null): ScalpStrategySettings = ScalpStrategySettings(
        minEntryCents = BUY_MIN,
        maxEntryCents = buyMax(code),
        takeProfitCents = PROFIT_CENTS,
        maxOpenPerSide = OPEN_AT_ONCE
    )

    /** Untouched slider row. The old default was lowest-buy 1¢ and no ceiling. */
    fun untouchedBand(s: ScalpStrategySettings): Boolean = s.minEntryCents <= 1 && s.maxEntryCents == null

    fun collapsed(min: Int, max: Int?): Boolean = max != null && max - min <= COLLAPSED_WIDTH

    /** A stored window both ends of which were set, inside the slider, and tighter than [NARROW_WIDTH]. */
    fun narrow(min: Int, max: Int?): Boolean =
        max != null && min >= BUY_MIN && max - min < NARROW_WIDTH

    /**
     * Buy band after the fleet floor.
     * [rawMin] and [rawMax] are the stored slider, before a favourite ceiling is filled in.
     * A stored window tighter than [NARROW_WIDTH] (20–30¢, 23–33¢, 31–32¢) becomes 15–55¢
     * (15–95¢ for late window). An AI-owned stored ceiling under the factory max, or a
     * stored lowest buy above 15¢, is put back the same way. A wider hand ceiling
     * (56–75¢, or up to 95¢ on late window) is kept. Values above [buyCeiling] clamp
     * down to that ceiling. A hand band at least [NARROW_WIDTH] wide is kept.
     * A missing ceiling is not a tight window.
     */
    fun floorBand(
        min: Int,
        max: Int?,
        touched: Boolean,
        rawMin: Int = min,
        rawMax: Int? = max,
        code: String? = null
    ): Pair<Int, Int?> {
        val ceiling = buyCeiling(code)
        val defaultMax = buyMax(code)
        val cappedMax = max?.coerceAtMost(ceiling)
        val tight = narrow(rawMin, rawMax)
        val under = touched && rawMax != null && (rawMin > BUY_MIN || rawMax < defaultMax)
        if (!tight && !under) return min to cappedMax
        val newMax = if (!tight && cappedMax != null && cappedMax > defaultMax) cappedMax else defaultMax
        return BUY_MIN to newMax.coerceAtMost(ceiling)
    }

    /** Algo overlay or a row that already passed the floor clamp. Does not invent a factory profit of 0. */
    fun heal(s: ScalpStrategySettings, code: String? = null): ScalpStrategySettings {
        val (min, max) = floorBand(
            s.minEntryCents,
            s.maxEntryCents,
            AiLearnBounds.aiTouched(s),
            s.minEntryCents,
            s.maxEntryCents,
            code
        )
        val hot = s.takeProfitCents != null && s.takeProfitCents > PROFIT_HEAL_ABOVE
        if (min == s.minEntryCents && max == s.maxEntryCents && !hot) return s
        return s.copy(
            minEntryCents = min,
            maxEntryCents = max,
            takeProfitCents = if (hot) PROFIT_CENTS else s.takeProfitCents
        )
    }
}
