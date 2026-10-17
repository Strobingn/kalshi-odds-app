package com.dirk.kalshiodds.decision

/** Price stop, distinct from a net-P&L limit: spread, fees and slippage can exceed the trigger percentage. */
object ScalpRisk {
    /** 0.3.85: default percent raised from 0.3.83's 1% (fired on one-tick noise) to 4%. */
    const val DEFAULT_PERCENT = 4
    /** 2 = 0.3.85 one-time 1% → 4% migration (only values still at the 0.3.83 forced 1%). */
    const val POLICY_VERSION = 2
    const val EXIT_DELAY_MS = 250L
    /** 0.3.85: absolute stop floor (dollars per contract) and padding over spread + round-trip fees. */
    const val MIN_STOP_USD = 0.03
    const val STOP_PAD_USD = 0.01
    /** 0.3.85: re-entry cooldowns per strategy per market (pre-0.3.83 generic cooldown was 15 s). */
    const val REENTRY_COOLDOWN_MS = 15_000L
    const val STOP_REENTRY_COOLDOWN_MS = 60_000L
    /**
     * 0.3.86: runaway-loop guard only: at most this many round trips per strategy per market per rolling 15 minutes
     * (logged when it triggers). The real entry gate stays each strategy's positive expected value after spread + fees.
     */
    const val ROUND_TRIP_WINDOW_MS = 15 * 60_000L
    const val MAX_ROUND_TRIPS_PER_WINDOW = 20
    private val runawayLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val EPS = 1e-9

    fun stopPrice(fill: Double, percent: Double): Double {
        require(fill.isFinite() && fill > 0.0 && fill <= 1.0)
        require(percent.isFinite() && percent > 0.0 && percent < 100.0)
        return fill * (1.0 - percent / 100.0)
    }
    fun hit(fill: Double, bid: Double?, percent: Double): Boolean =
        bid != null && bid.isFinite() && bid in 0.0..1.0 && bid <= stopPrice(fill, percent) + EPS

    /**
     * 0.3.85 stop distance (dollars): max(percent of the entry bid, spread + round-trip taker fees + 1¢, 3¢).
     * The stop can therefore never sit inside the spread at entry or fire on one-tick noise.
     */
    fun stopDistance(refBid: Double, percent: Double, spread: Double, contracts: Int): Double {
        val n = contracts.coerceAtLeast(1)
        val s = spread.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val ask = (refBid + s).coerceIn(0.0, 1.0)
        val fees = ScalpRule.feePerContract(n, ask) + ScalpRule.feePerContract(n, refBid)
        return maxOf(refBid * percent / 100.0, s + fees + STOP_PAD_USD, MIN_STOP_USD)
    }
    /** Stop trigger measured from the bid you could sell at when the trade opened (not the ask paid). */
    fun flooredStopPrice(refBid: Double, percent: Double, spread: Double, contracts: Int): Double =
        refBid - stopDistance(refBid, percent, spread, contracts)
    fun flooredHit(refBid: Double, bid: Double?, percent: Double, spread: Double, contracts: Int): Boolean =
        bid != null && bid.isFinite() && bid in 0.0..1.0 && bid <= flooredStopPrice(refBid, percent, spread, contracts) + EPS

    /** Entry-time sellable bid; untagged (legacy) trades fall back to fill minus saved spread (or 1¢). */
    fun refBid(t: ScalpTrade): Double? {
        val entry = t.entryPrice?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 } ?: return null
        val saved = ScalpV2.savedNumber(t.note, "riskRefBid", Double.NaN)
        if (saved.isFinite() && saved > 0.0 && saved <= 1.0) return minOf(saved, entry)
        return (entry - refSpread(t)).coerceAtLeast(0.01)
    }
    fun refSpread(t: ScalpTrade): Double =
        ScalpV2.savedNumber(t.note, "riskSpread", Double.NaN).takeIf { it.isFinite() && it >= 0.0 } ?: 0.01
    fun hasRef(t: ScalpTrade): Boolean = ScalpV2.savedNumber(t.note, "riskRefBid", Double.NaN).isFinite()
    fun tagRef(note: String, bid: Double, spread: Double): String =
        ScalpV2.tag(ScalpV2.tag(note, "riskRefBid", String.format(java.util.Locale.US, "%.4f", bid)),
            "riskSpread", String.format(java.util.Locale.US, "%.4f", spread.coerceAtLeast(0.0)))
    fun tradeStopPrice(t: ScalpTrade, percent: Double): Double? =
        refBid(t)?.let { flooredStopPrice(it, percent, refSpread(t), t.contracts) }

    fun managedExit(t: ScalpTrade): Boolean = ScalpV2.savedNumber(t.note, "riskExit", 0.0) == 1.0
    /** A running position can be tightened by the global cap, never loosened by a later settings change. */
    fun percent(t: ScalpTrade, controls: ScalpControls): Double {
        val model = ScalpModels.modelOf(t)?.code
        val cap = controls.protectivePercent(model).toDouble()
        val saved = ScalpV2.savedNumber(t.note, "riskStopPct", DEFAULT_PERCENT.toDouble()).coerceIn(1.0, 99.0)
        val v2 = if (ScalpV2.peakRule(t)) ScalpV2.stopPercent(t) else cap
        return minOf(cap, saved, v2)
    }

    private fun isStopExit(t: ScalpTrade): Boolean =
        managedExit(t) || (t.exitReason ?: "").contains("stop", ignoreCase = true)

    /**
     * 0.3.85 churn guard for every scalper lane (originals, forecast, shock, v2, g14 copies): blocks a new entry for
     * [variantId] while it is cooling down on [ticker] after an exit (60 s after a stop, 15 s otherwise) or once it has
     * [maxPerWindow] round trips on [ticker] in the last 15 minutes (filled trades only; open ones count too).
     */
    fun entryBlocked(all: List<ScalpTrade>, variantId: String, ticker: String, nowMs: Long,
                     maxPerWindow: Int = MAX_ROUND_TRIPS_PER_WINDOW): Boolean {
        val mine = all.filter { it.isPrimary && it.variantId == variantId && it.entryAtMs != null && it.state != ScalpState.NO_FILL }
        val lastExit = mine.filter { it.ticker.equals(ticker, true) && it.state == ScalpState.CLOSED && it.closedAtMs != null }
            .maxByOrNull { it.closedAtMs!! }
        if (lastExit != null) {
            val cool = if (isStopExit(lastExit)) STOP_REENTRY_COOLDOWN_MS else REENTRY_COOLDOWN_MS
            if (nowMs - lastExit.closedAtMs!! < cool) return true
        }
        val since = nowMs - ROUND_TRIP_WINDOW_MS
        val trips = mine.count { it.ticker.equals(ticker, true) &&
            ((it.entryAtMs ?: 0L) >= since || (it.closedAtMs ?: Long.MIN_VALUE) >= since || it.state != ScalpState.CLOSED) }
        if (trips < maxPerWindow) return false
        val key = "$variantId|${ticker.uppercase()}"
        val last = runawayLogged[key]
        if (last == null || nowMs - last >= ROUND_TRIP_WINDOW_MS) {
            runawayLogged[key] = nowMs
            if (runawayLogged.size > 500) runawayLogged.keys.take(250).forEach { runawayLogged.remove(it) }
            runCatching { android.util.Log.w("KashiScalpRisk", "runaway cap: $variantId on $ticker hit $trips round trips in 15 min; entries paused") }
        }
        return true
    }
}
