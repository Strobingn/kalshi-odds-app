package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.ScalpBreakdown
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.3.40 Scalp Data: scalping results only (separate from the main scorecard). Paper scalps; every dollar is
 * net after BOTH taker fees. A win is net > 0 after both fees; a loss is net < 0; exactly $0 is breakeven.
 */
object ScalpData {
    const val TITLE = "Scalp Data (PAPER)"
    private val ET: ZoneId = ZoneId.of("America/New_York")
    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.US)

    data class Summary(
        val roundTrips: Int,
        val open: Int,
        val netUsd: Double,
        val grossWinsUsd: Double,
        val grossLossesUsd: Double,
        val wins: Int,
        val losses: Int,
        val breakeven: Int,
        val avgWinUsd: Double?,
        val avgLossUsd: Double?,
        val biggestWinUsd: Double?,
        val biggestLossUsd: Double?,
        val netExTopWinUsd: Double,
        val feesUsd: Double
    )

    /**
     * 0.3.43 maker-first variants vs taker, per strategy. Maker fee is $0 on these series, so the adverse-selection
     * columns (side mid − entry at +30 s / +60 s) are what keep "fee-free" from becoming fake profit.
     */
    fun makerLines(all: List<ScalpTrade>): List<String> {
        val done = all.filter { it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED && it.netUsd != null }
        fun avg(xs: List<Double>) = if (xs.isEmpty()) "—" else String.format(Locale.US, "%+.1f¢", xs.average() * 100)
        return com.dirk.kalshiodds.decision.ScalpStrategy.values().flatMap { s ->
            listOf(true, false).map { mk ->
                val rows = done.filter { it.strategy == s && it.isMaker == mk }
                val fills = all.filter { it.strategy == s && it.isMaker == mk && it.entryAtMs != null }
                val noFill = all.count { it.strategy == s && it.isMaker == mk && it.state == com.dirk.kalshiodds.decision.ScalpState.NO_FILL }
                String.format(
                    Locale.US, "%s %s: %d trips · net %s/ct · AS30 %s · AS60 %s · no-fill %d",
                    s.label, if (mk) "maker" else "taker", rows.size,
                    avg(rows.map { it.netUsd!! / it.contracts.coerceAtLeast(1) }),
                    avg(fills.mapNotNull { it.adverse30 }), avg(fills.mapNotNull { it.adverse60 }), noFill
                )
            }
        }
    }

    fun closed(trades: List<ScalpTrade>): List<ScalpTrade> =
        trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null }.sortedBy { it.closedAtMs ?: it.signalAtMs }

    fun summary(trades: List<ScalpTrade>): Summary {
        val c = closed(trades)
        val nets = c.map { it.netUsd!! }
        val wins = nets.filter { it > 0.0 }
        val losses = nets.filter { it < 0.0 }
        val top = wins.maxOrNull()
        return Summary(
            roundTrips = c.size,
            open = trades.count { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY },
            netUsd = nets.sum(),
            grossWinsUsd = wins.sum(),
            grossLossesUsd = losses.sum(),
            wins = wins.size,
            losses = losses.size,
            breakeven = nets.size - wins.size - losses.size,
            avgWinUsd = wins.takeIf { it.isNotEmpty() }?.average(),
            avgLossUsd = losses.takeIf { it.isNotEmpty() }?.average(),
            biggestWinUsd = top,
            biggestLossUsd = losses.minOrNull(),
            netExTopWinUsd = nets.sum() - (top ?: 0.0),
            feesUsd = c.sumOf { it.entryFeeUsd + it.exitFeeUsd }
        )
    }

    /** Cumulative net after both fees, in close order (starts at 0). */
    fun equityCurve(trades: List<ScalpTrade>): List<Double> {
        var run = 0.0
        return listOf(0.0) + closed(trades).map { run += it.netUsd!!; run }
    }

    private fun usd(v: Double?) = v?.let { String.format(Locale.US, "%+,.2f USD", it) } ?: "—"

    fun summaryLines(s: Summary): List<String> = listOf(
        "Total net P&L after both fees: ${usd(s.netUsd)}",
        "Gross wins ${usd(s.grossWinsUsd)} vs gross losses ${usd(s.grossLossesUsd)}",
        String.format(Locale.US, "%d wins · %d losses · %d breakeven · %d round trips · %d open", s.wins, s.losses, s.breakeven, s.roundTrips, s.open),
        "Avg win ${usd(s.avgWinUsd)} · avg loss ${usd(s.avgLossUsd)}",
        "Biggest win ${usd(s.biggestWinUsd)} · biggest loss ${usd(s.biggestLossUsd)}",
        "Net excluding the top win: ${usd(s.netExTopWinUsd)}",
        String.format(Locale.US, "Fees paid (both legs): $%,.2f", s.feesUsd)
    )

    fun time(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ET).format(TIME) + " ET" } ?: "—"

    /** One row per round trip, newest first. */
    fun tripLines(trades: List<ScalpTrade>): List<ScalpCopy.Line> = closed(trades).asReversed().map { t ->
        val exitAvg = if (t.contracts > 0) t.proceedsUsd / t.contracts else 0.0
        ScalpCopy.Line(
            t.id,
            String.format(
                Locale.US,
                "%s · %s %s ×%d\nin %.0f¢ @ %s → out %.1f¢ @ %s · hold %ds\nfees $%.2f · net %+.2f USD · %s",
                t.strategy.label, t.ticker, t.side, t.contracts,
                (t.entryPrice ?: 0.0) * 100, time(t.entryAtMs), exitAvg * 100, time(t.closedAtMs),
                ((t.holdMs ?: 0L) / 1000L).toInt(), t.entryFeeUsd + t.exitFeeUsd, t.netUsd ?: 0.0, t.exitReason ?: ""
            ),
            t.netUsd?.let { if (it > 0.0) true else if (it < 0.0) false else null }
        )
    }
}

@Composable
fun ScalpDataScreen(viewModel: DecisionViewModel, onBack: () -> Unit, onOpenScalp: () -> Unit) {
    val colors = DipTheme.colors
    val trades by viewModel.scalpTrades.collectAsState()
    val paperOrders by viewModel.paperOrders.collectAsState()
    val scalpAll by viewModel.scalpAll.collectAsState()
    val s = ScalpData.summary(trades)
    val curve = ScalpData.equityCurve(trades)
    val rows = ScalpData.tripLines(trades)
    DecisionScaffold(ScalpData.TITLE, onBack) {
        item { Text("Scalping results only — paper, net after both Kalshi fees. Not part of the main scorecard.", color = colors.accentOrange, fontWeight = FontWeight.SemiBold) }
        item {
            DecisionCard {
                Text("Maker-first vs taker (all variants, paper)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                ScalpData.makerLines(scalpAll).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary) }
                Text(
                    "Maker variants post at the bid / exit at the ask (maker fee $0). Strategy fills use a conservative queue model " +
                        "(trade-through or estimated queue ahead consumed). AS30/AS60 = mid move after the fill; our earlier LIP study saw " +
                        "6–15¢ adverse selection on one-legged resting fills, so fee-free is not free.",
                    style = MaterialTheme.typography.labelSmall, color = colors.textSecondary
                )
            }
        }
        item {
            var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.OutlinedButton(onClick = {
                if (confirm) { viewModel.resetPaperBankrollTo20k(); confirm = false } else confirm = true
            }) { androidx.compose.material3.Text(if (confirm) PaperResetCopy.CONFIRM else PaperResetCopy.BUTTON) }
        }
        item {
            com.dirk.kalshiodds.ui.components.PaperOrdersPanel(
                tickers = emptyList(), orders = paperOrders, showTicket = false,
                onSubmit = { _, _, _, _, _, _ -> }, onEdit = viewModel::editPaperOrder, onCancel = viewModel::cancelPaperOrder
            )
        }
        item {
            DecisionCard {
                Text("Summary", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                ScalpData.summaryLines(s).forEachIndexed { i, line ->
                    Text(
                        line, style = MaterialTheme.typography.bodySmall,
                        color = if (i == 0) (if (s.netUsd >= 0) colors.up else colors.down) else colors.textPrimary
                    )
                }
            }
        }
        item {
            DecisionCard {
                Text("Equity curve (cumulative net, ${curve.size - 1} round trips)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                if (curve.size < 2) {
                    Text("No closed round trips yet.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                } else {
                    val line = if (curve.last() >= 0) colors.up else colors.down
                    val zero = colors.textSecondary
                    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                        val lo = minOf(0.0, curve.min()); val hi = maxOf(0.0, curve.max())
                        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                        fun y(v: Double) = (size.height * (1.0 - (v - lo) / span)).toFloat()
                        val dx = size.width / (curve.size - 1).coerceAtLeast(1)
                        drawLine(zero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
                        val path = Path()
                        curve.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * dx, y(v)) }
                        drawPath(path, line, style = Stroke(width = 3f))
                    }
                    Text(String.format(Locale.US, "Low %+.2f · high %+.2f · now %+.2f USD", curve.min(), curve.max(), curve.last()),
                        style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
        item {
            DecisionCard {
                Text("By strategy, coin and hour (ET)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                ScalpBreakdown.lines(trades).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary) }
            }
        }
        item { OutlinedButton(onClick = onOpenScalp) { Text("Scalp rules, params and open scalps") } }
        item { Text("Every round trip (${rows.size}, newest first)", fontWeight = FontWeight.SemiBold, color = colors.textPrimary) }
        items(rows, key = { "t" + it.key }) { row ->
            Text(row.text, style = MaterialTheme.typography.labelMedium,
                color = when (row.positive) { true -> colors.up; false -> colors.down; null -> colors.textSecondary })
        }
    }
}

/** 0.3.46 shared copy for the $20,000 reset button (Settings + Scalp Data). */
object PaperResetCopy {
    const val BUTTON = "Reset paper bankroll to $20,000"
    const val CONFIRM = "Tap again to reset — history is archived"
    const val DONE = "Paper bankroll reset to $20,000 — prior paper fills archived, no Kalshi orders"
}
