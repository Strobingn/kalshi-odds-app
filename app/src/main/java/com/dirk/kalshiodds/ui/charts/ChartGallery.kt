package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
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
