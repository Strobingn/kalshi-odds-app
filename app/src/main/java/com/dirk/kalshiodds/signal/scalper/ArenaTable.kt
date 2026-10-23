package com.dirk.kalshiodds.signal.scalper

import java.util.Locale

/**
 * The Arena's "Compare" table and the one-strategy page: every strategy in
 * one sortable, filterable list, and everything about one strategy (its
 * rules in words, its record, its trades). Pure and unit tested
 * (`ArenaTableTest`). Paper dollars after fees.
 *
 * Totals (trades, net, win rate, fees, fills) come from the ledger, which
 * counts every close; the per-trade numbers (¢ a contract, drawdown, hold
 * time, streaks, best and worst trade) come from the trade file the charts
 * read, so they cover its last few thousand closes.
 */
object ArenaTable {

    /** How a strategy enters and leaves, for the filter chips. */
    enum class Family(val label: String) {
        ALL("All"),
        BUY_NOW("Buy now"),
        RESTING("Resting"),
        HOLD("Hold to close"),
        NEW("New"),
        FLEET("20 fleet"),
        MICRO("4 micro"),
        TRADED("Has traded")
    }

    enum class SortKey(val label: String, val short: String) {
        NET("Net", "net"),
        TRADES("Trades", "trades"),
        WIN_RATE("Win rate", "won"),
        CENTS("¢ a contract", "¢/ct"),
        PROFIT_FACTOR("Profit factor", "PF"),
        DRAWDOWN("Drawdown", "DD"),
        HOLD_TIME("Hold time", "hold"),
        FILL_RATE("Fill rate", "filled")
    }

    data class Row(
        val strategy: ScalpStrategy,
        val trades: Int,
        val netUsd: Double,
        val winRate: Double?,
        /** Average result per contract, cents; null before a trade in the file. */
        val cents: Double?,
        /** Won ÷ lost; null until something was lost. */
        val profitFactor: Double?,
        val maxDrawdownUsd: Double,
        val avgHoldSec: Double?,
        /** Filled ÷ posted for a resting strategy; null for buy-now ones and before a post. */
        val fillRate: Double?,
        val verdict: String
    ) {
        /** The number a sort key sorts on; null sorts last whichever way the table is sorted. */
        fun value(key: SortKey): Double? = when (key) {
            SortKey.NET -> if (trades == 0) null else netUsd
            SortKey.TRADES -> trades.toDouble()
            SortKey.WIN_RATE -> winRate
            SortKey.CENTS -> cents
            SortKey.PROFIT_FACTOR -> profitFactor
            // Smaller is better, so the "best first" order puts the shallowest first.
            SortKey.DRAWDOWN -> if (trades == 0) null else -maxDrawdownUsd
            SortKey.HOLD_TIME -> avgHoldSec
            SortKey.FILL_RATE -> fillRate
        }
    }

    fun familyOf(st: ScalpStrategy): Family = when {
        st.hold -> Family.HOLD
        st.taker -> Family.BUY_NOW
        else -> Family.RESTING
    }

    fun isNew(st: ScalpStrategy): Boolean = st.ordinal >= FIRST_NEW.ordinal

    private val FIRST_NEW = ScalpStrategy.STRETCH_FADE

    fun matches(row: Row, family: Family): Boolean = when (family) {
        Family.ALL -> true
        Family.NEW -> isNew(row.strategy)
        Family.FLEET -> row.strategy.fleet
        Family.MICRO -> row.strategy.micro
        Family.TRADED -> row.trades > 0
        else -> familyOf(row.strategy) == family
    }

    /** One row per strategy still running ([ScalpStrategy.ACTIVE]), in their own order. */
    fun rows(state: ScalperState, trades: List<ScalpTrade>, strategies: List<ScalpStrategy> = ScalpStrategy.ACTIVE): List<Row> {
        val perTrade = ArenaStats.strategyStats(trades).associateBy { it.strategy }
        return strategies.map { st ->
            val s = state.stats(st.name)
            val t = perTrade[st]
            Row(
                strategy = st,
                trades = s.closed,
                netUsd = s.pnlUsd,
                winRate = s.winRate,
                cents = t?.expectancyCents,
                profitFactor = if (s.lostUsd > 1e-12) s.wonUsd / s.lostUsd else null,
                maxDrawdownUsd = t?.maxDrawdownUsd ?: 0.0,
                avgHoldSec = t?.avgHoldSec,
                fillRate = if (st.taker || s.posted == 0) null else (s.filled.toDouble() / s.posted).coerceIn(0.0, 1.0),
                verdict = ScalperResults.verdict(s)
            )
        }
    }

    /** Filtered, then sorted best first (or worst first); rows without the number go last either way. */
    fun view(rows: List<Row>, family: Family, key: SortKey, bestFirst: Boolean = true): List<Row> {
        val kept = rows.filter { matches(it, family) }
        val (has, missing) = kept.partition { it.value(key) != null }
        val sorted = if (bestFirst) has.sortedByDescending { it.value(key)!! } else has.sortedBy { it.value(key)!! }
        return sorted + missing
    }

    /** The table's last column: the sort metric, or ¢ a contract when the sort is already a column. */
    fun extraColumn(key: SortKey): SortKey =
        if (key == SortKey.NET || key == SortKey.TRADES || key == SortKey.WIN_RATE) SortKey.CENTS else key

    /** A cell's text. */
    fun format(row: Row, key: SortKey): String = when (key) {
        SortKey.NET -> if (row.trades == 0) "–" else ScalperResults.money(row.netUsd)
        SortKey.TRADES -> "${row.trades}"
        SortKey.WIN_RATE -> ScalperResults.pct(row.winRate)
        SortKey.CENTS -> row.cents?.let { String.format(Locale.US, "%+.2f", it) } ?: "–"
        SortKey.PROFIT_FACTOR -> row.profitFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "–"
        SortKey.DRAWDOWN -> if (row.trades == 0) "–" else ScalperResults.plainMoney(row.maxDrawdownUsd)
        SortKey.HOLD_TIME -> row.avgHoldSec?.let { holdText(it) } ?: "–"
        SortKey.FILL_RATE -> ScalperResults.pct(row.fillRate)
    }

    fun holdText(sec: Double): String = when {
        sec < 120 -> "${sec.toInt()}s"
        sec < 3600 -> "${(sec / 60).toInt()}m"
        else -> String.format(Locale.US, "%.1fh", sec / 3600)
    }

    // ------------------------------------------------------------ one strategy

    /** What the strategy waits for, in one sentence. */
    fun signal(st: ScalpStrategy): String = when (st) {
        ScalpStrategy.ML_REST -> "The trained model expects a resting bid posted now to make money."
        ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST -> "The side's price fell 2¢ or more in 10 seconds."
        ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST -> "The side's price rose 2¢ or more in 10 seconds."
        ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST -> "The side's price fell 6¢ or more in 30 seconds."
        ScalpStrategy.BOUNCE -> "A 5¢ fall from the minute's high, then the first 1–3¢ back up, spread at most 2¢."
        ScalpStrategy.BREAKOUT_50 -> "The side crossed up through 50¢ in the last 10 seconds, spread at most 2¢."
        ScalpStrategy.IMPROVE_1C -> "From minute 3, a spread of 2¢ or more: bid 1¢ above the best bid."
        ScalpStrategy.FLOW_FADE -> "Takers bought the other side 3:1, 500+ contracts in 30 seconds."
        ScalpStrategy.LATE_FAVORITE -> "Minutes 10–13, the side costs 80–95¢."
        ScalpStrategy.EARLY_FAVORITE -> "Minutes 3–10, the side costs 65–85¢."
        ScalpStrategy.MOMENTUM_HOLD -> "Minutes 5–13, the side rose 4¢ or more in 30 seconds, 50–85¢."
        ScalpStrategy.LONG_SHOT -> "Minutes 5–13, the side costs 10–20¢."
        ScalpStrategy.STRETCH_FADE -> "The mid sits 3¢ or more under its own 60-second average, spread at most 2¢."
        ScalpStrategy.SQUEEZE_BREAKOUT -> "Two quiet minutes (range 4¢ or less), then the mid breaks 1¢ above that range."
        ScalpStrategy.RANGE_FLOOR -> "A 3–8¢ two-minute range, and the mid is back on its low."
        ScalpStrategy.TREND_PULLBACK -> "Up 5¢ or more over two minutes, then down 1¢ in the last 10 seconds."
        ScalpStrategy.FLOW_FOLLOW -> "Takers bought this side 3:1, 500+ contracts in 30 seconds."
        ScalpStrategy.WHALE_FOLLOW -> "One taker bought 1,000+ contracts of this side in a single print in the last 5 seconds."
        ScalpStrategy.QUIET_MAKER -> "The mid has not moved for 30 seconds."
        ScalpStrategy.OPENING_DRIVE -> "Minutes 2–4, up 6¢ or more since minute 1, 50–80¢."
        ScalpStrategy.LATE_TIEBREAK -> "Minutes 12–13, a near coin flip (45–60¢) that rose 2¢ in the last minute."
        ScalpStrategy.HEAVY_FAVORITE -> "Minutes 12–13, the side costs 92–97¢."
        ScalpStrategy.PANIC_FADE -> "Down 4¢ or more in 10 seconds with 300+ contracts sold into it."
        ScalpStrategy.STEADY_CLIMB -> "Up 1¢ over 10 s, 3¢ over 30 s and 4¢ over 60 s."
        ScalpStrategy.PENNY_SCALPER -> "A 1¢ spread with at most 200 contracts ahead on the bid (needs the live book)."
        ScalpStrategy.BOOK_IMBALANCE -> "The bid shows 3× the ask's size and 300+ contracts (needs the live book)."
        ScalpStrategy.LATE_COMEBACK -> "Minutes 10–13, an underdog at 20–40¢ up 4¢ or more in 30 seconds."
        ScalpStrategy.KASHI_FAV_BASE, ScalpStrategy.KASHI_FAV_STRICT, ScalpStrategy.KASHI_FAV_97,
        ScalpStrategy.KASHI_FAV_LAST30, ScalpStrategy.KASHI_FAV_NIGHT -> {
            val f = st.fav!!
            "${f.tteMin}–${f.tteMax} s left, the live Bitcoin price makes the side at least ${c(f.fairMin)} to win, " +
                "and its ask is ${c(f.gap)} or more under that" + (if (f.offUsHours) "; not 13:00–21:00 UTC." else ".")
        }
        ScalpStrategy.OPENING_RIDE ->
            "First minute of the window: the side the live Bitcoin price leans to (fair price 51% or more; " +
                "the market's favourite while the index is not in), once a window."
        ScalpStrategy.LATE_EDGE -> {
            val f = st.fav!!
            "${f.tteMin}–${f.tteMax} s left, the settlement average so far plus the live Bitcoin price make the side at least " +
                "${c(f.fairMin)} to win, and its ask is under that by the taker fee plus ${c(f.gap)}; once a window."
        }
        ScalpStrategy.FLEET_LOCKED_IN -> "Final minute, 30+ index prints banked: the chance the 60-print average ends at or above the strike beats the ask (or a bid 1 tick up) by the fee plus 1¢."
        ScalpStrategy.FLEET_SPIKE_FADE -> "Final minute: a 3σ one-second index jump the market priced 3¢+ beyond what (60 − k)/60 of it can move the average; rest on the other side."
        ScalpStrategy.FLEET_VWAP_SLOPE -> "90–45 s left: the 30 s slope of the exchanges' volume-weighted price is over 2 standard errors; project it 30 s and rest on that side."
        ScalpStrategy.FLEET_TAIL_HARVEST -> "Last 45 s: turning the average would take a 6σ move; rest a bid at 99.1–99.6¢ on the side it locks in."
        ScalpStrategy.FLEET_FAT_TAIL -> "Bitcoin 1.8–4σ from the strike: a Student-t (ν 4.2) prices the far side above the market; rest at least 1.5¢ under it."
        ScalpStrategy.FLEET_NIGHT_THETA -> "This hour's volatility under 65% of the day's and the price 0.15%+ from the strike: rest on the leader at the seasonal fair price."
        ScalpStrategy.FLEET_RV_IV -> "The market's implied volatility and the 5 s realized volatility differ by 1.5σ of their gap: rest on the side realized volatility favours."
        ScalpStrategy.FLEET_CROSS_ASSET -> "An altcoin's 15-minute correlation with Bitcoin over 0.85 and up 0.25 in 5 windows: buy its 15-minute market where Bitcoin's move says it lags (one at a time)."
        ScalpStrategy.FLEET_AS_MAKER -> "Two-sided Avellaneda–Stoikov quotes around a reservation price skewed by inventory; out at the other quote."
        ScalpStrategy.FLEET_LADDER -> "First 10 minutes, steady volatility: seven resting bids 1¢ apart under the best bid; each fill offers out at +2¢ + 0.5¢ a rung."
        ScalpStrategy.FLEET_OBI -> "The top three book levels lean 0.65+ to one side with a 3¢+ spread: bid 1¢ inside on that side."
        ScalpStrategy.FLEET_VPIN_FLIP -> "VPIN over 0.70 on 500-contract buckets: pause the makers 30 s and buy the informed side when its 20 s move beats fee plus spread."
        ScalpStrategy.FLEET_NO_BIAS -> "YES at 10–25¢ with Bitcoin under the strike, where such YES has won 2.5¢+ less often than priced: rest NO."
        ScalpStrategy.FLEET_NOWCAST -> "A CF-style index from Coinbase, Kraken, Bitstamp and Gemini books is across the strike before the index tick: buy that side."
        ScalpStrategy.FLEET_POLY_ARB -> "Kalshi YES + Polymarket Down (or NO + Up) cost under $1 after both fees, gas and a basis buffer, 0.2%+ from the strike: buy both."
        ScalpStrategy.FLEET_PERP_HEDGE -> "The binary 2¢+ off its Student-t fair: rest on it; when it fills, hedge its delta with KXBTCPERP until the close."
        ScalpStrategy.FLEET_HOURLY_DOMINANCE -> "Windows closing on the hour: this window's side plus the hourly market's opposite side at a nested strike cost under $1 after fees: buy both."
        ScalpStrategy.FLEET_MACRO_REVERT -> "Within 5 minutes of a CPI, jobs or FOMC release: a 2.5σ move whose repricing has stalled, spread 5¢+: bid inside on the reversion."
        ScalpStrategy.FLEET_PIN_GAMMA -> "Last 3 minutes, near even (42–58¢) and within 0.15% of the strike: two-sided quotes δ around the fair price, flipped every few seconds."
        ScalpStrategy.FLEET_TIMESFM -> "TimesFM 2.5's forecast of the index gives a side 1.5¢+ over its price (plus fee) while the regime score is 0.60+: rest early, take late."
        ScalpStrategy.MICRO_OBI_MAKER -> "The top five book levels lean 35%+ to one side and the Student-t fair from the index beats the bid by 2.5¢+: rest a bid (1¢ inside a 3¢+ spread), sell 3¢ up."
        ScalpStrategy.MICRO_BRTI_PULSE -> "The settlement index moved 0.75σ+ in 10 s and the fair price ran 2¢+ further than the Kalshi book: pay the ask on a 2¢ edge over the fee, sell where the book should catch up."
        ScalpStrategy.MICRO_VWAP_REV -> "The book 3¢+ away from the last 30 s volume-weighted price (200+ contracts) while the index stayed calm: rest on the cheap side, sell at the VWAP."
        ScalpStrategy.MICRO_THETA_FAV -> "90–240 s left, the leading side's Student-t fair (on the settlement average) 80%+: pay the ask on a 2¢ edge over the fee, else rest a bid 3¢+ under the fair; hold."
    }

    private fun c(d: Double): String = "${Math.round(d * 100)}¢"

    /** How it gets in and out, built from the strategy's settings (yours where you changed one). */
    fun rules(st: ScalpStrategy, t: Tuned = Tuned(st, ScalpKnobs(), ScalpTuning.DEFAULT_CONTRACTS)): List<String> {
        val entry = if (st.hybrid) "Buys at the ask when its edge clears the taker fee, else rests a bid (sized at quarter Kelly)."
        else if (st.taker) "Buys at the ask the moment the signal fires (taker fee)."
        else "Rests a bid and waits up to ${t.entryWaitMs(ScalperEngine.Config().entryWaitMs) / 1000} s for sellers to reach it (no fee)."
        val exit = if (st.hold) {
            "Holds to the settlement: $1 if its side wins, $0 if not" +
                (if (t.stopDollars > 0.0) ", unless the bid falls ${c(t.stopDollars)} below the entry (sold, taker fee)." else ".")
        } else if (t.trail) {
            "Out at +${c(t.targetDollars)} (resting offer), or sold at the bid once it falls ${c(t.stopDollars)} under " +
                "the best bid since the entry (a trailing stop, taker fee). Anything still open settles."
        } else {
            "Out at +${c(t.targetDollars)} (resting offer), or sold at the bid at −${c(t.stopDollars)} " +
                "or after ${t.timeoutMs / 1000} s (taker fee)."
        }
        val band = if (t.ownBand) {
            "Trades only between ${c(t.entryMin)} and ${c(t.entryMax)}."
        } else {
            "Trades between ${c(PrintGrid.MIN_PRICE)} and ${c(PrintGrid.MAX_PRICE)}."
        }
        val size = "${t.contracts} contracts an order, up to ${t.maxPerSide} at once per side; looks every ${t.cadenceSeconds} s" +
            (if (t.knobs.cooldownSeconds == null && t.cooldownMs > 0) "; ${t.cooldownMs / 1000} s between buys." else ".")
        val spec = ScalpTriggers.of(st)
        val trigger = t.knobs.trigger
        val signal = signal(st) + if (spec != null && trigger != null) " Your setting: ${spec.label.lowercase()} ${ScalpTriggers.text(spec, trigger)}." else ""
        return listOf(signal, entry, exit, band, size) + limits(t) +
            if (!t.enabled) listOf("Switched off in its settings: it opens nothing new.") else emptyList()
    }

    /** Your extra limits, one line each; empty when none are set. */
    fun limits(t: Tuned): List<String> = buildList {
        val k = t.knobs
        if (k.fromMinute != null || k.untilMinute != null) {
            add(
                if (t.noMinutes) "Your window has no minutes left: it opens nothing."
                else "Opens orders only from minute ${t.fromSecond / 60} until minute ${t.untilSecond / 60}."
            )
        }
        if (k.secondsLeftMax != null || k.secondsLeftMin != null) {
            t.secondsLeft?.let { w ->
                add(if (w.first > w.last) "Your seconds-left window is empty: it opens nothing." else "Buys only with ${w.first}–${w.last} s left.")
            }
        }
        if (k.edgeCents != null) t.favGap?.let { add("Needs the ask ${c(it)} or more under the fair price.") }
        t.onlySide?.let { add("Buys $it only.") }
        t.maxSpread?.let { add("Skips a spread wider than ${c(it)}.") }
        t.maxOrdersPerWindow?.let { add("At most $it order${if (it == 1) "" else "s"} a window.") }
        if (k.cooldownSeconds != null && t.cooldownMs > 0) add("Waits ${t.cooldownMs / 1000} s after an order before the next.")
        t.dailyLossUsd?.let { add(String.format(java.util.Locale.US, "Pauses for the rest of the day (UTC) once it is down \$%.0f today.", it)) }
    }

    /** Everything on the one-strategy page that is a number. */
    data class Detail(
        val row: Row,
        val stats: ScalpStats,
        val streaks: Pair<Int, Int>,
        val best: ScalpTrade?,
        val worst: ScalpTrade?,
        /** The strategy's own trades from the file, newest first. */
        val recent: List<ScalpTrade>,
        val sampled: Int
    )

    fun detail(st: ScalpStrategy, state: ScalperState, trades: List<ScalpTrade>, recent: Int = 20): Detail {
        val mine = trades.filter { it.strategy == st.name }
        val row = rows(state, trades, listOf(st)).first()
        val ps = ArenaStats.strategyStats(mine).firstOrNull()
        return Detail(
            row = row,
            stats = state.stats(st.name),
            streaks = (ps?.longestWinStreak ?: 0) to (ps?.longestLossStreak ?: 0),
            // The real extremes even when every trade won (or lost): the best of a winless record is its smallest loss.
            best = mine.maxByOrNull { it.pnlUsd },
            worst = mine.minByOrNull { it.pnlUsd },
            recent = mine.sortedByDescending { it.closedAtMs }.take(recent),
            sampled = mine.size
        )
    }
}
