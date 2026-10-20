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
    }

    private fun c(d: Double): String = "${Math.round(d * 100)}¢"

    /** How it gets in and out, built from the strategy's settings (yours where you changed one). */
    fun rules(st: ScalpStrategy, t: Tuned = Tuned(st, ScalpKnobs(), ScalpTuning.DEFAULT_CONTRACTS)): List<String> {
        val entry = if (st.taker) "Buys at the ask the moment the signal fires (taker fee)."
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
        val size = "${t.contracts} contracts an order, up to ${t.maxPerSide} at once per side; looks every ${t.cadenceSeconds} s."
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
        t.onlySide?.let { add("Buys $it only.") }
        t.maxSpread?.let { add("Skips a spread wider than ${c(it)}.") }
        t.maxOrdersPerWindow?.let { add("At most $it order${if (it == 1) "" else "s"} a window.") }
        if (t.cooldownMs > 0) add("Waits ${t.cooldownMs / 1000} s after an order before the next.")
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
        val row = rows(state, trades).first { it.strategy == st }
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
