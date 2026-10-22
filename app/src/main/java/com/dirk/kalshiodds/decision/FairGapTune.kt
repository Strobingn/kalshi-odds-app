package com.dirk.kalshiodds.decision

import java.util.Locale

/**
 * Kalshi Scalper 1.0.3 — retune of the LOSING strategies toward the one rule that won in Dirk's Sep 28 – Oct 7 BTC data
 * (fair-gap, hold ≈4.5 min: +10.4¢/contract, t = 4.7; hold to settle +7.8¢, t = 3.5):
 *  - buy only at 24–42¢,
 *  - only when the BRTI (CF Benchmarks) settlement-aware fair value beats the ask by 10–21¢ AFTER the Kalshi taker fee
 *    ceil(0.07·C·P·(1−P)),
 *  - never both sides of one window (per strategy),
 *  - no 3¢ trailing stop (it lost at every size: −4.7¢ at 3¢, −5.0¢ at 5¢, −4.9¢ at 8¢, −3.5¢ at 12¢, +0.4¢ with none),
 *  - hold ≈4.5 min, otherwise to settlement.
 * Nothing is deleted: each tweaked strategy keeps its own trigger and simply has to pass this gate too. Winning strategies
 * ([KEEP]) are untouched. Dirk's manual sliders win: an explicit price range or max-hold slider replaces the defaults here.
 * PAPER ONLY, BTC only.
 */
object FairGapTune {
    const val VERSION = "fairgap-tune-1.0.3"
    const val LO = 0.24
    const val HI = 0.42
    const val MIN_GAP = 0.10
    const val MAX_GAP = 0.21
    const val HOLD_S = 270
    const val QUOTE_MAX_AGE_MS = 10_000L

    /** Winners — unchanged in 1.0.3 (fair-gap family + fair-value research legs + the LLM lane, whose only change is model transparency).
     *  1.0.14: fair-t1/t2 and r-late-t1/t2 copies run their own rules.
     *  1.0.21: r-late is back on its own 80–95¢ band, so it is not forced through 24–42¢.
     */
    val KEEP: Set<String> = setOf("fair", "fairh60", "v2-fair", "v2-fairh60", Research.HOLD, Research.KELLY, Research.MAKER, "v2-llm", "fair-t1", "fair-t2", Research.LATE, Research.LATE_T1, Research.LATE_T2, KeptScalpers.FAIR_ID, KeptScalpers.V2_FAIR_ID)

    /**
     * 1.0.21: these keep their own entry rule. The 24–42¢ fair-gap gate does not apply.
     * sf16–sf31 use [com.dirk.kalshiodds.decision.ShockFav.favEntryOk] (sf31 still checks off-US hours).
     * fcfade uses its own 40–49¢ band.
     */
    val OWN_RULES: Set<String> = setOf(
        "sf16", "sf17", "sf18", "sf19", "sf20", "sf21", "sf22", "sf23", "sf24", "sf29", "sf31",
        "fcfade", KeptScalpers.FADE_ID
    )

    /** Hold-to-settlement entries need at least this many seconds left. Own-rule late favourites keep their shorter window. */
    const val SETTLE_MIN_S = 180.0

    /** Untouched fair / fair-t1 / fair-t2 highest buy. A saved slider replaces it. AI learns stays inside [this − 10¢, this]. */
    const val FAIR_MAX_CENTS = 40
    val FAIR_MAX_CODES: Set<String> = setOf("fair", "fair-t1", "fair-t2")
    /** Untouched fairh60 lowest buy. A slider above 1¢ replaces it. */
    const val FAIRH60_MIN_CENTS = 40

    fun isTweaked(code: String?): Boolean = code != null && code !in KEEP
    fun ownsRules(code: String?): Boolean = code != null && (code in OWN_RULES || code.removeSuffix("f") in OWN_RULES)
    /**
     * 24–42¢ entry gate. Kept winners, their paper variants, and own-rule scalpers are exempt.
     * The gate was cancelling sf16f / sf41 / sf04 at save time because the card id is not the
     * bare family code in [OWN_RULES]. Exits still use [isTweaked].
     */
    fun entryGated(code: String?): Boolean = isTweaked(code) && !ownsRules(code) &&
        code !in KeptScalpers.IDS && !ScalpVariants.isLive(code)
    /** Late-favourite leg uses the fair-gap ask gate only when this is true. */
    fun favUsesFairGapGate(code: String?): Boolean = entryGated(code)

    fun defaultMaxCents(code: String?): Int? = if (code != null && code in FAIR_MAX_CODES) FAIR_MAX_CENTS else null

    /** Saved highest-buy wins. Otherwise the 1.0.21 per-code default, or null when this code has none. */
    fun effectiveMaxCents(code: String?, s: ScalpStrategySettings?): Int? = s?.maxEntryCents ?: defaultMaxCents(code)

    /**
     * Saved lowest-buy wins once it is off the untouched 1¢. fairh60 with no manual floor buys from 40¢.
     * Passing a null settings object is the untouched slider.
     */
    fun effectiveMinCents(code: String?, s: ScalpStrategySettings?): Int {
        val min = s?.minEntryCents ?: 1
        return if (code == "fairh60" && min <= 1) FAIRH60_MIN_CENTS else min
    }

    /** True when [ask] is outside the effective band. Does not apply time or fair-gap gates. */
    fun priceBlocked(code: String?, ask: Double, s: ScalpStrategySettings?): Boolean {
        val min = effectiveMinCents(code, s) / 100.0
        val max = effectiveMaxCents(code, s)?.div(100.0)
        return ask < min - 1e-9 || (max != null && ask > max + 1e-9)
    }

    /**
     * Own-rule late-favourite legs buy up to their own ask cap (about 95–97¢).
     * Every other entry stays under the 55¢ hard ceiling.
     */
    fun askAllowed(code: String?, ask: Double, favLeg: Boolean): Boolean {
        if (!ask.isFinite() || ask <= 0.0 || ask > 0.99) return false
        if (favLeg && ownsRules(code)) return true
        return ScalpRule.buyPriceOk(ask)
    }

    data class Band(val lo: Double, val hi: Double)
    /** Manual slider overrides the default 24–42¢ band. */
    fun band(s: ScalpStrategySettings?): Band {
        val lo = if (s != null && s.minEntryCents > 1) s.minEntryCents / 100.0 else LO
        val hi = s?.maxEntryCents?.div(100.0) ?: HI
        return Band(lo, maxOf(lo, hi))
    }
    /** Manual max-hold slider overrides the 4.5 min default (0 = hold to settlement). */
    fun holdS(s: ScalpStrategySettings?): Int = s?.maxHoldSeconds ?: HOLD_S

    /** Gap per contract after the taker entry fee on [n] contracts. */
    fun gapAfterFee(fair: Double, ask: Double, n: Int): Double = fair - ask - ScalpRule.feePerContract(n.coerceAtLeast(1), ask)

    /**
     * Entry gate. Returns null when the entry may proceed, else the skip reason. [otherSideTaken] = this strategy already
     * bought the opposite side in this window.
     */
    fun entryBlock(q: ScalpRule.Quote?, side: String, ask: Double, n: Int, s: ScalpStrategySettings?, otherSideTaken: Boolean, nowMs: Long): String? {
        if (otherSideTaken) return "fair-gap rule: never both sides in one window"
        if (q == null || nowMs - q.nowMs > QUOTE_MAX_AGE_MS) return "fair-gap rule: no fresh quote"
        if (ScalpParams.coinOf(q.ticker) != ShockFav.TRADE_COIN) return "fair-gap rule: BTC only"
        val b = band(s)
        if (ask < b.lo - 1e-9 || ask > b.hi + 1e-9)
            return String.format(Locale.US, "fair-gap rule: ask %.0f¢ outside %.0f–%.0f¢", ask * 100, b.lo * 100, b.hi * 100)
        val f = Research.fair(q) ?: return "fair-gap rule: no fair value"
        if (!f.brti) return "fair-gap rule: no fresh BRTI (CF Benchmarks) tick"
        val fair = if (side == "YES") f.pYes else 1.0 - f.pYes
        val gap = gapAfterFee(fair, ask, n)
        if (gap < MIN_GAP - 1e-9 || gap > MAX_GAP + 1e-9)
            return String.format(Locale.US, "fair-gap rule: BRTI gap %.1f¢ after fee outside 10–21¢", gap * 100)
        return null
    }

    /** Exit for tweaked strategies: no stop, no trail; sell after the hold time, else hold to settlement. */
    fun exit(heldS: Double, s: ScalpStrategySettings?): String? {
        val h = holdS(s)
        return if (h > 0 && heldS >= h) "fair-gap rule: ${h}s hold done" else null
    }
}
