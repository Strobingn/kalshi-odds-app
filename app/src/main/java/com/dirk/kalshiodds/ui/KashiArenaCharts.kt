package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.charts.ChartStroke
import com.dirk.kalshiodds.ui.theme.ArenaColors
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.abs

@Composable
internal fun ArenaProfitChart(points: List<KashiArenaData.Point>, compact: Boolean = false) {
    val c = DipTheme.colors
    if (points.size < 2) { Text("Appears after a recorded window completes.", color = c.textSecondary); return }
    val low = minOf(0.0, points.minOf { it.net }); val high = maxOf(0.0, points.maxOf { it.net })
    val pad = ((high - low) * .08).coerceAtLeast(.05)
    val lo = low - pad; val hi = high + pad
    val start = points.first().atMs; val span = (points.last().atMs - start).coerceAtLeast(1)
    val color = pnlColor(points.last().net)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    fun nearest(f: Float): Int { val at = start + (f.coerceIn(0f, 1f) * span).toLong(); return points.indices.minBy { abs(points[it].atMs - at) } }
    if (!compact) {
        val p = points[selected ?: points.lastIndex]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ScalpTabCopy.money(p.net), style = ArenaUi.mono(MaterialTheme.typography.titleLarge), color = pnlColor(p.net))
            Text(if (selected == null) "Drag to read" else com.dirk.kalshiodds.domain.EtTime.clockSec(p.atMs), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(if (compact) 40.dp else 180.dp)
        .semantics { contentDescription = "Recorded window profit from ${ScalpTabCopy.money(points.first().net)} to ${ScalpTabCopy.money(points.last().net)}" }
        .pointerInput(points) { detectTapGestures { selected = nearest(it.x / size.width) } }
        .pointerInput(points) { detectHorizontalDragGestures { change, _ -> change.consume(); selected = nearest(change.position.x / size.width) } }) {
        fun x(p: KashiArenaData.Point) = 4.dp.toPx() + (size.width - 8.dp.toPx()) * ((p.atMs - start).toDouble() / span).toFloat()
        fun y(v: Double) = size.height * (1 - ((v - lo) / (hi - lo)).toFloat())
        drawLine(c.border, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p), y(p.net)) else lineTo(x(p), y(p.net)) } }
        val wash = Path().apply { moveTo(x(points.first()), y(0.0)); points.forEach { lineTo(x(it), y(it.net)) }; lineTo(x(points.last()), y(0.0)); close() }
        drawPath(wash, color.copy(alpha = .10f)); drawPath(path, color, style = Stroke(ChartStroke.SERIES_DP.dp.toPx()))
        val end = points[selected ?: points.lastIndex]
        if (selected != null) drawLine(c.textSecondary, Offset(x(end), 0f), Offset(x(end), size.height))
        drawCircle(color, 3.dp.toPx(), Offset(x(end), y(end.net)))
    }
    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(arenaDate(start), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(arenaDate(points.last().atMs) + " ET", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

internal fun arenaDate(at: Long): String = com.dirk.kalshiodds.domain.EtTime.dateClockBare(at)

@Composable
internal fun ArenaRaceChart(st: ScalpTabState, rows: List<ScalpModels.Row>) {
    val c = DipTheme.colors; val colors = ArenaColors.current().series
    val now = st.updatedAtMs
    val curves = rows.filter { it.roundTrips > 0 }.take(4).map { r -> r.model to KashiArenaData.curve(st.windows, now, setOf(r.model)) }.filter { it.second.size >= 2 }
    if (curves.isEmpty()) { Text("Recorded strategy curves appear as windows complete.", color = c.textSecondary); return }
    val all = curves.flatMap { it.second }; val from = all.minOf { it.atMs }; val span = (all.maxOf { it.atMs } - from).coerceAtLeast(1)
    val lo = minOf(0.0, all.minOf { it.net }); val hi = maxOf(0.0, all.maxOf { it.net }); val range = (hi - lo).coerceAtLeast(.01)
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Profit race for the top four traded strategies. Each line starts at zero for the displayed range." }) {
        fun y(v: Double) = 8.dp.toPx() + (size.height - 16.dp.toPx()) * ((hi - v) / range).toFloat()
        drawLine(c.border, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        curves.forEachIndexed { i, (_, points) ->
            val path = Path(); points.forEachIndexed { j, p -> val x = ((p.atMs - from).toDouble() / span * size.width).toFloat(); if (j == 0) path.moveTo(x, y(p.net)) else path.lineTo(x, y(p.net)) }
            drawPath(path, colors[i], style = Stroke(ChartStroke.SERIES_DP.dp.toPx()))
        }
    }
    curves.forEachIndexed { i, (model, points) -> Text("● ${model.label} · ${ScalpTabCopy.money(points.last().net)}", color = colors[i], style = MaterialTheme.typography.labelSmall) }
    Text("Recorded completed windows · starts at $0 for this range", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun ArenaBars(rows: List<ScalpModels.Row>, fees: Boolean = false) {
    val c = DipTheme.colors
    val shown = rows.filter { it.roundTrips > 0 }.take(8)
    val max = shown.maxOfOrNull { abs(if (fees) it.feesUsd else it.netUsd) }?.coerceAtLeast(.01) ?: 1.0
    if (shown.isEmpty()) Text("No closed trades yet.", color = c.textSecondary)
    shown.forEach { r ->
        val value = if (fees) r.feesUsd else r.netUsd
        Text(r.model.label, color = c.textPrimary, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.weight(1f).height(18.dp).semantics { contentDescription = "${r.model.label}: ${ScalpTabCopy.money(value)}" }) {
                val center = size.width / 2
                drawLine(c.border, Offset(center, 0f), Offset(center, size.height))
                val width = (abs(value) / max * (center - 2.dp.toPx())).toFloat()
                drawRect(if (fees) c.spot else if (value >= 0) c.up else c.down, Offset(if (value >= 0) center else center - width, 3.dp.toPx()), androidx.compose.ui.geometry.Size(width, size.height - 6.dp.toPx()))
            }
            Text(ScalpTabCopy.money(value), color = if (fees) c.textSecondary else pnlColor(value), style = ArenaUi.mono(MaterialTheme.typography.labelMedium), modifier = Modifier.width(82.dp))
        }
    }
}
