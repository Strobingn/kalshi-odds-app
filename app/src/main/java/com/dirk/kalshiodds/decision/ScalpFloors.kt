package com.dirk.kalshiodds.decision

import kotlin.math.roundToInt

/**
 * Lowest buy price for each paper scalper. Pennies are not a strategy.
 * Late favourites buy the favourite, so the floor sits under the fair threshold
 * by [FAV_DISCOUNT] cents and never in the 1–9¢ band.
 * Dip and fair-gap floors are [GENERAL] cents. Shock used to allow a 5¢ ask;
 * the edge is the lag, not a sub-10¢ price, so that band is 10¢ now.
 * The 3¢ complement of a 97¢ favourite is a real book (tenth-cent fills) and is
 * not a default entry.
 */
object ScalpFloors {
    const val GENERAL = 10
    /** Favourite ask stays within this many cents under the fair threshold. */
    const val FAV_DISCOUNT = 15

    fun params(code: String): ShockFav.Params? {
        ScalpVariants.shockParams(code)?.let { return it }
        return ShockFav.byVariantId(code)
    }

    fun isFavorite(code: String): Boolean {
        if (Research.isLate(code)) return true
        val spec = ScalpVariants.of(code)
        if (spec?.kind == ScalpVariants.Kind.FAV) return true
        val p = params(code) ?: return false
        return p.fav && !p.shock
    }

    /** Lowest buy, in cents. Always at least [GENERAL] except where a favourite threshold is higher. */
    fun cents(code: String): Int {
        if (code == "fairh60") return FairGapTune.FAIRH60_MIN_CENTS
        ScalpVariants.of(code)?.let { spec ->
            return when (spec.kind) {
                ScalpVariants.Kind.FAV -> favFloor(spec.shock)
                ScalpVariants.Kind.FADE -> (spec.fadeLo * 100.0).roundToInt().coerceAtLeast(GENERAL)
                else -> GENERAL
            }
        }
        if (Research.isLate(code)) {
            val band = Research.lateBand(code, null)
            return (band.lo * 100.0).roundToInt().coerceAtLeast(GENERAL)
        }
        if (code.startsWith("fc")) return 40
        val p = params(code)
        if (p != null && p.fav && !p.shock) return favFloor(p)
        return GENERAL
    }

    /** Highest buy the strategy's own rule allows, in cents. Always above [cents]. */
    fun ceilingCents(code: String): Int {
        val floor = cents(code)
        val spec = ScalpVariants.of(code)
        if (spec?.kind == ScalpVariants.Kind.FAV) {
            val amax = spec.shock?.amax ?: 0.97
            return (amax * 100.0).roundToInt().coerceIn(floor + 1, 99)
        }
        if (spec?.kind == ScalpVariants.Kind.FADE) {
            return (spec.fadeHi * 100.0).roundToInt().coerceIn(floor + 1, 99)
        }
        if (Research.isLate(code)) {
            val band = Research.lateBand(code, null)
            return (band.hi * 100.0).roundToInt().coerceIn(floor + 1, 99)
        }
        val p = params(code)
        if (p != null && p.fav && !p.shock) return (p.amax * 100.0).roundToInt().coerceIn(floor + 1, 99)
        if (code.startsWith("fc")) return 49.coerceAtLeast(floor + 1)
        return 55.coerceAtLeast(floor + 1)
    }

    /**
     * v2 dip and v2 fair-gap copies. Their notes stamped v2ask to the signal
     * price, so a 12¢ print became the whole ceiling.
     */
    fun repairsTightAsk(code: String): Boolean {
        if (code == KeptScalpers.V2_DIP_ID || code == KeptScalpers.V2_FAIR_ID) return true
        if (code.startsWith("v2-dip-") || code.startsWith("v2-g10-")) return true
        val kind = ScalpVariants.of(code)?.kind
        return kind == ScalpVariants.Kind.V2_DIP || kind == ScalpVariants.Kind.V2_FAIR
    }

    /**
     * Lowest ceiling that is still a band, in cents.
     * On the v2 dip / fair-gap family a ceiling under 30¢ is the collapsed
     * signal-price cap (the 10–14¢ band). It is put back on the parent 55¢
     * ceiling. Every other scalper may tighten to one cent above its floor.
     */
    fun minCeilingCents(code: String): Int {
        val floor = cents(code)
        val ceil = ceilingCents(code)
        if (!repairsTightAsk(code)) return (floor + 1).coerceAtMost(ceil)
        return 30.coerceIn(floor + 1, ceil)
    }

    /** Fair threshold in cents for a favourite, or null. */
    fun fairMinCents(code: String): Int? {
        if (Research.isLate(code)) return null
        val spec = ScalpVariants.of(code)
        if (spec?.kind == ScalpVariants.Kind.FAV) return spec.shock?.let { (it.fmin * 100.0).roundToInt() }
        val p = params(code)
        if (p != null && p.fav && !p.shock) return (p.fmin * 100.0).roundToInt()
        return null
    }

    private fun favFloor(p: ShockFav.Params?): Int {
        if (p == null) return 70
        return ((p.fmin * 100.0).roundToInt() - FAV_DISCOUNT).coerceIn(50, 90)
    }
}
