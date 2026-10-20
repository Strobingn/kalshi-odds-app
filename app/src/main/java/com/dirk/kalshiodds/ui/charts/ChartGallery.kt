package com.dirk.kalshiodds.ui.charts

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
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

    private fun entered(t: ScalpTrade): Boolean =
        t.state != ScalpState.NO_FILL && t.entryPrice != null && t.entryPrice.isFinite() &&
            t.entryPrice in 0.0..1.0 && t.contracts > 0

    internal fun isUp(side: String): Boolean =
        side.equals("YES", true) || side.equals("UP", true)

    internal val ET: ZoneId get() = ChartShapes.ET
}
