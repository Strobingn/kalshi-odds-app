package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.TakerCost
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Arena's "is there an edge?" numbers: price calibration for the trades
 * held to the settlement, a resampled projection of what the next trades
 * could do, the rolling result per contract, results by minute of the
 * window and how alike the strategies trade. Pure and unit tested
 * (`ArenaEdgeTest`). Paper dollars after fees.
 */
object ArenaEdge {

    // ------------------------------------------------------------ calibration

    /**
     * One 10¢ band of entry prices for trades held to the settlement. The
     * price paid is the market's own chance of winning; a band whose
     * [winRate] sits above [breakEven] (price plus the average entry fee)
     * made money.
     */
    data class CalBand(
        val lo: Double,
        val hi: Double,
        val trades: Int,
        val wins: Int,
        /** Average price paid, dollars (= the chance the market gave it). */
        val avgPrice: Double,
        /** Average price plus the average entry fee per contract. */
        val breakEven: Double
    ) {
        val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades
        /** Standard error of [winRate]; the chart draws ±2 of these. */
        val stdErr: Double get() = if (trades == 0) 0.0 else sqrt(winRate * (1 - winRate) / trades).coerceAtLeast(0.5 / trades)
        /** Won ahead of the price after the fee, by more than two standard errors. */
        val clearlyAbove: Boolean get() = trades >= MIN_BAND_TRADES && winRate - 2 * stdErr > breakEven
        val clearlyBelow: Boolean get() = trades >= MIN_BAND_TRADES && winRate + 2 * stdErr < breakEven
    }

    const val MIN_BAND_TRADES = 5

    /** Settled (held-to-close) trades by entry band; voids are left out. Bands with no trades are dropped. */
    fun calibration(trades: List<ScalpTrade>, strategy: ScalpStrategy? = null): List<CalBand> {
        val acc = List(10) { ArrayList<ScalpTrade>() }
        for (t in trades) {
            if (t.kind != ExitKind.SETTLED.name) continue
            if (strategy != null && t.strategy != strategy.name) continue
            if (t.exit != 0.0 && t.exit != 1.0) continue
            if (t.contracts <= 0) continue
            acc[floor(t.entry * 10.0).toInt().coerceIn(0, 9)] += t
        }
        return acc.mapIndexedNotNull { i, rows ->
            if (rows.isEmpty()) return@mapIndexedNotNull null
            val n = rows.sumOf { it.contracts }
            val price = rows.sumOf { it.entry * it.contracts } / n
            val fee = rows.sumOf { it.entryFeeUsd } / n
            CalBand(
                lo = i / 10.0, hi = (i + 1) / 10.0,
                trades = rows.size,
                wins = rows.count { it.exit == 1.0 },
                avgPrice = price,
                breakEven = price + fee
            )
        }
    }

    // ------------------------------------------------------------ projection

    /** Percentiles of the resampled running P&L after each of [steps] trades. */
    data class Fan(
        val steps: List<Int>,
        val p10: List<Double>,
        val p50: List<Double>,
        val p90: List<Double>,
        /** Share of resampled paths that finish above $0. */
        val pProfit: Double,
        val horizon: Int,
        /** Trades resampled from, and the market windows they fell in. */
        val sample: Int,
        val windows: Int = 0
    )

    const val MIN_FAN_TRADES = 10

    /** Fewer windows than this and the resample is mostly one or two outcomes repeated. */
    const val MIN_FAN_WINDOWS = 5

    /**
     * Draws [paths] futures of [horizon] trades from the strategy's own past
     * results and reports the spread. Trades in one market window share its
     * outcome, so whole windows are drawn (with replacement), each adding all
     * of its trades at once, as the leaderboard's luck test clusters them.
     * It says what the record so far implies; it cannot know whether the
     * future looks like the past. Null with fewer than [MIN_FAN_TRADES]
     * trades or [MIN_FAN_WINDOWS] windows.
     */
    fun projection(
        trades: List<ScalpTrade>,
        strategy: ScalpStrategy? = null,
        horizon: Int = 200,
        paths: Int = 400,
        points: Int = 40,
        seed: Int = 7
    ): Fan? {
        val rows = trades.filter { strategy == null || it.strategy == strategy.name }
        if (rows.size < MIN_FAN_TRADES || horizon < 1 || paths < 1) return null
        val windows = rows.groupBy { it.ticker }.values.map { w -> w.size to w.sumOf { it.pnlUsd } }
        if (windows.size < MIN_FAN_WINDOWS) return null
        val rnd = Random(seed)
        val stride = maxOf(1, horizon / points)
        val steps = (stride..horizon step stride).toMutableList()
        if (steps.last() != horizon) steps += horizon
        val at = Array(steps.size) { DoubleArray(paths) }
        for (p in 0 until paths) {
            var run = 0.0
            var n = 0
            var k = 0
            while (k < steps.size) {
                val (count, pnl) = windows[rnd.nextInt(windows.size)]
                run += pnl
                n += count
                // A window lands all its trades at once: every step it passes reads the same total.
                while (k < steps.size && n >= steps[k]) { at[k][p] = run; k++ }
            }
        }
        fun pct(arr: DoubleArray, q: Double): Double {
            val sorted = arr.sortedArray()
            return sorted[((sorted.size - 1) * q).toInt()]
        }
        return Fan(
            steps = steps,
            p10 = at.map { pct(it, 0.10) },
            p50 = at.map { pct(it, 0.50) },
            p90 = at.map { pct(it, 0.90) },
            pProfit = at.last().count { it > 0.0 }.toDouble() / paths,
            horizon = horizon,
            sample = rows.size,
            windows = windows.size
        )
    }

    // ------------------------------------------------------------ rolling edge

    /** Average result per contract over the last [window] trades, at each trade's close (oldest first). */
    fun rolling(trades: List<ScalpTrade>, strategy: ScalpStrategy? = null, window: Int = 30): List<CurvePoint> {
        val rows = trades.filter { strategy == null || it.strategy == strategy.name }.sortedBy { it.closedAtMs }
        if (rows.size < window || window < 1) return emptyList()
        val cents = rows.map { ArenaCharts.centsPerContract(it) }
        var sum = cents.take(window).sum()
        val out = ArrayList<CurvePoint>(rows.size - window + 1)
        out += CurvePoint(rows[window - 1].closedAtMs, sum / window)
        for (i in window until rows.size) {
            sum += cents[i] - cents[i - window]
            out += CurvePoint(rows[i].closedAtMs, sum / window)
        }
        return ArenaCharts.thin(out, 160)
    }

    // ------------------------------------------------------------ minute of window

    val MINUTES: List<String> = (0 until 15).map { "$it" }

    /** Minute of the 15-minute window the entry filled in (0–14), from the ticker's close time. */
    fun entryMinute(t: ScalpTrade): Int? {
        val close = TakerCost.closeEpochMs(t.ticker) ?: return null
        val open = close - PrintGrid.SECONDS * 1000L
        val m = ((t.filledAtMs - open) / 60_000L).toInt()
        return m.takeIf { t.filledAtMs >= open && it in 0 until 15 }
    }

    fun byMinute(trades: List<ScalpTrade>): ArenaStats.Grid = ArenaStats.grid(trades, MINUTES) { entryMinute(it) }

    // ------------------------------------------------------------ correlation

    const val MIN_SHARED_WINDOWS = 8

    /**
     * Correlation of each pair's net per window, over the windows both
     * traded (at least [MIN_SHARED_WINDOWS]). Kept in an [ArenaStats.Grid]
     * so the heatmap can draw it: `avgCents` holds r (−1…1), `trades` the
     * shared windows, `netUsd` the pair's combined net. The diagonal is empty.
     */
    fun correlation(trades: List<ScalpTrade>): ArenaStats.Grid {
        val byName = ScalpStrategy.values().associateBy { it.name }
        val net = HashMap<ScalpStrategy, HashMap<String, Double>>()
        for (t in trades) {
            val st = byName[t.strategy] ?: continue
            val m = net.getOrPut(st) { HashMap() }
            m[t.ticker] = (m[t.ticker] ?: 0.0) + t.pnlUsd
        }
        val rows = ScalpStrategy.ACTIVE.filter { (net[it]?.size ?: 0) >= MIN_SHARED_WINDOWS }
        val cells = rows.map { a ->
            rows.map { b ->
                if (a == b) return@map null
                val ma = net.getValue(a)
                val mb = net.getValue(b)
                val shared = ma.keys.filter { it in mb }
                if (shared.size < MIN_SHARED_WINDOWS) return@map null
                val r = pearson(shared.map { ma.getValue(it) }, shared.map { mb.getValue(it) }) ?: return@map null
                ArenaStats.Cell(shared.size, shared.sumOf { ma.getValue(it) + mb.getValue(it) }, r)
            }
        }
        return ArenaStats.Grid(rows, rows.map { it.label }, cells)
    }

    /** Pearson's r, or null when either side never varies. */
    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 2) return null
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx < 1e-18 || syy < 1e-18) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }
}
