package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Mirrors Bitcoin Swarm's summary, bankroll plot, open-position and strategy rows on Kashi's indexed ledger. */
@Composable
internal fun SwarmScalpSummary(rows: List<ScalpModels.Row>, today: List<ScalpModels.Row>) {
    val c = DipTheme.colors
    val count = rows.sumOf { it.roundTrips }
    val wins = rows.sumOf { it.wins }
    SwarmCard("Net after fees · all time") {
        Text(ScalpTabCopy.money(rows.sumOf { it.netUsd }), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Text("${ScalpTabCopy.money(rows.sumOf { it.grossWinUsd })} won · ${ScalpTabCopy.money(rows.sumOf { it.grossLossUsd })} lost · ${ScalpTabCopy.money(rows.sumOf { it.feesUsd })} fees", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SwarmMetric("W / L", "$wins / ${rows.sumOf { it.losses }}")
            SwarmMetric("Trades", count.toString())
            SwarmMetric("Win rate", if (count > 0) String.format(Locale.US, "%.1f%%", wins * 100.0 / count) else "—")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SwarmMetric("Today · ET", ScalpTabCopy.money(today.sumOf { it.netUsd }))
            SwarmMetric("Open P&L", ScalpTabCopy.money(rows.sumOf { it.unrealizedUsd }))
            SwarmMetric("Open", rows.sumOf { it.open }.toString())
        }
        Text("Separate paper experiments summed together; this is not one shared bankroll. Flat trades are included in trade count.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}
@Composable private fun SwarmMetric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall, color = DipTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = DipTheme.colors.textPrimary) }
}
@Composable
internal fun SwarmScalpCurve(windows: List<ScalpModels.WindowRow>, v2: Boolean, nowMs: Long = System.currentTimeMillis()) {
    val c = DipTheme.colors
    val points = windows.filter { it.closeMs != null && it.closeMs <= nowMs }.sortedBy { it.closeMs }.map { w ->
        w.closeMs!! to w.netByModel.filterKeys { ScalpModels.isV2(it) == v2 }.values.sum()
    }
    val curve = points.runningFold(0.0) { a, p -> a + p.second }
    SwarmCard("Realized P&L · last ${points.size} completed windows") {
        if (points.size < 2) Text("The curve appears after two recorded windows. No synthetic trades or interpolated results.", color = c.textSecondary)
        else {
            val low = minOf(0.0, curve.min()); val high = maxOf(0.0, curve.max()); val span = (high - low).coerceAtLeast(.01)
            val times = listOf(points.first().first - 900_000L) + points.map { it.first }
            val timeSpan = (times.last() - times.first()).coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ScalpTabCopy.money(curve.last()), color = c.textPrimary, fontWeight = FontWeight.Bold)
                Text("${ScalpTabCopy.money(low)} … ${ScalpTabCopy.money(high)}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val zero = (high / span).toFloat() * size.height
                drawLine(c.textSecondary.copy(alpha = .25f), Offset(0f, zero), Offset(size.width, zero))
                val path = Path()
                curve.forEachIndexed { i, value ->
                    val x = ((times[i] - times.first()).toDouble() / timeSpan * size.width).toFloat()
                    val y = ((high - value) / span * size.height).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.textPrimary, style = Stroke(3.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                fun label(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("America/New_York")).format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US))
                Text(label(times.first()), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                Text(label(times.last()) + " ET", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            Text("Starts at $0 for this range; excludes current-window and open P&L. Each point is the recorded result for that contract window.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable internal fun SwarmStrategyRow(row: ScalpModels.Row, feedback: com.dirk.kalshiodds.decision.ScalpTradeFeedback? = null, onOpen: () -> Unit) {
    val c = DipTheme.colors
    SwarmCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Column(Modifier.weight(1f)) {
                Text(row.model.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("${row.wins} W · ${row.losses} L · ${row.roundTrips} trades · ${row.open} open", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                feedback?.let { Text("Learned: ${it.trades} trades · recent net ${String.format(Locale.US, "%+.2f¢/ct", it.recentNetPerContract * 100)}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall) }
            }
            Text(ScalpTabCopy.money(row.netUsd), color = c.textPrimary, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable internal fun SwarmOpenPosition(t: ScalpTrade, bid: Double?) {
    val c = DipTheme.colors
    SwarmCard {
        Text(t.algoLabel, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("${if (t.side == "YES") "UP" else "DOWN"} · ${t.remaining} ct · ${t.ticker}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text("Entry ${ScalpTabCopy.cents(t.entryPrice)} → bid ${ScalpTabCopy.cents(bid)} · ${t.unrealizedUsd(bid)?.let { ScalpTabCopy.money(it) } ?: "—"} after exit fee", color = c.textPrimary)
        Text(if (t.state == ScalpState.PENDING_EXIT) "Exiting · ${t.exitReason}" else if (t.state == ScalpState.PENDING_ENTRY) "Entry pending" else "Protective stop active", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
