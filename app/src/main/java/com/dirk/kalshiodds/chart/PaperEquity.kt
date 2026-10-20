package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.signal.paper.PaperFill

/**
 * Equity curve for the current paper book. Uses only settled fills that
 * already have a finite realized P&L. Open positions and missing P&L
 * stay off the line so the last point matches the sum of those P&Ls.
 */
object PaperEquity {

    data class Point(
        val tMs: Long,
        val equityUsd: Double,
        /** Running realized P&L since the starting bankroll. */
        val pnlUsd: Double
    )

    fun curve(fills: List<PaperFill>, startingUsd: Double): List<Point> {
        if (!startingUsd.isFinite()) return emptyList()
        val settled = ArrayList<Pair<Long, Double>>(fills.size)
        for (fill in fills) {
            if (!fill.settled) continue
            val pnl = fill.pnlUsd?.takeIf { it.isFinite() } ?: continue
            settled.add(fill.createdAtMs to pnl)
        }
        if (settled.isEmpty()) return emptyList()
        settled.sortBy { it.first }
        val out = ArrayList<Point>(settled.size + 1)
        out.add(Point(settled.first().first - 1L, startingUsd, 0.0))
        var equity = startingUsd
        var pnlRun = 0.0
        for ((tMs, pnl) in settled) {
            equity += pnl
            pnlRun += pnl
            out.add(Point(tMs, equity, pnlRun))
        }
        return out
    }
}
