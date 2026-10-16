package com.dirk.kalshiodds.signal.scalper

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/**
 * The Arena's "Execution" numbers: win often or win big (the payoff map),
 * pay the ask or wait in line (each signal's buy-now and resting twins),
 * how often a resting bid fills, how fast it fills and from where in the
 * queue, whether the ML scalper's forecast held up, and the daily rank race.
 * Pure and unit tested (`ArenaExecTest`). Paper dollars after fees.
 */
object ArenaExec {

    private val byName: Map<String, ScalpStrategy> = ScalpStrategy.values().associateBy { it.name }

    // ------------------------------------------------------------ payoff map

    /**
     * One strategy on the payoff map. [hitRate] counts wins against losses
     * (a trade that nets exactly $0 is neither). It made money exactly when
     * [payoff] (average win ÷ average loss, per contract) beats
     * [breakEvenPayoff] = losses ÷ wins.
     */
    data class PayoffDot(
        val strategy: ScalpStrategy,
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val avgWinCents: Double,
        val avgLossCents: Double,
        val netUsd: Double
    ) {
        val hitRate: Double get() = wins.toDouble() / (wins + losses)
        val payoff: Double get() = avgWinCents / avgLossCents
        val breakEvenPayoff: Double get() = losses.toDouble() / wins
        val ahead: Boolean get() = wins * avgWinCents > losses * avgLossCents
    }

    const val MIN_PAYOFF_TRADES = 10

    /** The win rate a payoff of [payoff] needs to break even: 1 ÷ (1 + payoff). */
    fun breakEvenHitRate(payoff: Double): Double = 1.0 / (1.0 + payoff)

    /** Strategies with [MIN_PAYOFF_TRADES] trades and at least one win and one loss, in enum order. */
    fun payoffMap(trades: List<ScalpTrade>): List<PayoffDot> =
        trades.groupBy { it.strategy }.mapNotNull { (name, rows) ->
            val st = byName[name] ?: return@mapNotNull null
            if (rows.size < MIN_PAYOFF_TRADES) return@mapNotNull null
            val c = rows.map { ArenaCharts.centsPerContract(it) }
            val won = c.filter { it > 0 }
            val lost = c.filter { it < 0 }
            if (won.isEmpty() || lost.isEmpty()) return@mapNotNull null
            PayoffDot(st, rows.size, won.size, lost.size, won.average(), -lost.average(), rows.sumOf { it.pnlUsd })
        }.sortedBy { it.strategy.ordinal }

    // ------------------------------------------------------------ buy now or rest

    /** One half of a twin pair: trades, average result per contract and its standard error. */
    data class Side(val strategy: ScalpStrategy, val trades: Int, val avgCents: Double, val stdErrCents: Double, val netUsd: Double)

    /** The same signal entered two ways: pay the ask now, or rest a bid and wait. */
    data class Twin(val signal: String, val taker: Side?, val maker: Side?, val makerFillRate: Double?)

    val TWINS: List<Triple<String, ScalpStrategy, ScalpStrategy>> = listOf(
        Triple("Dip", ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST),
        Triple("Momentum", ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST),
        Triple("Extreme reversion", ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST)
    )

    private fun side(st: ScalpStrategy, trades: List<ScalpTrade>): Side? {
        val rows = trades.filter { it.strategy == st.name }
        if (rows.isEmpty()) return null
        val c = rows.map { ArenaCharts.centsPerContract(it) }
        val mean = c.average()
        val se = if (c.size < 2) 0.0 else sqrt(c.sumOf { (it - mean) * (it - mean) } / (c.size - 1) / c.size)
        return Side(st, rows.size, mean, se, rows.sumOf { it.pnlUsd })
    }

    /** Every twin pair, with whichever halves have traded; pairs where neither has are dropped. */
    fun twins(trades: List<ScalpTrade>, state: ScalperState): List<Twin> =
        TWINS.mapNotNull { (signal, taker, maker) ->
            val t = side(taker, trades)
            val m = side(maker, trades)
            if (t == null && m == null) null else Twin(signal, t, m, fillRate(state, maker))
        }

    // ------------------------------------------------------------ fill rate

    /** Share of [st]'s posted orders that filled; null before its first post. */
    fun fillRate(state: ScalperState, st: ScalpStrategy): Double? =
        state.stats(st.name).takeIf { it.posted > 0 }?.let { (it.filled.toDouble() / it.posted).coerceIn(0.0, 1.0) }

    data class FillRate(val strategy: ScalpStrategy, val posted: Int, val filled: Int) {
        val rate: Double get() = if (posted == 0) 0.0 else (filled.toDouble() / posted).coerceIn(0.0, 1.0)
    }

    /** The resting strategies that have posted, in enum order. Buy-now orders always fill, so they are left out. */
    fun fillRates(state: ScalperState): List<FillRate> =
        ScalpStrategy.values().filter { !it.taker }.mapNotNull { st ->
            val s = state.stats(st.name)
            if (s.posted == 0) null else FillRate(st, s.posted, s.filled)
        }

    // ------------------------------------------------------------ fill speed, queue

    val WAIT_LABELS: List<String> = listOf("<1s", "1–5s", "5–20s", "20–60s", "1m+")
    private val WAIT_EDGES = listOf(1_000L, 5_000L, 20_000L, 60_000L)

    /** Which [WAIT_LABELS] column a resting order's post-to-fill wait falls in. */
    fun waitColumn(t: ScalpTrade): Int {
        val w = (t.filledAtMs - t.postedAtMs).coerceAtLeast(0L)
        return WAIT_EDGES.indexOfFirst { w < it }.let { if (it < 0) WAIT_EDGES.size else it }
    }

    /** Result per contract by how long the resting bid waited to fill. Buy-now strategies are left out. */
    fun fillSpeed(trades: List<ScalpTrade>): ArenaStats.Grid =
        ArenaStats.grid(trades.filter { byName[it.strategy]?.taker == false }, WAIT_LABELS) { waitColumn(it) }

    val QUEUE_LABELS: List<String> = listOf("front", "1–49", "50–199", "200–999", "1k+")
    private val QUEUE_EDGES = listOf(1.0, 50.0, 200.0, 1_000.0)

    /** Which [QUEUE_LABELS] column the contracts ahead at posting fall in; null when the queue was not known. */
    fun queueColumn(t: ScalpTrade): Int? {
        if (!t.queueKnown || !t.queueAhead.isFinite()) return null
        val q = t.queueAhead.coerceAtLeast(0.0)
        return QUEUE_EDGES.indexOfFirst { q < it }.let { if (it < 0) QUEUE_EDGES.size else it }
    }

    /** Result per contract by the contracts ahead in the queue when the bid was posted. */
    fun queueDepth(trades: List<ScalpTrade>): ArenaStats.Grid =
        ArenaStats.grid(trades.filter { byName[it.strategy]?.taker == false }, QUEUE_LABELS) { queueColumn(it) }

    // ------------------------------------------------------------ model check

    /** ML scalper fills grouped by the forecast at posting: what it expected against what it made. */
    data class ModelBand(
        val label: String,
        val trades: Int,
        val expectedCents: Double,
        val realizedCents: Double,
        val stdErrCents: Double
    )

    val MODEL_LABELS: List<String> = listOf("<0¢", "0–0.5¢", "0.5–1¢", "1–2¢", "2¢+")
    private val MODEL_EDGES = listOf(0.0, 0.5, 1.0, 2.0)

    /** The ML scalper's filled trades by [ScalpTrade.expectedCents] band; empty bands are dropped. */
    fun modelCheck(trades: List<ScalpTrade>): List<ModelBand> {
        val acc = List(MODEL_LABELS.size) { ArrayList<ScalpTrade>() }
        for (t in trades) {
            if (t.strategy != ScalpStrategy.ML_REST.name || !t.expectedCents.isFinite()) continue
            val i = MODEL_EDGES.indexOfFirst { t.expectedCents < it }.let { if (it < 0) MODEL_EDGES.size else it }
            acc[i] += t
        }
        return acc.mapIndexedNotNull { i, rows ->
            if (rows.isEmpty()) return@mapIndexedNotNull null
            val c = rows.map { ArenaCharts.centsPerContract(it) }
            val mean = c.average()
            val se = if (c.size < 2) 0.0 else sqrt(c.sumOf { (it - mean) * (it - mean) } / (c.size - 1) / c.size)
            ModelBand(MODEL_LABELS[i], rows.size, rows.map { it.expectedCents }.average(), mean, se)
        }
    }

    // ------------------------------------------------------------ rank race

    /** One strategy's place at the end of each day (1 = best running net); null before its first close. */
    data class RankLine(val strategy: ScalpStrategy, val ranks: List<Int?>, val netUsd: List<Double?>)

    data class RankRace(val days: List<String>, val lines: List<RankLine>) {
        val isEmpty: Boolean get() = lines.isEmpty()
        val field: Int get() = lines.size
    }

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /**
     * Running net per strategy at the end of each of the last [days] days
     * (the phone's clock), ranked. Trades before the first day still count
     * toward the running net. Ties keep the enum order.
     */
    fun rankRace(
        trades: List<ScalpTrade>,
        today: LocalDate,
        days: Int = 14,
        zone: ZoneId = ZoneId.systemDefault(),
        /** The trades are only the newest slice of the file: its oldest day is partial, so it is left out. */
        partialOldestDay: Boolean = false
    ): RankRace {
        val first = today.minusDays((days - 1).toLong())
        val labels = (0 until days).map { DAY.format(first.plusDays(it.toLong())) }
        val endOf = (0 until days).map { first.plusDays(it + 1L).atStartOfDay(zone).toInstant().toEpochMilli() }
        var rows = trades.filter { byName[it.strategy] != null }.sortedBy { it.closedAtMs }
        if (partialOldestDay && rows.isNotEmpty()) {
            // Running nets then start from $0 on the first whole day, instead of part-way through one.
            val oldest = java.time.Instant.ofEpochMilli(rows.first().closedAtMs).atZone(zone).toLocalDate()
            val from = oldest.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            rows = rows.filter { it.closedAtMs >= from }
        }
        val run = LinkedHashMap<ScalpStrategy, Double>()
        val ranks = HashMap<ScalpStrategy, MutableList<Int?>>()
        val nets = HashMap<ScalpStrategy, MutableList<Double?>>()
        var k = 0
        for (d in 0 until days) {
            while (k < rows.size && rows[k].closedAtMs < endOf[d]) {
                val st = byName.getValue(rows[k].strategy)
                run[st] = (run[st] ?: 0.0) + rows[k].pnlUsd
                k++
            }
            val order = run.keys.sortedWith(compareByDescending<ScalpStrategy> { run.getValue(it) }.thenBy { it.ordinal })
            val place = order.withIndex().associate { (i, st) -> st to i + 1 }
            for (st in ScalpStrategy.values()) {
                ranks.getOrPut(st) { ArrayList() } += place[st]
                nets.getOrPut(st) { ArrayList() } += run[st]
            }
        }
        val lines = ScalpStrategy.values()
            .filter { st -> ranks.getValue(st).any { it != null } }
            .map { RankLine(it, ranks.getValue(it), nets.getValue(it)) }
        return RankRace(labels, lines)
    }
}
