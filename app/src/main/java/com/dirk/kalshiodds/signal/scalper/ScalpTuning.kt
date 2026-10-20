package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.Serializable

/**
 * What you changed on one strategy (Arena → strategy → Settings). Every
 * field is null until changed, and null means the strategy's own default
 * ([ScalpStrategy]); so an untouched strategy runs exactly as shipped.
 * Prices are in cents, times in seconds. Paper only.
 */
@Serializable
data class ScalpKnobs(
    val enabled: Boolean = true,
    val contracts: Int? = null,
    val targetCents: Double? = null,
    val stopCents: Double? = null,
    val timeoutSeconds: Int? = null,
    val maxPerSide: Int? = null,
    val entryMinCents: Double? = null,
    val entryMaxCents: Double? = null,
    val cadenceSeconds: Int? = null,
    /** The strategy's main trigger ([ScalpTriggers.of]), in that trigger's own unit. */
    val trigger: Double? = null,
    /** Opens new orders only from this minute of the window (0 = the open)… */
    val fromMinute: Int? = null,
    /** …and before this one (15 = the close). On top of the strategy's own timing. */
    val untilMinute: Int? = null,
    /** "YES" or "NO": trade that side only. Null = both. */
    val side: String? = null,
    /** Widest spread (ask − bid, cents) it will trade into. Null = any. */
    val maxSpreadCents: Double? = null,
    /** Most orders it opens in one 15-minute window (per market). Null = no limit. */
    val maxOrdersPerWindow: Int? = null,
    /** Seconds it waits after opening an order before it may open the next one. Null = none. */
    val cooldownSeconds: Int? = null,
    /** Pause it for the rest of the UTC day once its paper loss today reaches this many dollars. Null = never. */
    val dailyLossUsd: Double? = null,
    /** Trailing stop instead of a fixed one (scalps). Null = the strategy's own. */
    val trail: Boolean? = null,
    /** How long a resting entry bid waits to fill, in seconds. Null = the arena's 20 s. */
    val bidWaitSeconds: Int? = null,
    /** The late strategies (Kashi's favourites, late edge): buy only with at most this many seconds left… */
    val secondsLeftMax: Int? = null,
    /** …and at least this many. */
    val secondsLeftMin: Int? = null,
    /** Kashi's favourites: least the ask must sit under the fair price, in cents. */
    val edgeCents: Double? = null
) {
    val isDefault: Boolean get() = this == ScalpKnobs()
}

/**
 * One strategy's settings with every default filled in: what the engine and
 * the strategies actually trade on.
 */
class Tuned(val strategy: ScalpStrategy, val knobs: ScalpKnobs, defaultContracts: Int) {
    companion object {
        /** The arena makes no decision in a window's last 5 s ([PaperScalper.LATE_LAST_SECOND]). */
        const val MIN_SECONDS_LEFT = 5
    }

    val enabled: Boolean = knobs.enabled
    val contracts: Int = (knobs.contracts ?: defaultContracts).coerceIn(1, ScalpTuning.MAX_CONTRACTS)
    val targetDollars: Double = knobs.targetCents?.div(100.0) ?: strategy.targetDollars
    val stopDollars: Double = knobs.stopCents?.div(100.0) ?: strategy.stopDollars
    val timeoutMs: Long = knobs.timeoutSeconds?.let { it * 1000L } ?: strategy.timeoutMs
    val maxPerSide: Int = (knobs.maxPerSide ?: strategy.maxPerSide).coerceAtLeast(1)
    val cadenceSeconds: Int = (knobs.cadenceSeconds ?: strategy.cadenceSeconds).coerceAtLeast(1)

    /** True when the strategy trades its own price band (by default or because you set one). */
    val ownBand: Boolean = knobs.entryMinCents != null || knobs.entryMaxCents != null ||
        strategy.entryMin != PrintGrid.MIN_PRICE || strategy.entryMax != PrintGrid.MAX_PRICE
    val entryMin: Double = knobs.entryMinCents?.div(100.0) ?: strategy.entryMin
    val entryMax: Double = knobs.entryMaxCents?.div(100.0) ?: strategy.entryMax

    /** Second of the window it may open orders from, and the second it stops before. */
    val fromSecond: Int = ((knobs.fromMinute ?: strategy.startMinute).coerceIn(0, 15)) * 60
    val untilSecond: Int = ((knobs.untilMinute ?: 15).coerceIn(0, 15)) * 60

    /** No minute left between [fromSecond] and [untilSecond]: it opens nothing. */
    val noMinutes: Boolean get() = fromSecond >= untilSecond

    fun inMinutes(second: Int): Boolean = second >= fromSecond && second < untilSecond

    /** "YES" / "NO" when you limited it to one side, else null. */
    val onlySide: String? = knobs.side?.uppercase()?.takeIf { it == "YES" || it == "NO" }

    fun sideAllowed(side: String): Boolean = onlySide == null || onlySide.equals(side, ignoreCase = true)

    /** Widest spread it trades into, in dollars; null = any. */
    val maxSpread: Double? = knobs.maxSpreadCents?.takeIf { it > 0.0 }?.div(100.0)

    val maxOrdersPerWindow: Int? = knobs.maxOrdersPerWindow?.takeIf { it > 0 }

    val cooldownMs: Long = (knobs.cooldownSeconds ?: 0).coerceAtLeast(0) * 1000L

    val dailyLossUsd: Double? = knobs.dailyLossUsd?.takeIf { it > 0.0 }

    val trail: Boolean = knobs.trail ?: strategy.trail

    /**
     * Seconds left in the window it buys in, for the late strategies ([ScalpStrategy.fav]); null for the rest.
     * Never under 5 s: the arena stops deciding then.
     */
    val secondsLeft: IntRange? = strategy.fav?.let { f ->
        (knobs.secondsLeftMin ?: f.tteMin).coerceIn(MIN_SECONDS_LEFT, 899)..(knobs.secondsLeftMax ?: f.tteMax).coerceIn(1, 899)
    }

    /** Kashi's favourites: least the ask must sit under the fair price, in dollars. */
    val favGap: Double? = strategy.fav?.let { f -> knobs.edgeCents?.takeIf { it >= 0.0 }?.div(100.0) ?: f.gap }

    /** How long its resting entry bid waits, given the arena's default. */
    fun entryWaitMs(default: Long): Long = knobs.bidWaitSeconds?.takeIf { it > 0 }?.let { it * 1000L } ?: default

    private val spec = ScalpTriggers.of(strategy)

    /** The trigger in its own unit (null for a strategy without one). */
    fun trigger(default: Double): Double = knobs.trigger ?: default

    /** The trigger as the strategies read it: cents become dollars, everything else is as shown. */
    fun triggerValue(default: Double): Double {
        val v = knobs.trigger ?: return default
        return if (spec?.unit == ScalpTriggers.Unit.CENTS || spec?.unit == ScalpTriggers.Unit.PERCENT) v / 100.0 else v
    }
}

/**
 * Every strategy's settings, kept on the phone ([persist]) and read by the
 * engine on every decision. Thread-safe: the map is replaced, never edited.
 */
class ScalpTuning(
    initial: Map<String, ScalpKnobs> = emptyMap(),
    private val persist: (Map<String, ScalpKnobs>) -> Unit = {}
) {
    @Volatile
    private var map: Map<String, ScalpKnobs> = initial.filterValues { !it.isDefault }

    var defaultContracts: Int = DEFAULT_CONTRACTS

    fun knobs(st: ScalpStrategy): ScalpKnobs = map[st.name] ?: ScalpKnobs()

    fun tuned(st: ScalpStrategy): Tuned = Tuned(st, knobs(st), defaultContracts)

    fun all(): Map<String, ScalpKnobs> = map

    /** Strategies with at least one setting changed. */
    fun changed(): Set<ScalpStrategy> = ScalpStrategy.values().filter { map.containsKey(it.name) }.toSet()

    fun set(st: ScalpStrategy, knobs: ScalpKnobs) {
        val next = HashMap(map)
        if (knobs.isDefault) next.remove(st.name) else next[st.name] = knobs
        map = next
        runCatching { persist(next) }
    }

    fun reset(st: ScalpStrategy) = set(st, ScalpKnobs())

    fun resetAll() {
        map = emptyMap()
        runCatching { persist(emptyMap()) }
    }

    companion object {
        const val DEFAULT_CONTRACTS = 10
        const val MAX_CONTRACTS = 100
    }
}

/** Each strategy's main trigger: the one number its signal turns on, and the range it may be set in. */
object ScalpTriggers {
    enum class Unit { CENTS, CONTRACTS, SECONDS, RATIO, MINUTE, PERCENT }

    data class Spec(val label: String, val unit: Unit, val default: Double, val min: Double, val max: Double, val step: Double)

    private fun cents(label: String, d: Double, min: Double, max: Double, step: Double = 1.0) =
        Spec(label, Unit.CENTS, d * 100.0, min, max, step)

    fun of(st: ScalpStrategy): Spec? = when (st) {
        ScalpStrategy.ML_REST -> Spec("Least the model must expect", Unit.CENTS, Double.NaN, -2.0, 2.0, 0.01)
        ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST ->
            cents("Fall in 10 s", ScalpStrategy.FAST_MOVE_DOLLARS, 1.0, 10.0)
        ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST ->
            cents("Rise in 10 s", ScalpStrategy.FAST_MOVE_DOLLARS, 1.0, 10.0)
        ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST ->
            cents("Fall in 30 s", ScalpStrategy.EXTREME_MOVE_DOLLARS, 2.0, 20.0)
        ScalpStrategy.BOUNCE -> cents("Fall from the minute's high", ScalpStrategy.BOUNCE_DROP_DOLLARS, 2.0, 20.0)
        ScalpStrategy.BREAKOUT_50 -> cents("Level it breaks up through", 0.50, 20.0, 80.0)
        ScalpStrategy.IMPROVE_1C -> cents("Widest spread to step into", ScalpStrategy.TIGHT_SPREAD_DOLLARS, 2.0, 10.0)
        ScalpStrategy.FLOW_FADE, ScalpStrategy.FLOW_FOLLOW ->
            Spec("Taker contracts in 30 s", Unit.CONTRACTS, ScalpStrategy.FLOW_MIN_CONTRACTS, 100.0, 5_000.0, 50.0)
        ScalpStrategy.LATE_FAVORITE -> Spec("From minute", Unit.MINUTE, 10.0, 5.0, 13.0, 1.0)
        ScalpStrategy.MOMENTUM_HOLD -> cents("Rise in 30 s", ScalpStrategy.RIDE_MOVE_DOLLARS, 1.0, 15.0)
        ScalpStrategy.LONG_SHOT -> Spec("From minute", Unit.MINUTE, 5.0, 1.0, 13.0, 1.0)
        ScalpStrategy.EARLY_FAVORITE -> Spec("Until minute", Unit.MINUTE, 10.0, 4.0, 13.0, 1.0)
        ScalpStrategy.STRETCH_FADE -> cents("Under its 60 s average", ScalpStrategy.STRETCH_DOLLARS, 1.0, 15.0)
        ScalpStrategy.SQUEEZE_BREAKOUT -> cents("Widest quiet range", ScalpStrategy.SQUEEZE_RANGE_DOLLARS, 1.0, 15.0)
        ScalpStrategy.RANGE_FLOOR -> cents("Narrowest range", ScalpStrategy.FLOOR_MIN_RANGE_DOLLARS, 1.0, 15.0)
        ScalpStrategy.TREND_PULLBACK -> cents("Two-minute rise", ScalpStrategy.PULLBACK_TREND_DOLLARS, 2.0, 20.0)
        ScalpStrategy.WHALE_FOLLOW -> Spec("Biggest single print", Unit.CONTRACTS, ScalpStrategy.WHALE_CONTRACTS, 100.0, 10_000.0, 100.0)
        ScalpStrategy.QUIET_MAKER -> Spec("Flat for", Unit.SECONDS, 30.0, 10.0, 120.0, 5.0)
        ScalpStrategy.OPENING_DRIVE -> cents("Rise since minute 1", ScalpStrategy.DRIVE_DOLLARS, 2.0, 20.0)
        ScalpStrategy.LATE_TIEBREAK -> cents("Rise in the last minute", ScalpStrategy.TIEBREAK_MOVE_DOLLARS, 1.0, 10.0)
        ScalpStrategy.HEAVY_FAVORITE -> Spec("From minute", Unit.MINUTE, 12.0, 8.0, 14.0, 1.0)
        ScalpStrategy.PANIC_FADE -> cents("Fall in 10 s", ScalpStrategy.PANIC_DROP_DOLLARS, 1.0, 15.0)
        ScalpStrategy.STEADY_CLIMB -> cents("Rise in 60 s", 0.04, 2.0, 15.0)
        ScalpStrategy.PENNY_SCALPER -> Spec("Most contracts ahead", Unit.CONTRACTS, ScalpStrategy.PENNY_MAX_QUEUE, 0.0, 2_000.0, 25.0)
        ScalpStrategy.BOOK_IMBALANCE -> Spec("Bid size ÷ ask size", Unit.RATIO, ScalpStrategy.IMBALANCE_RATIO, 1.5, 10.0, 0.5)
        ScalpStrategy.LATE_COMEBACK -> cents("Rise in 30 s", ScalpStrategy.COMEBACK_MOVE_DOLLARS, 1.0, 15.0)
        ScalpStrategy.KASHI_FAV_BASE, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
        ScalpStrategy.KASHI_FAV_LAST30, ScalpStrategy.KASHI_FAV_NIGHT ->
            Spec("Fair price at least", Unit.PERCENT, st.fav!!.fairMin * 100.0, 80.0, 99.0, 1.0)
        ScalpStrategy.OPENING_RIDE -> Spec("Lean at least", Unit.PERCENT, PaperScalper.OPEN_LEAN * 100.0, 50.0, 70.0, 1.0)
        ScalpStrategy.LATE_EDGE -> cents("Edge over the fee", st.fav!!.gap, 0.0, 10.0)
    }

    /** A trigger value as the settings card shows it. */
    fun text(spec: Spec, v: Double): String = when (spec.unit) {
        Unit.CENTS -> if (spec.step < 1.0) String.format(java.util.Locale.US, "%+.2f¢", v) else "${Math.round(v)}¢"
        Unit.CONTRACTS -> String.format(java.util.Locale.US, "%,d", Math.round(v))
        Unit.SECONDS -> "${Math.round(v)} s"
        Unit.RATIO -> String.format(java.util.Locale.US, "%.1f×", v)
        Unit.MINUTE -> "minute ${Math.round(v)}"
        Unit.PERCENT -> "${Math.round(v)}%"
    }
}
