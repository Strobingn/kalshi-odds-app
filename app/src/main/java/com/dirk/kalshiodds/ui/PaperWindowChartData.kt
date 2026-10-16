package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill

/** Adapts recorded ledger events; legacy sells without a timestamp never get an invented marker. */
object PaperWindowChartData {
    fun fills(paper: PaperBookState, ticker: String): List<PaperFill> =
        (paper.fills + paper.archived.flatMap { it.fills }).filter { it.ticker.equals(ticker, true) }

    fun build(ticker: String, closeMs: Long, nowMs: Long, points: List<BidPoint>,
        paper: PaperBookState, quoteAtMs: Long? = null): ExecutionWindowData {
        val rows = fills(paper, ticker)
        val markers = buildList {
            // A partial sale creates a sold slice plus a remaining slice of one original buy.
            for ((position, slices) in rows.groupBy { it.positionId ?: it.id }) {
                val first = slices.first()
                add(ExecutionWindowData.Execution("$position-buy", position, first.side, first.createdAtMs,
                    first.limitPrice, slices.sumOf { it.contracts }, ExecutionWindowData.Kind.BUY,
                    feeUsd = slices.sumOf { it.entryFeeUsd }))
                slices.forEachIndexed { index, fill ->
                    val at = fill.exitAtMs
                    val price = fill.exitPrice
                    if (fill.settled && at != null && price != null) {
                        val kind = if (fill.outcome.equals("sell", true)) ExecutionWindowData.Kind.SELL else ExecutionWindowData.Kind.SETTLEMENT
                        add(ExecutionWindowData.Execution("${fill.id}-$index-exit", position, fill.side,
                            if (kind == ExecutionWindowData.Kind.SETTLEMENT) minOf(at, closeMs) else at,
                            price, fill.contracts, kind, recordedAtMs = at, feeUsd = fill.exitFeeUsd))
                    }
                }
            }
        }
        return ExecutionWindowData(ticker, closeMs, nowMs, quoteAtMs, points, markers)
    }
}
