package com.dirk.kalshiodds.decision

/**
 * Floors for "AI learns" and the on-device paper tuner.
 *
 * A loser used to walk three knobs independently: highest buy down to the
 * baseline minus 20¢ (or one cent above the price floor), lowest buy up to
 * one cent under that, and until-minute / stop percent down to 1. A short
 * losing streak then pinned every strategy on a 1¢ band and minute 1, and
 * the next close said "AI kept settings" because nothing could move further.
 *
 * Losers may still tighten. They may not close the buy band, the entry
 * window, or the stop past the floors below. A band Dirk set by hand that is
 * already narrower than the floor is left alone; only a stored AI-owned row
 * is widened, and only by [repair].
 */
object AiLearnBounds {
    /** Usable buy range, in cents. Favourite books with less room keep that room. */
    const val MIN_BAND_CENTS = 10
    const val UNTIL_FLOOR = 5
    const val UNTIL_CEILING = 14
    /** Percent stop under the fill. 1% sells on the first tick. */
    const val STOP_PCT_FLOOR = 8
    const val STOP_PCT_CEILING = 40
    /** Absolute stop, in cents. The tuner used to walk this to 1¢. */
    const val STOP_CENTS_FLOOR = 6
    /** A 1–3¢ target does not clear the taker fee. 0 stays "off". */
    const val TAKE_FLOOR = 4
    const val MAX_DROP_FROM_BASE = 20

    fun bandRoom(code: String): Int {
        val room = (ScalpFloors.ceilingCents(code) - ScalpFloors.cents(code)).coerceAtLeast(1)
        return minOf(MIN_BAND_CENTS, room)
    }

    /**
     * Width the next step must keep. A range that is already narrower than
     * [bandRoom] is not widened here (a hand-set band, or a row waiting for [repair]).
     */
    fun enforcedBand(code: String, min: Int, max: Int): Int {
        val want = bandRoom(code)
        val have = (max - min).coerceAtLeast(1)
        return if (have < want) have else want
    }

    fun aiOwned(s: ScalpStrategySettings): Boolean {
        if (!s.aiLearns) return false
        if (s.aiN > 0 || s.aiBaseCents != null) return true
        return s.aiNote.startsWith("AI ")
    }

    fun untilLo(cur: Int): Int = if (cur < UNTIL_FLOOR) cur else UNTIL_FLOOR

    fun stopPctLo(cur: Int): Int = if (cur < STOP_PCT_FLOOR) cur else STOP_PCT_FLOOR

    fun stopCentsLo(cur: Int): Int = if (cur < STOP_CENTS_FLOOR) cur else STOP_CENTS_FLOOR

    fun nextMax(code: String, cur: Int, base: Int, min: Int, step: Int, winner: Boolean): Int {
        val ceil = ScalpFloors.ceilingCents(code)
        val band = enforcedBand(code, min, cur)
        val floorMax = maxOf(ScalpFloors.minCeilingCents(code), min + band).coerceAtMost(ceil)
        return if (winner) minOf(cur + step, base + 12, ceil)
        else maxOf(cur - step, base - MAX_DROP_FROM_BASE, floorMax)
    }

    fun nextMin(code: String, curMin: Int, max: Int, step: Int, winner: Boolean): Int {
        val floor = ScalpFloors.cents(code)
        val band = enforcedBand(code, curMin, max)
        val cap = (max - band).coerceAtLeast(floor)
        return if (winner) (curMin - step).coerceAtLeast(floor) else (curMin + 1).coerceAtMost(cap)
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
     * One stored strategy whose learner already collapsed it.
     * Lowest buy moves down until the band is real; the tightened ceiling stays
     * when it still clears the floor. Until-minute and a 1% stop are lifted.
     * Take profit of 0 (off) or of the strategy target (15¢ on sf42) is kept.
     */
    fun repair(code: String, s: ScalpStrategySettings): ScalpStrategySettings {
        if (!aiOwned(s)) return s
        val floor = ScalpFloors.cents(code)
        val ceil = ScalpFloors.ceilingCents(code)
        val band = bandRoom(code)
        var min = s.minEntryCents.coerceIn(floor, 98)
        var max = s.maxEntryCents
        var until = s.untilMinute
        var stopPct = s.stopLossPercent
        var stopC = s.stopCents
        var target = s.takeProfitCents
        var changed = false
        val high = (max ?: ceil).coerceIn(floor + 1, ceil)
        if (high - min < band) {
            val lowered = (high - band).coerceAtLeast(floor)
            if (high - lowered >= band) {
                if (lowered != min) {
                    min = lowered
                    changed = true
                }
            } else {
                val raised = (floor + band).coerceAtMost(ceil).coerceAtLeast(min + 1)
                if (max != raised) {
                    max = raised
                    changed = true
                }
                val min2 = (raised - band).coerceAtLeast(floor)
                if (min2 != min) {
                    min = min2
                    changed = true
                }
            }
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
        if (target != null && target in 1 until TAKE_FLOOR) {
            target = TAKE_FLOOR
            changed = true
        }
        if (!changed) return s
        val shownHigh = max ?: high
        return s.copy(
            minEntryCents = min,
            maxEntryCents = max,
            untilMinute = until,
            stopLossPercent = stopPct,
            stopCents = stopC,
            takeProfitCents = target,
            aiNote = "AI repaired collapsed sliders · buy $min–${shownHigh}¢ · until minute $until"
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
