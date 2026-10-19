package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpStat
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ScalpTuneState
import java.util.Locale

/** 0.3.68: Decision-view Scalp section built only from aggregate tables (no full-history scan). */
object ScalpAggUi {
    fun build(book: ScalpBook, open: List<ScalpTrade>, marks: Map<String, ScalpRule.Quote>, tune: ScalpTuneState, st: List<ScalpStat>): ScalpUi {
        val n = st.sumOf { it.n }
        val wins = st.sumOf { it.wins }
        val net = st.sumOf { it.netUsd }
        val sumSq = st.sumOf { it.sumSq }
        val cost = st.sumOf { it.costSum }
        val all = ScalpStat("all", n = n, wins = wins, netUsd = net, sumSq = sumSq, costSum = cost,
            sumWin = st.sumOf { it.sumWin }, sumLoss = st.sumOf { it.sumLoss }, nLoss = st.sumOf { it.nLoss }, noFills = st.sumOf { it.noFills })
        val ci = all.perTradeCi()
        val stats = if (n == 0 && open.isEmpty() && all.noFills == 0 && kotlin.math.abs(net) < 1e-9) {
            listOf(com.dirk.kalshiodds.signal.feedback.ScorecardPickGuard.NO_DATA_YET)
        } else listOf(
            String.format(Locale.US, "%d round trips · %d open · %d no-fills", n, open.size, all.noFills),
            String.format(Locale.US, "Net after both fees: %+.2f USD", net),
            "Win rate: " + (all.winRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—"),
            "Avg win / loss: " + (all.avgWin?.let { String.format(Locale.US, "%+.2f", it) } ?: "—") + " / " + (all.avgLoss?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"),
            "ROI on cost: " + (all.roi?.let { String.format(Locale.US, "%+.1f%%", it * 100) } ?: "—"),
            "95% CI $/trade (normal): " + (ci?.let { String.format(Locale.US, "[%+.3f, %+.3f]", it.first, it.second) } ?: "—")
        )
        val breakdown = ScalpModels.leaderboardFromStats(st, exits = book.loadExits()).filter { it.roundTrips > 0 }.take(12).map { r ->
            String.format(Locale.US, "%s · %d trips · %+.2f USD · win %s%s", r.model.label, r.roundTrips, r.netUsd,
                r.winRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—", if (r.exitReasons.isNotBlank()) " · " + r.exitReasons else "")
        }
        val windows = ScalpModels.windowsFromAgg(book.loadWindows(12), 12).map { w ->
            val tot = w.netByModel.values.sum()
            ScalpCopy.Line("w-" + w.windowKey, String.format(Locale.US, "Window %s · %d trips · %+.2f USD · best %s", w.windowKey, w.tripsByModel.values.sum(), tot, w.best?.label ?: "—"), tot > 0)
        }
        return ScalpUi(
            open = ScalpCopy.openLines(open, marks),
            history = windows,
            stats = stats,
            ladder = "",
            tuning = ScalpCopy.tuningLines(tune),
            breakdown = breakdown
        )
    }
}
