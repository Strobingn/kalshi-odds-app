package com.dirk.kalshiodds.signal.scalper

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * The second set of Arena numbers: per-strategy trading stats, drawdown,
 * the strategy × price and strategy × day grids, hold time and the markers
 * on the live window chart. Pure and unit tested (`ArenaStatsTest`). Paper
 * dollars after fees; "¢ a contract" is a trade's net divided by its size.
 */
object ArenaStats {

    // ------------------------------------------------------------ per strategy

    data class StrategyStats(
        val strategy: ScalpStrategy,
        val trades: Int,
        val wins: Int,
        val netUsd: Double,
        /** Average result per contract, cents, after fees. */
        val expectancyCents: Double,
        /** Dollars won ÷ dollars lost; null when nothing was lost yet. */
        val profitFactor: Double?,
        /** Deepest fall of the running P&L from its high, dollars (0 = never below its high). */
        val maxDrawdownUsd: Double,
        /** Average seconds from fill to exit; null when there are no trades. */
        val avgHoldSec: Double?,
        val longestWinStreak: Int,
        val longestLossStreak: Int
    )

    /** One row per strategy that has closed a trade, best net first. */
    fun strategyStats(trades: List<ScalpTrade>): List<StrategyStats> {
        val byName = ScalpStrategy.values().associateBy { it.name }
        return trades.groupBy { it.strategy }.mapNotNull { (name, rows) ->
            val st = byName[name] ?: return@mapNotNull null
            val sorted = rows.sortedBy { it.closedAtMs }
            var won = 0.0
            var lost = 0.0
            var run = 0.0
            var peak = 0.0
            var dd = 0.0
            var w = 0
            var l = 0
            var bestW = 0
            var bestL = 0
            for (t in sorted) {
                if (t.pnlUsd > 0) won += t.pnlUsd else lost -= t.pnlUsd
                run += t.pnlUsd
                if (run > peak) peak = run
                if (peak - run > dd) dd = peak - run
                when {
                    t.pnlUsd > 0 -> { w++; l = 0 }
                    t.pnlUsd < 0 -> { l++; w = 0 }
                    else -> { w = 0; l = 0 }
                }
                if (w > bestW) bestW = w
                if (l > bestL) bestL = l
            }
            StrategyStats(
                strategy = st,
                trades = rows.size,
                wins = rows.count { it.pnlUsd > 0 },
                netUsd = run,
                expectancyCents = rows.map { ArenaCharts.centsPerContract(it) }.average(),
                profitFactor = if (lost > 1e-12) won / lost else null,
                maxDrawdownUsd = dd,
                avgHoldSec = rows.map { (it.closedAtMs - it.filledAtMs).coerceAtLeast(0L) / 1000.0 }.average(),
                longestWinStreak = bestW,
                longestLossStreak = bestL
            )
        }.sortedByDescending { it.netUsd }
    }

    // ------------------------------------------------------------ drawdown

    /** How far below its running high the curve sits at each point: 0 at a new high, negative under it. */
    fun drawdown(points: List<CurvePoint>): List<CurvePoint> {
        var peak = Double.NEGATIVE_INFINITY
        return points.map { p ->
            if (p.pnlUsd > peak) peak = p.pnlUsd
            CurvePoint(p.tMs, p.pnlUsd - peak)
        }
    }

    fun maxDrawdown(points: List<CurvePoint>): Double = -(drawdown(points).minOfOrNull { it.pnlUsd } ?: 0.0)

    // ------------------------------------------------------------ grids

    /** One cell of a strategy × column grid. Empty cells are null. */
    data class Cell(val trades: Int, val netUsd: Double, val avgCents: Double)

    data class Grid(
        val rows: List<ScalpStrategy>,
        val columns: List<String>,
        /** [rows] × [columns]. */
        val cells: List<List<Cell?>>
    ) {
        val isEmpty: Boolean get() = cells.all { r -> r.all { it == null } }
    }

    internal fun grid(
        trades: List<ScalpTrade>,
        columns: List<String>,
        column: (ScalpTrade) -> Int?
    ): Grid {
        val byName = ScalpStrategy.values().associateBy { it.name }
        val acc = HashMap<Pair<ScalpStrategy, Int>, MutableList<ScalpTrade>>()
        for (t in trades) {
            val st = byName[t.strategy] ?: continue
            val c = column(t) ?: continue
            if (c !in columns.indices) continue
            acc.getOrPut(st to c) { ArrayList() } += t
        }
        val rows = ScalpStrategy.values().filter { st -> columns.indices.any { (st to it) in acc } }
        val cells = rows.map { st ->
            columns.indices.map { c ->
                acc[st to c]?.let { list ->
                    Cell(list.size, list.sumOf { it.pnlUsd }, list.map { ArenaCharts.centsPerContract(it) }.average())
                }
            }
        }
        return Grid(rows, columns, cells)
    }

    /** Entry price bands, in the traded side's cents: 0–10¢ … 90–100¢. */
    val PRICE_BANDS: List<String> = (0 until 10).map { "${it * 10}" }

    /** Where in the price range each strategy makes or loses money (by the side's entry price). */
    fun priceBands(trades: List<ScalpTrade>): Grid =
        grid(trades, PRICE_BANDS) { t -> floor(t.entry * 10.0).toInt().coerceIn(0, 9) }

    fun priceBandLabel(i: Int): String = "${i * 10}–${i * 10 + 10}¢"

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** The last [days] calendar days (the phone's clock) ending [today], one column each, oldest first. */
    fun daily(trades: List<ScalpTrade>, today: LocalDate, days: Int = 14, zone: ZoneId = ZoneId.systemDefault()): Grid {
        val first = today.minusDays((days - 1).toLong())
        val cols = (0 until days).map { DAY.format(first.plusDays(it.toLong())) }
        return grid(trades, cols) { t ->
            val d = Instant.ofEpochMilli(t.closedAtMs).atZone(zone).toLocalDate()
            val i = (d.toEpochDay() - first.toEpochDay()).toInt()
            if (i in 0 until days) i else null
        }
    }

    // ------------------------------------------------------------ hold time

    data class HoldBucket(val label: String, val trades: Int, val avgCents: Double?)

    /** Upper edges, seconds; the last bucket is open, and trades held to the settlement have their own. */
    private val HOLD_EDGES = listOf(10L, 30L, 60L, 120L, 300L)
    val HOLD_LABELS = listOf("<10s", "10–30s", "30–60s", "1–2m", "2–5m", "5m+", "close")

    fun holdBuckets(trades: List<ScalpTrade>, strategy: ScalpStrategy? = null): List<HoldBucket> {
        val buckets = List(HOLD_LABELS.size) { ArrayList<Double>() }
        for (t in trades) {
            if (strategy != null && t.strategy != strategy.name) continue
            val i = if (t.kind == ExitKind.SETTLED.name) {
                HOLD_LABELS.lastIndex
            } else {
                val held = (t.closedAtMs - t.filledAtMs).coerceAtLeast(0L) / 1000L
                HOLD_EDGES.indexOfFirst { held < it }.let { if (it < 0) HOLD_EDGES.size else it }
            }
            buckets[i] += ArenaCharts.centsPerContract(t)
        }
        return HOLD_LABELS.mapIndexed { i, label ->
            HoldBucket(label, buckets[i].size, buckets[i].takeIf { it.isNotEmpty() }?.average())
        }
    }

    // ------------------------------------------------------------ live window

    enum class MarkerKind { ENTRY, EXIT, OPEN }

    /** One mark on the live chart, in YES terms (a DOWN trade at 40¢ sits at 60¢ on the YES line). */
    data class Marker(
        val strategy: ScalpStrategy,
        val side: String,
        val kind: MarkerKind,
        /** Seconds since the window opened. */
        val second: Double,
        val yesPrice: Double,
        /** The trade's net (exits and their entries); null while open. */
        val pnlUsd: Double?,
        /** Entry and exit in the side's own cents, for the readout. */
        val entry: Double,
        val exit: Double?,
        /** Same for a trade's entry and its exit (`run-id`); null for an open position. */
        val key: String? = null
    )

    fun markers(window: LiveWindow, trades: List<ScalpTrade>): List<Marker> {
        val byName = ScalpStrategy.values().associateBy { it.name }
        fun sec(ms: Long) = ((ms - window.openMs) / 1000.0).coerceIn(0.0, PrintGrid.SECONDS.toDouble())
        fun yes(side: String, p: Double) = if (side.equals("NO", ignoreCase = true)) 1.0 - p else p
        val out = ArrayList<Marker>()
        for (t in trades) {
            if (t.ticker != window.ticker) continue
            val st = byName[t.strategy] ?: continue
            val key = "${t.run}-${t.id}"
            out += Marker(st, t.side, MarkerKind.ENTRY, sec(t.filledAtMs), yes(t.side, t.entry), t.pnlUsd, t.entry, t.exit, key)
            out += Marker(st, t.side, MarkerKind.EXIT, sec(t.closedAtMs), yes(t.side, t.exit), t.pnlUsd, t.entry, t.exit, key)
        }
        for (p in window.open) {
            out += Marker(
                p.order.strategy, p.order.side, MarkerKind.OPEN, sec(p.filledAtMs),
                yes(p.order.side, p.order.price), null, p.order.price, null
            )
        }
        return out.sortedBy { it.second }
    }

    /** `Bounce · buy now · UP · 52¢ → 60¢ · +$0.71`, or `… · open at 52¢`. */
    fun markerLabel(m: Marker): String {
        val side = ScalperResults.sideLabel(m.side)
        val head = "${m.strategy.label} · $side"
        return if (m.kind == MarkerKind.OPEN || m.exit == null) {
            "$head · open at ${ScalperResults.cents(m.entry)}"
        } else {
            val exit = if (m.exit >= 0.999) "$1" else if (m.exit <= 0.001) "$0" else ScalperResults.cents(m.exit)
            "$head · ${ScalperResults.cents(m.entry)} → $exit · ${ScalperResults.money(m.pnlUsd ?: 0.0)}"
        }
    }

    /** `3:07` into the window. */
    fun clock(second: Double): String {
        val s = second.toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
