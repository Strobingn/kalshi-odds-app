package com.dirk.kalshiodds.decision

/**
 * 0.3.65 'v2' copies (PAPER, BTC): each original's signals are copied as a separate trade that
 *  - buys only when the ask is ≤ 50¢ (2 s entry delay, displayed-size fill, 10-contract clip, taker fee),
 *  - keeps the original's stop,
 *  - exits before the final minute unless fresh CF evidence supports a profitable hold,
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
    const val EXIT_DELAY_MS = 250L
    const val EXIT_BY_S = 60.0
    const val BOOK_MAX_AGE_MS = 3_000L

    /** Stops must be reachable even when the original stop exceeds a cheap contract's entry price. */
    fun stopPrice(entry: Double, distance: Double): Double = (entry - distance).coerceAtLeast(0.01)

    fun fresh(q: ScalpRule.Quote): Boolean = !q.bookGap && q.nowMs - q.bookAtMs in 0..BOOK_MAX_AGE_MS

    /** A conservative hold hypothesis, not knowledge of the settlement result. Missing CF data means exit. */
    fun closeExit(q: ScalpRule.Quote, side: String, entry: Double, n: Int): String? {
        if (q.tauS > EXIT_BY_S) return null
        val bid = q.bid(side)
        val fair = q.cfFair(side)
        val hold = fresh(q) && bid != null && bid.isFinite() && bid >= 0.90 &&
            fair != null && fair.isFinite() && fair >= 0.97 &&
            fair > bid - ScalpRule.feePerContract(n.coerceAtLeast(1), bid) + 0.01 &&
            ScalpRule.netAfterFees(entry, bid, n.coerceAtLeast(1)) > 0.0
        return if (hold) null else "v2 exit before close: no strong fresh winning evidence"
    }

    /** Bounded paper experiment. Positive / inconclusive books keep their existing entry, stop and trail. */
    data class Plan(val spec: Spec, val stop: Double, val arm: Double, val adjusted: Boolean, val note: String)
    fun plan(id: String, windows: List<ScalpWindowAgg>, nowMs: Long, scalperId: String = id): Plan {
        val base = spec(id)
        val stop = base.stop ?: stopFor(base.base)
        val rows = windows.filter { it.scalperId == scalperId &&
            it.n > 0 && it.netUsd.isFinite() && it.closeMs != null && it.closeMs <= nowMs }
            .sortedByDescending { it.closeMs }.take(24)
        val n = rows.sumOf { it.n }
        val values = rows.map { it.netUsd / it.n }
        val mean = values.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val se = if (values.size < 2) Double.POSITIVE_INFINITY else
            kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1) / values.size)
        val losing = n >= MIN_TRADES && rows.size >= 3 && rows.sumOf { it.netUsd } < 0 && mean + 2 * se < 0
        if (!losing) return Plan(base, stop, stop, false,
            "v2ai=keep; $n closed / ${rows.size} completed windows; positive or inconclusive")
        val revisedStop = minOf(stop, maxOf(0.04, stop * 0.8))
        return Plan(base.copy(maxAsk = (base.maxAsk - 0.02).coerceAtLeast(0.02),
            trail = minOf(base.trail, 0.02)), revisedStop, minOf(revisedStop, 0.04), true,
            "v2ai=trial; $n closed / ${rows.size} completed windows; negative window mean + 2SE; bounded risk/entry/profit trial")
    }

    private val STOP_RX = Regex("v2stop=([0-9.]+)")
    fun savedStop(t: ScalpTrade): Double = STOP_RX.find(t.note)?.groupValues?.get(1)?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.01..0.99 } ?: (spec(t.variantId).stop ?: stopFor(baseOf(t.variantId)))
    fun savedNumber(note: String, key: String, fallback: Double): Double =
        Regex("(?:^|[ ;·])${Regex.escape(key)}=([0-9.]+)").find(note)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: fallback
    fun tag(note: String, key: String, value: String): String {
        val rx = Regex("${Regex.escape(key)}=[^ ;·]+")
        return if (rx.containsMatchIn(note)) rx.replace(note, "$key=$value") else "$note · $key=$value"
    }

    fun isV2(variantId: String): Boolean = variantId.startsWith(PREFIX)

    /**
     * 0.3.68: per-copy v2 parameters. Plain v2 copies keep MAX_ASK / TRAIL / original stop / top-of-book CLIP,
     * except Shock v2 sf01–sf20 which now each get their own (max ask, trail) pair so they stop trading identically.
     * Extra copies of v2 sf41–sf44: two small tweaks (t1/t2: different max ask = different buys, different trail+stop =
     * different sells) and two size copies (x5/x10) whose paper fills walk the real order-book depth.
     */
    data class Spec(val id: String, val base: String, val maxAsk: Double = MAX_ASK, val trail: Double = TRAIL,
                    val stop: Double? = null, val clip: Int = CLIP, val walk: Boolean = false, val tag: String = "")

    private val SHOCK_ASK = listOf(0.50, 0.48, 0.46, 0.44, 0.42)
    private val SHOCK_TRAIL = listOf(0.02, 0.01, 0.03, 0.04)
    private val SHOCK_SPECS: List<Spec> = (1..20).map { i ->
        val b = String.format(java.util.Locale.US, "sf%02d", i)
        Spec(PREFIX + b, b, maxAsk = SHOCK_ASK[(i - 1) % 5], trail = SHOCK_TRAIL[(i - 1) / 5])
    }
    private val SIMPLE_BASES = listOf("sf41", "sf42", "sf43", "sf44")
    val EXTRA_SPECS: List<Spec> = SIMPLE_BASES.flatMapIndexed { k, b ->
        listOf(
            Spec(PREFIX + b + "t1", b, maxAsk = 0.44 + 0.01 * k, trail = 0.01, stop = 0.10 + 0.02 * k, tag = "tweak 1"),
            Spec(PREFIX + b + "t2", b, maxAsk = 0.52 + 0.01 * k, trail = 0.03, stop = 0.20 + 0.02 * k, tag = "tweak 2"),
            Spec(PREFIX + b + "x5", b, clip = 5, walk = true, tag = "5 contracts"),
            Spec(PREFIX + b + "x10", b, clip = 10, walk = true, tag = "10 contracts")
        )
    }
    private val BY_ID: Map<String, Spec> = (SHOCK_SPECS + EXTRA_SPECS).associateBy { it.id }
    fun spec(variantId: String): Spec = BY_ID[variantId] ?: Spec(variantId, variantId.removePrefix(PREFIX))
    /** Extra (non-plain) v2 copies to spawn for one original signal. */
    fun extrasFor(originalVariantId: String): List<Spec> = EXTRA_SPECS.filter { it.base == originalVariantId }
    fun isExtra(variantId: String): Boolean = EXTRA_SPECS.any { it.id == variantId }
    fun baseOf(variantId: String): String = spec(variantId).base

    /** Walk book levels (best first) for up to [want] contracts at prices passing [ok]. Returns (filled, total $). */
    fun walk(levels: List<Pair<Double, Double>>, want: Int, ok: (Double) -> Boolean): Pair<Int, Double> {
        var n = 0; var usd = 0.0
        for ((px, sz) in levels) {
            if (n >= want || !ok(px)) break
            val take = minOf(want - n, kotlin.math.floor(sz + 1e-9).toInt())
            if (take <= 0) continue
            n += take; usd += take * px
        }
        return n to usd
    }
    fun v2Id(originalVariantId: String): String = PREFIX + originalVariantId
    /** The trade as its original (for model lookup). */
    fun originalView(t: ScalpTrade): ScalpTrade = t.copy(ruleVersion = t.ruleVersion.replace("|${t.variantId}|", "|${baseOf(t.variantId)}|").replace("|$PREFIX", "|"))

    /** Original's per-bet stop in dollars per contract. */
    fun stopFor(originalVariantId: String): Double = when {
        ShockFav.isShockFav(originalVariantId) -> ShockFav.byVariantId(originalVariantId)?.let { ShockFav.stopC(it) / 100.0 } ?: 0.25
        originalVariantId.startsWith(ForecastScalp.RIDE_PREFIX) || originalVariantId.startsWith(ForecastScalp.FADE_PREFIX) -> ForecastScalp.STOP_C / 100.0
        else -> ScalpParams.byId(originalVariantId)?.stop ?: 0.10
    }.coerceIn(0.01, 0.99)

    fun entryOk(ask: Double?, maxAsk: Double = MAX_ASK): Boolean = ask != null && ask > 0.0 && ask <= maxAsk + 1e-9

    /** Armed once the per-contract gain after both fees reaches the stop size. */
    fun armed(entry: Double, bid: Double, stop: Double, n: Int = CLIP): Boolean = ScalpRule.netAfterFees(entry, bid, n.coerceAtLeast(1)) >= stop - 1e-9

    /** Exit reason or null. [wasArmed] persists across quotes (peak tracked by the caller). */
    fun exit(entry: Double, stop: Double, peakBid: Double, bid: Double, wasArmed: Boolean, n: Int = CLIP, trail: Double = TRAIL): String? = when {
        bid <= stopPrice(entry, stop) + 1e-9 -> "stop −${Math.round(stop * 100)}¢"
        (wasArmed || armed(entry, bid, stop, n)) && bid <= peakBid - trail + 1e-9 -> "v2 trail ${Math.round(trail * 100)}¢ off peak after +${Math.round(stop * 100)}¢ net"
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
