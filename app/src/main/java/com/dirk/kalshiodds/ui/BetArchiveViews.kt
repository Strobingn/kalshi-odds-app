package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.archive.BetArchiveStore
import com.dirk.kalshiodds.signal.archive.BetArchiveSummary
import com.dirk.kalshiodds.signal.archive.BetRow
import com.dirk.kalshiodds.signal.latefav.LateFavoriteEntry
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** The Home card for the permanent bet archive, read from the phone every 15 s. Read-only. */
@Composable
fun BetArchiveSection() {
    val context = LocalContext.current.applicationContext
    val rows by produceState(emptyList<BetRow>(), context) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { BetArchiveStore.of(context).all() }.getOrDefault(value) }
            delay(15_000L)
        }
    }
    BetArchiveCard(rows)
}

/**
 * Every paper bet the phone's own rules took, kept for good: totals, the
 * running P&L, P&L and bets by day, and one bar per rule. Paper only.
 */
@Composable
fun BetArchiveCard(rows: List<BetRow>) {
    ChartCard(
        title = "Your bets · every one kept",
        subtitle = "Every paper bet the phone's own rules took (clear lead and the other trackers, and the paper " +
            "book), with its result. Kept in its own file on this phone: trimming or resetting a tracker never " +
            "deletes them. Export results includes them. Profit is after the taker fee.",
        tag = "BET_ARCHIVE"
    ) {
        if (rows.isEmpty()) {
            Muted("No bets yet. Each one the trackers take shows up here and stays.")
            return@ChartCard
        }
        val settled = rows.filter { it.pnlUsd != null && it.result != "void" }
        val net = settled.sumOf { it.pnlUsd!! }
        val ac = ArenaColors.current()
        KpiGrid(
            listOf(
                Kpi("Bets kept", String.format(Locale.US, "%,d", rows.size)),
                Kpi("Settled", String.format(Locale.US, "%,d", settled.size)),
                Kpi(
                    "Won",
                    if (settled.isEmpty()) "–" else String.format(Locale.US, "%.0f%%", settled.count { it.won == true } * 100.0 / settled.size)
                ),
                Kpi("Net after fees", if (settled.isEmpty()) "–" else ScalperResults.money(net), if (settled.isEmpty()) null else if (net >= 0) ac.gain else ac.loss),
                Kpi("A bet", if (settled.isEmpty()) "–" else ScalperResults.money(net / settled.size)),
                Kpi("Open", String.format(Locale.US, "%,d", rows.size - settled.size - rows.count { it.result == "void" }))
            )
        )
        val curve = BetArchiveSummary.cumulative(rows).map { CurvePoint(it.first, it.second) }
        if (curve.size >= 2) {
            Muted("Running P&L, every rule together")
            ScrubLineChart(curve, baseline = 0.0, height = 150.dp) { ScalperResults.money(it) }
        }
        val daily = BetArchiveSummary.dailyPnl(rows)
        if (daily.isNotEmpty()) {
            Muted("P&L by day (UTC)")
            DivergingColumns(
                values = daily.map { it.second },
                tag = "BET_ARCHIVE_DAILY",
                height = 110,
                negativeAt = { daily[it].second < 0 },
                readout = { i -> ScalperResults.money(daily[i].second) to daily[i].first }
            )
        }
        val perDay = BetArchiveSummary.perDay(rows)
        if (perDay.isNotEmpty()) {
            Muted("Bets placed by day (UTC)")
            DivergingColumns(
                values = perDay.map { it.second.toDouble() },
                tag = "BET_ARCHIVE_COUNT",
                height = 80,
                negativeAt = { false },
                readout = { i -> "${perDay[i].second} bets" to perDay[i].first },
                allPositive = true
            )
        }
        SourceBars(BetArchiveSummary.bySource(rows))
    }
}

/** One row per rule: its bets, win rate and net, with a bar around $0. */
@Composable
private fun SourceBars(sources: List<BetArchiveSummary.Source>) {
    val colors = DipTheme.colors
    val ac = ArenaColors.current()
    val maxAbs = sources.maxOfOrNull { abs(it.pnlUsd) }?.takeIf { it > 1e-9 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("BET_ARCHIVE_SOURCES")) {
        Muted("By rule")
        sources.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.bets} bets · " + (s.winRate?.let { String.format(Locale.US, "%.0f%% won", it * 100) } ?: "none settled"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }
                Canvas(Modifier.width(100.dp).height(14.dp)) {
                    val mid = size.width / 2f
                    drawLine(ac.baseline, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
                    if (s.settled > 0) {
                        val len = (abs(s.pnlUsd) / maxAbs).toFloat() * (mid - 1.dp.toPx())
                        if (s.pnlUsd >= 0) {
                            bar(mid + 1.dp.toPx(), 0f, mid + 1.dp.toPx() + len, size.height, ac.gain, BarEnd.RIGHT)
                        } else {
                            bar(mid - 1.dp.toPx() - len, 0f, mid - 1.dp.toPx(), size.height, ac.loss, BarEnd.LEFT)
                        }
                    }
                }
                Text(
                    if (s.settled == 0) "–" else ScalperResults.money(s.pnlUsd),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.width(62.dp),
                    maxLines = 1
                )
            }
        }
    }
}

/** A tracker card's running P&L line (nothing until two of its bets have settled). */
@Composable
fun TrackerCurve(entries: List<LateFavoriteEntry>) {
    val curve = BetArchiveSummary.trackerCurve(entries).map { CurvePoint(it.first, it.second) }
    if (curve.size < 2) return
    ScrubLineChart(curve, baseline = 0.0, height = 100.dp) { ScalperResults.money(it) }
}
