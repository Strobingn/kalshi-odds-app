package com.dirk.kalshiodds.decision

/**
 * 0.3.65 'v2' copies (PAPER, BTC): each original's signals are copied as a separate trade that
 *  - buys only when the ask is ≤ 50¢ (2 s entry delay, displayed-size fill, 10-contract clip, taker fee),
 *  - keeps the original's stop,
 *  - has no max-hold / time limit and no bet-count limit (held to settlement otherwise),
 *  - does not sell until the gain after both fees reaches the stop size; then trails the peak bid and sells 2¢ off it.
 * Deterministic, no LLM. Originals are untouched.
 */
object ScalpV2 {
    const val PREFIX = "v2-"
    const val MAX_ASK = 0.50
    const val TRAIL = 0.02
    const val ENTRY_DELAY_MS = 2_000L
    const val GIVE_UP_MS = 6_000L
    const val CLIP = 10
    const val MIN_TRADES = 30

    fun isV2(variantId: String): Boolean = variantId.startsWith(PREFIX)
    fun v2Id(originalVariantId: String): String = PREFIX + originalVariantId
    /** The trade as its original (for model lookup). */
    fun originalView(t: ScalpTrade): ScalpTrade = t.copy(ruleVersion = t.ruleVersion.replace("|$PREFIX", "|"))

    /** Original's per-bet stop in dollars per contract. */
    fun stopFor(originalVariantId: String): Double = when {
        ShockFav.isShockFav(originalVariantId) -> ShockFav.byVariantId(originalVariantId)?.let { ShockFav.stopC(it) / 100.0 } ?: 0.25
        originalVariantId.startsWith(ForecastScalp.RIDE_PREFIX) || originalVariantId.startsWith(ForecastScalp.FADE_PREFIX) -> ForecastScalp.STOP_C / 100.0
        else -> ScalpParams.byId(originalVariantId)?.stop ?: 0.10
    }.coerceIn(0.01, 0.99)

    fun entryOk(ask: Double?): Boolean = ask != null && ask > 0.0 && ask <= MAX_ASK + 1e-9

    /** Armed once the per-contract gain after both fees reaches the stop size. */
    fun armed(entry: Double, bid: Double, stop: Double, n: Int = CLIP): Boolean = ScalpRule.netAfterFees(entry, bid, n.coerceAtLeast(1)) >= stop - 1e-9

    /** Exit reason or null. [wasArmed] persists across quotes (peak tracked by the caller). */
    fun exit(entry: Double, stop: Double, peakBid: Double, bid: Double, wasArmed: Boolean, n: Int = CLIP): String? = when {
        bid <= entry - stop + 1e-9 -> "stop −${Math.round(stop * 100)}¢"
        (wasArmed || armed(entry, bid, stop, n)) && bid <= peakBid - TRAIL + 1e-9 -> "v2 trail 2¢ off peak after +${Math.round(stop * 100)}¢ net"
        else -> null
    }

    data class Compare(val v2: ScalpModels.Model, val original: ScalpModels.Model, val v2Row: ScalpModels.Row, val origRow: ScalpModels.Row, val status: String)

    /** Plain status: 'not enough data' under 30 closed v2 trades, else by $/trade vs the original. */
    fun status(v2Trades: Int, v2PerTrade: Double, origPerTrade: Double): String = when {
        v2Trades < MIN_TRADES -> "not enough data"
        v2PerTrade > origPerTrade + 1e-9 -> "beating original"
        else -> "losing to original"
    }
}
