package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure inputs for the shared Analysis, Scorecard, and strategy charts.
 * Empty output means the canvas should show [CHART_EMPTY_TEXT]. Nothing here is invented.
 */
object ChartGallery {
    data class SideGroup(
        val label: String,
        val upEntries: Int,
        val downEntries: Int,
        val upNet: Double,
        val downNet: Double
    )

    /** Filled entries grouped by strategy. UP and DOWN stay on the same row. */
    fun sideGroups(
        trades: List<ScalpTrade>,
        labelOf: (ScalpTrade) -> String = { it.algoLabel }
    ): List<SideGroup> {
        val entered = trades.filter { entered(it) }
        if (entered.isEmpty()) return emptyList()
        return entered.groupBy { labelOf(it).ifBlank { "Strategy" } }.map { (label, rows) ->
            var upEntries = 0
            var downEntries = 0
            var upNet = 0.0
            var downNet = 0.0
            for (t in rows) {
                val net = t.netUsd?.takeIf { t.state == ScalpState.CLOSED && it.isFinite() }
                if (isUp(t.side)) {
                    upEntries++
                    if (net != null) upNet += net
                } else {
                    downEntries++
                    if (net != null) downNet += net
                }
            }
            SideGroup(label, upEntries, downEntries, upNet, downNet)
        }.sortedByDescending { it.upNet + it.downNet }
    }

    data class Correlation(val labels: List<String>, val matrix: List<List<Double?>>)

    /**
     * Pearson correlation of ET-day nets. Needs two days and two strategies that actually moved.
     * At most [maxStrategies], the ones with the largest absolute daily movement.
     */
    fun dailyCorrelation(
        windows: List<ScalpModels.WindowRow>,
        nowMs: Long,
        models: List<ScalpModels.Model>,
        zone: ZoneId = ChartShapes.ET,
        maxStrategies: Int = 12
    ): Correlation? {
        if (models.isEmpty() || maxStrategies < 2) return null
        val wanted = models.toSet()
        val byDay = windows.filter { w ->
            val close = w.closeMs
            close != null && close <= nowMs && w.netByModel.keys.any { it in wanted }
        }.groupBy { Instant.ofEpochMilli(it.closeMs!!).atZone(zone).toLocalDate() }.toSortedMap()
        if (byDay.size < 2) return null
        val series = models.map { model ->
            model to byDay.values.map { rows ->
                rows.sumOf { w -> w.netByModel[model]?.takeIf { it.isFinite() } ?: 0.0 }
            }
        }.filter { (_, values) -> values.any { abs(it) > 1e-9 } }
        if (series.size < 2) return null
        val ranked = series.sortedByDescending { (_, values) -> values.sumOf { abs(it) } }.take(maxStrategies)
        return Correlation(
            ranked.map { it.first.label },
            ranked.map { (_, a) -> ranked.map { (_, b) -> pearson(a, b) } }
        )
    }

    fun pearson(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 2) return null
        val meanA = a.average()
        val meanB = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val xa = a[i] - meanA
            val xb = b[i] - meanB
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        if (da < 1e-12 || db < 1e-12) return null
        return (num / sqrt(da * db)).coerceIn(-1.0, 1.0)
    }

    data class SignedBin(val label: String, val count: Int, val sign: Double)
    data class ScatterDot(val entryCents: Double, val exitCents: Double, val win: Boolean)

    private val PNL_UPPERS = doubleArrayOf(-2.0, -1.0, -0.25, 0.25, 1.0, 2.0)
    private val PNL_LABELS = listOf("≤ −$2", "−$2 to −$1", "−$1 to −25¢", "±25¢", "25¢ to $1", "$1 to $2", "≥ $2")
    private val PNL_SIGNS = doubleArrayOf(-1.0, -1.0, -1.0, 0.0, 1.0, 1.0, 1.0)

    /** Trailing win rate, in percent, once [window] closed trades exist. A win is net above zero. */
    fun rollingWinRate(trades: List<ScalpTrade>, window: Int = 30): List<ChartShapes.Sample> {
        val n = window.coerceAtLeast(1)
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
            .sortedBy { it.closedAtMs ?: it.entryAtMs ?: it.signalAtMs }
        if (closed.size < n) return emptyList()
        var wins = 0
        val out = ArrayList<ChartShapes.Sample>(closed.size - n + 1)
        for (i in closed.indices) {
            if ((closed[i].netUsd ?: 0.0) > 0.0) wins++
            if (i >= n && (closed[i - n].netUsd ?: 0.0) > 0.0) wins--
            if (i + 1 >= n) {
                val t = closed[i].closedAtMs ?: closed[i].entryAtMs ?: closed[i].signalAtMs
                out += ChartShapes.Sample(t, wins * 100.0 / n)
            }
        }
        return out
    }

    fun pnlBins(trades: List<ScalpTrade>): List<SignedBin> {
        val nets = trades.mapNotNull { t -> t.netUsd?.takeIf { t.state == ScalpState.CLOSED && it.isFinite() } }
        if (nets.isEmpty()) return emptyList()
        val counts = IntArray(PNL_LABELS.size)
        for (v in nets) {
            var i = PNL_UPPERS.size
            for (e in PNL_UPPERS.indices) if (v < PNL_UPPERS[e]) { i = e; break }
            counts[i]++
        }
        return PNL_LABELS.mapIndexed { i, label -> SignedBin(label, counts[i], PNL_SIGNS[i]) }
    }

    /** Entry cents versus exit cents. Exit is proceeds divided by contracts sold. */
    fun entryExit(trades: List<ScalpTrade>): List<ScatterDot> = trades.mapNotNull { t ->
        if (t.state != ScalpState.CLOSED) return@mapNotNull null
        val entry = t.entryPrice?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return@mapNotNull null
        val sold = t.soldContracts
        if (sold <= 0 || !t.proceedsUsd.isFinite()) return@mapNotNull null
        val exit = t.proceedsUsd / sold
        if (!exit.isFinite() || exit !in 0.0..1.0) return@mapNotNull null
        ScatterDot(entry * 100.0, exit * 100.0, (t.netUsd ?: 0.0) > 0.0)
    }

    data class KnobTick(val atMs: Long, val who: String, val knob: String, val from: String, val to: String)

    /** Recorded tuner changes, oldest first. Identical values and blank knobs are dropped. */
    fun knobTicks(rows: List<KnobTick>): List<KnobTick> =
        rows.filter { it.atMs > 0L && it.knob.isNotBlank() && it.from != it.to && it.who.isNotBlank() }
            .sortedBy { it.atMs }

    data class FeeLeg(val scope: String, val gross: Double, val fees: Double, val net: Double)

    /** Gross, fee cost, and net. A flat zero leg is omitted. Fees are stored as a positive cost. */
    fun feeLeg(scope: String, gross: Double, fees: Double, net: Double): FeeLeg? {
        if (!gross.isFinite() || !fees.isFinite() || !net.isFinite()) return null
        if (abs(gross) < 1e-9 && abs(fees) < 1e-9 && abs(net) < 1e-9) return null
        return FeeLeg(scope, gross, abs(fees), net)
    }

    fun feeLegFromTrades(scope: String, trades: List<ScalpTrade>): FeeLeg? {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
        if (closed.isEmpty()) return null
        val net = closed.sumOf { it.netUsd!! }
        val fees = closed.sumOf {
            val cost = it.entryFeeUsd + it.exitFeeUsd
            if (cost.isFinite()) cost else 0.0
        }
        return feeLeg(scope, net + fees, fees, net)
    }

    fun dayLeg(rows: Collection<ScalpModels.Row>): FeeLeg? {
        if (rows.sumOf { it.roundTrips } <= 0) return null
        val net = rows.sumOf { it.netUsd }
        val fees = rows.sumOf { it.feesUsd }
        return feeLeg("Day", net + fees, fees, net)
    }

    data class CalPoint(val label: String, val predicted: Double, val actual: Double, val n: Int)

    /** AI UP chance (0–100 or 0–1) versus whether UP settled. Ten-point buckets that have samples. */
    fun calibration(samples: List<Pair<Double, Boolean>>): List<CalPoint> {
        val clean = samples.mapNotNull { (raw, settledUp) ->
            ChartShapes.scalePct(raw)?.let { it to settledUp }
        }
        if (clean.isEmpty()) return emptyList()
        return (0 until 10).mapNotNull { i ->
            val lo = i * 10.0
            val hi = lo + 10.0
            val rows = clean.filter { (p, _) -> if (i == 9) p in lo..100.0 else p >= lo && p < hi }
            if (rows.isEmpty()) null else CalPoint(
                "${lo.toInt()}–${hi.toInt()}%",
                rows.map { it.first }.average(),
                rows.count { it.second } * 100.0 / rows.size,
                rows.size
            )
        }
    }

    data class DepthSeries(val name: String, val up: Boolean, val bid: Boolean, val cents: List<Double>, val size: List<Double>)

    /**
     * Cumulative depth. YES levels are UP bids. NO levels are DOWN bids.
     * The other side's bids are the asks: an UP ask sits at 100¢ minus the DOWN bid.
     */
    fun cumulativeDepth(yes: List<Pair<Double, Double>>, no: List<Pair<Double, Double>>): List<DepthSeries> {
        val yesC = depthLevels(yes)
        val noC = depthLevels(no)
        if (yesC.isEmpty() && noC.isEmpty()) return emptyList()
        return listOfNotNull(
            depthSeries("UP bid", up = true, bid = true, cumulativeBids(yesC)),
            depthSeries("UP ask", up = true, bid = false, cumulativeAsks(noC)),
            depthSeries("DOWN bid", up = false, bid = true, cumulativeBids(noC)),
            depthSeries("DOWN ask", up = false, bid = false, cumulativeAsks(yesC))
        )
    }

    private fun depthLevels(levels: List<Pair<Double, Double>>): Map<Int, Double> {
        val acc = HashMap<Int, Double>()
        for ((price, size) in levels) {
            if (!price.isFinite() || !size.isFinite() || size <= 0.0 || price !in 0.0..1.0) continue
            val cents = (price * 100.0).roundToInt().coerceIn(0, 100)
            acc[cents] = (acc[cents] ?: 0.0) + size
        }
        return acc
    }

    private fun cumulativeBids(levels: Map<Int, Double>): Pair<List<Double>, List<Double>> {
        var acc = 0.0
        val cents = ArrayList<Double>()
        val size = ArrayList<Double>()
        for ((cent, qty) in levels.entries.sortedByDescending { it.key }) {
            acc += qty
            cents += cent.toDouble()
            size += acc
        }
        return cents to size
    }

    private fun cumulativeAsks(otherBids: Map<Int, Double>): Pair<List<Double>, List<Double>> {
        val asks = HashMap<Int, Double>()
        for ((cent, qty) in otherBids) {
            val ask = (100 - cent).coerceIn(0, 100)
            asks[ask] = (asks[ask] ?: 0.0) + qty
        }
        var acc = 0.0
        val cents = ArrayList<Double>()
        val size = ArrayList<Double>()
        for ((cent, qty) in asks.entries.sortedBy { it.key }) {
            acc += qty
            cents += cent.toDouble()
            size += acc
        }
        return cents to size
    }

    private fun depthSeries(name: String, up: Boolean, bid: Boolean, curve: Pair<List<Double>, List<Double>>): DepthSeries? {
        if (curve.first.isEmpty()) return null
        return DepthSeries(name, up, bid, curve.first, curve.second)
    }

    data class FamilyEvent(val tMs: Long, val family: String, val pnl: Double)
    data class StackModel(val names: List<String>, val times: List<Long>, val values: List<List<Double>>)

    /** Cumulative P&L per family, sharing one time axis so the bands can stack. */
    fun familyStacks(events: List<FamilyEvent>): StackModel? {
        val clean = events.filter { it.family.isNotBlank() && it.pnl.isFinite() }.sortedBy { it.tMs }
        if (clean.isEmpty()) return null
        val names = clean.map { it.family }.distinct()
        val index = names.withIndex().associate { it.value to it.index }
        val running = DoubleArray(names.size)
        val times = ArrayList<Long>(clean.size + 1)
        val series = List(names.size) { ArrayList<Double>(clean.size + 1) }
        times += clean.first().tMs - 1
        series.forEach { it += 0.0 }
        for (event in clean) {
            running[index.getValue(event.family)] += event.pnl
            times += event.tMs
            running.forEachIndexed { i, v -> series[i] += v }
        }
        return StackModel(names, times, series)
    }

    fun scorecardFamily(source: String?, pickSource: String?, fallback: String): String {
        com.dirk.kalshiodds.signal.paper.PaperPickSource.of(source, pickSource)?.label?.let { return it }
        val raw = source?.trim().orEmpty()
        return raw.ifEmpty { fallback.ifBlank { "Other" } }
    }

    fun winLossCaption(wins: Int, losses: Int): String {
        fun word(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
        return "${word(wins, "win", "wins")} · ${word(losses, "loss", "losses")}"
    }

    fun corrText(r: Double): String {
        val body = String.format(Locale.US, "%.2f", abs(r))
        return when {
            r < -0.005 -> "−$body"
            r > 0.005 -> "+$body"
            else -> "0.00"
        }
    }

    data class SparkLine(val label: String, val values: List<Double>, val frozen: Boolean = false)

    /** One equity curve per strategy. A strategy with no recorded windows keeps an empty line. */
    fun equitySparks(
        windows: List<ScalpModels.WindowRow>,
        nowMs: Long,
        models: List<Pair<ScalpModels.Model, Boolean>>
    ): List<SparkLine> {
        if (models.isEmpty()) return emptyList()
        val lines = ChartShapes.equityFromWindows(windows, nowMs, models.map { it.first }).associateBy { it.name }
        return models.map { (model, frozen) ->
            SparkLine(
                model.label,
                lines[model.label]?.points?.map { it.value }?.filter { it.isFinite() } ?: emptyList(),
                frozen
            )
        }
    }

    data class LeaderRow(val label: String, val net: Double, val frozen: Boolean)

    /** Best net first. Frozen rows stay, including a flat $0. Non-finite nets are dropped. */
    fun leaderboard(rows: List<LeaderRow>): List<LeaderRow> =
        rows.filter { it.label.isNotBlank() && it.net.isFinite() }.sortedByDescending { it.net }

    data class HeatCell(val row: String, val column: String, val rate: Double?, val n: Int)
    data class HeatGrid(val rows: List<String>, val columns: List<String>, val cells: List<List<HeatCell>>)

    /** Closed-trade win rate. Rows are coins that traded; columns are all 24 Eastern hours. */
    fun winRateByHourCoin(trades: List<ScalpTrade>, zone: ZoneId = ChartShapes.ET): HeatGrid? {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
        if (closed.isEmpty()) return null
        val preferred = listOf("BTC", "ETH", "SOL")
        val coins = closed.map { coinOf(it) }.distinct().sortedWith(
            compareBy<String> { preferred.indexOf(it).let { i -> if (i < 0) preferred.size else i } }.thenBy { it }
        )
        val columns = (0 until 24).map { ChartShapes.hourLabel(it) }
        val cells = coins.map { coin ->
            (0 until 24).map { hour ->
                val rows = closed.filter { coinOf(it) == coin && hourOf(it, zone) == hour }
                val n = rows.size
                val rate = if (n == 0) null else rows.count { (it.netUsd ?: 0.0) > 0.0 } / n.toDouble()
                HeatCell(coin, columns[hour], rate, n)
            }
        }
        return HeatGrid(coins, columns, cells)
    }

    private fun coinOf(t: ScalpTrade): String = t.coin.ifBlank { "BTC" }

    private fun hourOf(t: ScalpTrade, zone: ZoneId): Int =
        Instant.ofEpochMilli(t.entryAtMs ?: t.closedAtMs ?: t.signalAtMs).atZone(zone).hour

    private fun entered(t: ScalpTrade): Boolean =
        t.state != ScalpState.NO_FILL && t.entryPrice != null && t.entryPrice.isFinite() &&
            t.entryPrice in 0.0..1.0 && t.contracts > 0

    internal fun isUp(side: String): Boolean =
        side.equals("YES", true) || side.equals("UP", true)

    internal val ET: ZoneId get() = ChartShapes.ET
}
