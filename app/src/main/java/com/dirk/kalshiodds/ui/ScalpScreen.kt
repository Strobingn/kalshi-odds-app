package com.dirk.kalshiodds.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStats
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Text for the Scalp (PAPER) screen. Pure. */
object ScalpCopy {
    data class Line(val key: String, val text: String, val positive: Boolean?)

    const val TITLE = "Scalp (PAPER)"
    const val BANNER = "PAPER ONLY — no Kalshi orders. Shadow and live scalping are not in this release."

    fun openLines(trades: List<ScalpTrade>, marks: Map<String, ScalpRule.Quote>): List<Line> =
        trades.filter { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY }
            .map { t ->
                val q = marks[t.ticker.uppercase()]
                val bid = q?.bid(t.side)
                val fair = q?.fair(t.side)
                val u = t.unrealizedUsd(bid)
                val text = when (t.state) {
                    ScalpState.PENDING_ENTRY -> String.format(Locale.US, "%s %s · signal @ %.0f¢ (fair %.0f¢) · waiting for next fresh book", t.ticker, t.side, t.signalAsk * 100, t.fairAtSignal * 100)
                    else -> String.format(
                        Locale.US,
                        "%s %s ×%d · entry %.0f¢ · bid %s · fair %s · unrealized %s after exit fee%s",
                        t.ticker, t.side, t.remaining, (t.entryPrice ?: 0.0) * 100,
                        bid?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—",
                        fair?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—",
                        u?.let { String.format(Locale.US, "%+.2f USD", it) } ?: "—",
                        if (t.state == ScalpState.PENDING_EXIT) " · exiting: ${t.exitReason}" else ""
                    )
                }
                Line(t.id, text, u?.let { it > 0.0 })
            }

    fun historyLines(trades: List<ScalpTrade>, limit: Int = 100): List<Line> =
        trades.filter { it.state == ScalpState.CLOSED || it.state == ScalpState.NO_FILL }.take(limit).map { t ->
            val text = if (t.state == ScalpState.NO_FILL) {
                "${t.ticker} ${t.side} · ${t.note}"
            } else {
                String.format(
                    Locale.US,
                    "%s %s ×%d · in %.0f¢ out avg %.1f¢ · fees $%.2f · net %+.2f USD · hold %ds · %s",
                    t.ticker, t.side, t.contracts, (t.entryPrice ?: 0.0) * 100,
                    if (t.contracts > 0) t.proceedsUsd / t.contracts * 100 else 0.0,
                    t.entryFeeUsd + t.exitFeeUsd, t.netUsd ?: 0.0, ((t.holdMs ?: 0L) / 1000L).toInt(), t.exitReason ?: ""
                )
            }
            Line(t.id, text, t.netUsd?.let { it > 0.0 })
        }

    fun statsLines(s: ScalpStats.Summary): List<String> = listOf(
        String.format(Locale.US, "%d round trips · %d open · %d no-fills", s.roundTrips, s.open, s.noFills),
        String.format(Locale.US, "Net after both fees: %+.2f USD", s.netUsd),
        "Win rate: " + (s.winRate?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—"),
        "Avg net per contract: " + (s.avgNetPerContractCents?.let { String.format(Locale.US, "%+.2f¢", it) } ?: "—"),
        "Avg hold: " + (s.avgHoldS?.let { String.format(Locale.US, "%.0f s", it) } ?: "—"),
        "95% window-clustered CI (per contract): " +
            (s.ci?.let { String.format(Locale.US, "[%+.2f¢, %+.2f¢] over %d windows", it.lo * 100, it.hi * 100, it.clusters) } ?: "—")
    )
}

@Composable
fun ScalpScreen(viewModel: DecisionViewModel, onBack: () -> Unit) {
    val colors = DipTheme.colors
    val ui by viewModel.scalp.collectAsState()
    DecisionScaffold(ScalpCopy.TITLE, onBack) {
        item { Text(ScalpCopy.BANNER, fontWeight = FontWeight.Bold, color = colors.accentOrange) }
        item { Text(ScalpRule.BACKTEST_LABEL, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary) }
        item { Text(ui.rules, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        item {
            DecisionCard {
                Text("Stats", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                ui.stats.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary) }
                Text("Ladder: ${ui.ladder}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        item { Text("Open scalps (${ui.open.size})", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) }
        items(ui.open, key = { "o" + it.key }) { line ->
            Text(line.text, style = MaterialTheme.typography.labelMedium, color = when (line.positive) { true -> colors.up; false -> colors.down; null -> colors.textPrimary })
        }
        item { Text("History (latest ${ui.history.size})", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) }
        items(ui.history, key = { "h" + it.key }) { line ->
            Text(line.text, style = MaterialTheme.typography.labelMedium, color = when (line.positive) { true -> colors.up; false -> colors.down; null -> colors.textSecondary })
        }
    }
}

/** 0.3.39: Scalp is the primary paper Autopilot strategy; Home shows a compact card for it. */
object HomeScalpCopy {
    const val TITLE = "Autopilot: Scalp (PAPER, always on)"

    fun lines(trades: List<ScalpTrade>, paperOn: Boolean): List<String> {
        val s = ScalpStats.summary(trades)
        val status = if (paperOn) {
            "Running whenever paper trading is on — no Kalshi orders."
        } else {
            "Paused: paper trading is off in Settings."
        }
        return listOf(
            status,
            String.format(
                java.util.Locale.US,
                "%d open · %d round trips · net %+.2f USD after both fees",
                s.open, s.roundTrips, s.netUsd
            ),
            ScalpRule.BACKTEST_LABEL
        )
    }
}

@androidx.compose.runtime.Composable
fun HomeScalpCard(lines: List<String>, onOpen: () -> Unit) {
    val colors = com.dirk.kalshiodds.ui.theme.DipTheme.colors
    com.dirk.kalshiodds.ui.components.FieldCard {
        androidx.compose.material3.Text(
            HomeScalpCopy.TITLE,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        lines.forEach { line ->
            androidx.compose.material3.Text(
                line,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        androidx.compose.material3.TextButton(onClick = onOpen) {
            androidx.compose.material3.Text("Open Scalp")
        }
    }
}
