package com.dirk.kalshiodds.decision

/**
 * Floors for "AI learns" and the on-device paper tuner.
 *
 * A loser used to walk the buy band in until it stuck on a 10¢ window
 * (20–30¢, 23–33¢) and the next close said "AI kept settings". The factory
 * window is the hard floor: lowest buy stays at or under 15¢, highest buy
 * stays at or over 55¢, and take-profit stays at 25¢. Losers may still
 * tighten the entry minute, the stop, and the clip. They may not tighten
 * the buy band or open-at-once.
 *
 * A band Dirk set by hand that is at least 20¢ wide is left alone. A stored
 * AI-owned row is put back by [repair], including open-at-once and take-profit.
 */
object AiLearnBounds {
    /** Factory buy window. AI learns may not leave a tighter band. */
    const val MIN_BAND_CENTS = PaperFleetDefaults.MIN_WIDTH
    const val UNTIL_FLOOR = 5
    const val UNTIL_CEILING = 14
    /** Percent stop under the fill. 1% sells on the first tick. */
    const val STOP_PCT_FLOOR = 8
    const val STOP_PCT_CEILING = 40
    /** Absolute stop, in cents. The tuner used to walk this to 1¢. */
    const val STOP_CENTS_FLOOR = 6
    /** A 1–3¢ target does not clear the taker fee. 0 stays "off" on a hand row. */
    const val TAKE_FLOOR = 4
    /** Retired. Losers used to drop the ceiling by this many cents (55¢ → 35¢). */
    const val MAX_DROP_FROM_BASE = 20

    /** Factory 40¢ window for every scalper, including a favourite. */
    fun bandRoom(@Suppress("UNUSED_PARAMETER") code: String): Int = MIN_BAND_CENTS

    /**
     * Width the next step must keep: the factory 15–55¢ span.
     * The current [min] and [max] used to shrink this; they no longer do.
     */
    fun enforcedBand(
        @Suppress("UNUSED_PARAMETER") code: String,
        @Suppress("UNUSED_PARAMETER") min: Int,
        @Suppress("UNUSED_PARAMETER") max: Int
    ): Int = MIN_BAND_CENTS

    /** Learned at least once. Survives an AI-learns toggle that left the note or the counters. */
    fun aiTouched(s: ScalpStrategySettings): Boolean =
        s.aiN > 0 || s.aiBaseCents != null || s.aiNote.startsWith("AI ")

    fun aiOwned(s: ScalpStrategySettings): Boolean = s.aiLearns && aiTouched(s)

    fun untilLo(cur: Int): Int = if (cur < UNTIL_FLOOR) cur else UNTIL_FLOOR

    fun stopPctLo(cur: Int): Int = if (cur < STOP_PCT_FLOOR) cur else STOP_PCT_FLOOR

    fun stopCentsLo(cur: Int): Int = if (cur < STOP_CENTS_FLOOR) cur else STOP_CENTS_FLOOR

    fun nextMax(code: String, cur: Int, base: Int, @Suppress("UNUSED_PARAMETER") min: Int, step: Int, winner: Boolean): Int {
        val ceil = maxOf(ScalpFloors.ceilingCents(code), PaperFleetDefaults.BUY_MAX)
        val restored = maxOf(cur, PaperFleetDefaults.BUY_MAX)
        if (!winner) return restored
        return minOf(
            maxOf(cur + step, PaperFleetDefaults.BUY_MAX),
            maxOf(base, PaperFleetDefaults.BUY_MAX) + 12,
            ceil
        )
    }

    fun nextMin(code: String, curMin: Int, @Suppress("UNUSED_PARAMETER") max: Int, @Suppress("UNUSED_PARAMETER") step: Int, @Suppress("UNUSED_PARAMETER") winner: Boolean): Int {
        // Lowest buy cannot sit above 15¢, and it cannot sit under the strategy floor.
        // A favourite whose own floor is 80¢ stays there. A 23¢ paper floor snaps back to 15¢.
        val floor = ScalpFloors.cents(code)
        val cap = maxOf(floor, PaperFleetDefaults.BUY_MIN)
        return minOf(maxOf(curMin, floor), cap)
    }

    fun nextUntil(cur: Int, winner: Boolean): Int {
        val lo = untilLo(cur)
        return if (winner) (cur + 1).coerceAtMost(UNTIL_CEILING) else (cur - 1).coerceAtLeast(lo)
    }

    fun nextStopPct(cur: Int, winner: Boolean): Int {
        val lo = stopPctLo(cur)
        return if (winner) (cur + 1).coerceAtMost(STOP_PCT_CEILING) else (cur - 1).coerceAtLeast(lo)
    }

    fun nextStopCents(cur: Int, winner: Boolean): Int {
        val lo = stopCentsLo(cur)
        return if (winner) (cur + 1).coerceAtMost(48) else (cur - 1).coerceAtLeast(lo)
    }

    /**
     * One stored strategy the learner already moved.
     * The buy band is put back to 15–55¢ when AI tightened it. Take-profit is
     * put back to 25¢. Open-at-once is put back to unlimited (0). Until-minute
     * and a 1% stop are lifted. Clip size is not lowered.
     */
    fun repair(code: String, s: ScalpStrategySettings): ScalpStrategySettings {
        if (!aiTouched(s)) return s
        val ceil = maxOf(ScalpFloors.ceilingCents(code), PaperFleetDefaults.BUY_MAX)
        var min = s.minEntryCents
        var max = s.maxEntryCents
        var until = s.untilMinute
        var stopPct = s.stopLossPercent
        var stopC = s.stopCents
        var target = s.takeProfitCents
        var contracts = s.contracts
        var open = s.maxOpenPerSide
        var changed = false
        if (contracts != null && contracts < PaperClip.MIN) {
            contracts = PaperClip.MIN
            changed = true
        }
        val (flooredMin, flooredMax) = PaperFleetDefaults.floorBand(min, max, true, s.minEntryCents, s.maxEntryCents)
        if (flooredMin != min || flooredMax != max) {
            min = flooredMin
            max = flooredMax
            changed = true
        }
        if (until < UNTIL_FLOOR) {
            until = UNTIL_FLOOR
            changed = true
        }
        if (stopPct != null && stopPct < STOP_PCT_FLOOR) {
            stopPct = STOP_PCT_FLOOR
            changed = true
        }
        if (stopC != null && stopC < STOP_CENTS_FLOOR) {
            stopC = STOP_CENTS_FLOOR
            changed = true
        }
        if (target != PaperFleetDefaults.PROFIT_CENTS) {
            target = PaperFleetDefaults.PROFIT_CENTS
            changed = true
        }
        if (open != PaperFleetDefaults.OPEN_AT_ONCE) {
            open = PaperFleetDefaults.OPEN_AT_ONCE
            changed = true
        }
        if (!changed) return s
        val shownHigh = max ?: ceil
        return s.copy(
            minEntryCents = min,
            maxEntryCents = max,
            untilMinute = until,
            stopLossPercent = stopPct,
            stopCents = stopC,
            takeProfitCents = target,
            contracts = contracts,
            maxOpenPerSide = open,
            aiNote = "AI repaired collapsed sliders · buy $min–${shownHigh}¢ · profit ${target}¢ · open $open · until minute $until · clip ${contracts ?: PaperClip.MIN}"
        )
    }

    fun repairAll(c: ScalpControls): ScalpControls {
        if (c.strategySettings.isEmpty()) return c
        val next = c.strategySettings.mapValues { (code, raw) ->
            repair(code, ScalpParamSanity.settings(code, raw))
        }
        return if (next == c.strategySettings) c else c.copy(strategySettings = next)
    }
}
