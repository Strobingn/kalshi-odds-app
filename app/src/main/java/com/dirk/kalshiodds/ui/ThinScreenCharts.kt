package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/*
 * Charts for the screens that used to be text only: Signal history, Data and Arb Hunter. Built from the AI tab's chart
 * kit (AiCharts.kt / InsightCharts.kt); the numbers come from [ThinScreenChartData].
 */

private fun pp(v: Double) = String.format(Locale.US, "%.1fpp", v)
private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)

/** Signal history: totals, UP/DOWN split, signals per hour, the edge of each signal and the regime mix. */
@Composable
fun SignalHistoryCharts(s: ThinScreenChartData.SignalStats) {
    val c = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Signals", s.total.toString(), c.textPrimary, "${s.passed} passed filter")
            StatTile("Lean up", if (s.total == 0) "–" else pct(s.up.toDouble() / s.total), c.up, "${s.up} up · ${s.down} down")
            StatTile("Avg edge", pp(s.avgEdgePp), c.accentBlue, s.avgConfidence?.let { "conf ${pct(it)}" } ?: "no confidence")
        }
        AiCard("Up vs down", "Which side each signal leaned") {
            Donut(
                slices = listOf(
                    Triple("Up / YES", s.up.toFloat(), c.up),
                    Triple("Down / NO", s.down.toFloat(), c.down)
                ),
                center = s.total.toString(),
                centerSub = "signals"
            )
        }
        AiCard("Signals per hour", "Last ${ThinScreenChartData.HOURS} hours, Eastern time") {
            Legend(listOf("Up" to c.up, "Down" to c.down))
            StackedBars(
                buckets = s.hourly.map { listOf(it.up.toFloat(), it.down.toFloat()) },
                colors = listOf(c.up, c.down),
                xLabels = s.hourly.map { it.label },
                height = 120.dp
            )
        }
        AiCard("Edge of each signal", "Fair value minus market, newest on the right") {
            LineChart(s.edgeSeries, height = 110.dp, format = { pp(it) })
        }
        if (s.regimes.isNotEmpty()) {
            AiCard("Regime mix", "Market regime when each signal fired") {
                ShareBars(s.regimes)
            }
        }
    }
}

/** Data: the last settled windows as a strip, the YES/NO split, streaks and the YES rate by hour of day. */
@Composable
fun SettledWindowCharts(s: ThinScreenChartData.SettledStats) {
    val c = DipTheme.colors
    val total = s.yes + s.no
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AiCard("Last ${s.strip.size} Bitcoin windows", "Oldest on the left: green settled up, red settled down") {
            ResultStrip(s.strip)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val streak = s.currentStreak
            StatTile(
                "Streak now",
                if (streak == 0) "–" else "${kotlin.math.abs(streak)} ${if (streak > 0) "up" else "down"}",
                if (streak >= 0) c.up else c.down
            )
            StatTile("Longest up", s.longestYes.toString(), c.up)
            StatTile("Longest down", s.longestNo.toString(), c.down)
        }
        AiCard("Up vs down", "Every labeled window in the archive") {
            Donut(
                slices = listOf(
                    Triple("Up / YES", s.yes.toFloat(), c.up),
                    Triple("Down / NO", s.no.toFloat(), c.down)
                ),
                center = if (total == 0) "–" else pct(s.yes.toDouble() / total),
                centerSub = "settled up"
            )
        }
        val hours = s.yesRateByHour
        if (hours.any { it.second > 0 }) {
            AiCard("Up rate by hour", "Eastern time; above the line = up more than half the time") {
                SignedColumns(
                    labels = (0 until 24).map { ThinScreenChartData.hourLabel(it) },
                    values = hours.map { (rate, n) -> if (n == 0) 0.0 else (rate - 0.5) * 100 },
                    height = 110.dp,
                    labelEvery = 6,
                    format = { v -> String.format(Locale.US, "%+.0fpp", v) }
                )
            }
        }
    }
}

/** Arb Hunter: how close each set is to free money, in cents per set after fees. */
@Composable
fun ArbGapChart(gaps: List<ThinScreenChartData.ArbGap>) {
    if (gaps.isEmpty()) return
    AiCard("Closest to a free set", "Cents per set after fees; right of the line is a real edge") {
        SignedBars(
            gaps.map { g ->
                SignedBar(
                    label = g.label,
                    value = g.edgeCentsPerSet,
                    valueText = String.format(Locale.US, "%+.1f¢", g.edgeCentsPerSet),
                    note = if (g.profitable) "opportunity" else "near miss"
                )
            }
        )
    }
}

/** A grid of small squares, one per settled window, wrapped onto as many rows as it needs. */
@Composable
fun ResultStrip(results: List<Boolean>, perRow: Int = 24, cell: Dp = 12.dp) {
    val c = DipTheme.colors
    if (results.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(cell * 2), contentAlignment = Alignment.Center) {
            Text("No settled windows yet", fontSize = 12.sp, color = c.textSecondary)
        }
        return
    }
    val rows = ceil(results.size / perRow.toDouble()).toInt().coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(cell * rows + 2.dp * (rows - 1))) {
        val gap = 2.dp.toPx()
        val side = max(1f, (size.width - gap * (perRow - 1)) / perRow)
        val h = (size.height - gap * (rows - 1)) / rows
        results.forEachIndexed { i, yes ->
            val r = i / perRow
            val col = i % perRow
            drawRoundRect(
                if (yes) c.up else c.down,
                Offset(col * (side + gap), r * (h + gap)),
                Size(side, h),
                CornerRadius(3f)
            )
        }
    }
}

/** Neutral 0–100% bars for a mix (no good/bad colouring, unlike [PercentBars]). */
@Composable
fun ShareBars(rows: List<Pair<String, Double>>, labelWidth: Dp = 118.dp) {
    val c = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, frac) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(labelWidth), fontSize = 11.sp, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    drawRoundRect(c.surfaceAlt, Offset.Zero, size, CornerRadius(7f))
                    drawRoundRect(c.accentBlue, Offset.Zero, Size(size.width * frac.coerceIn(0.0, 1.0).toFloat(), size.height), CornerRadius(7f))
                }
                Text(pct(frac), Modifier.width(48.dp).padding(start = 6.dp), fontSize = 11.sp, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}
