package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.time.Instant
import java.time.ZoneId

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
