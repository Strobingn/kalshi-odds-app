package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.ScalpWindow
import com.dirk.kalshiodds.chart.SpotAxis
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

internal object ScalpChartCopy {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneId.of("America/New_York"))
    private val minute = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.of("America/New_York"))
    fun time(ms: Long) = time.format(Instant.ofEpochMilli(ms))
    fun minute(ms: Long) = minute.format(Instant.ofEpochMilli(ms))
    fun dollars(v: Double?) = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "$%,.2f", it) } ?: "—"
    fun cents(v: Double?) = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—"
    fun side(side: String) = if (side == "NO") "DOWN" else "UP"
}

@Composable
internal fun ScalpAlgorithmPicker(code: String, onSelect: (String) -> Unit, onSliders: () -> Unit) {
    val c = DipTheme.colors
    var picking by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val model = ScalpModels.modelByCode(code)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Text((model?.label ?: "All algorithms") + " ▾", maxLines = 2)
        }
        Button(onClick = { if (model == null) picking = true else onSliders() }) { Text("Sliders") }
    }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, confirmButton = {
        TextButton(onClick = { picking = false }) { Text("Close") }
    }, title = { Text("Choose algorithm · sliders & chart") }, text = {
        Column {
            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                label = { Text("Find algorithm") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { TextButton(onClick = { onSelect("*"); picking = false }) { Text("All algorithms · compare fills") } }
                items(ScalpModels.ALL.filter { it.label.contains(search, true) || it.code.contains(search, true) }, key = { it.code }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(m.code); picking = false; onSliders() }.padding(vertical = 12.dp)) {
                        Text(m.label, color = c.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(m.code, color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    })
}

@Composable
internal fun ScalpLiveWindow(window: ScalpWindow, trades: List<ScalpTrade>, selectedId: String?, onSelect: (String) -> Unit) {
    val c = DipTheme.colors
    val q = window.quote
    val fresh = q != null && !q.bookGap && window.nowMs - q.bookAtMs in 0..3000L
    var scrubAt by remember(window.ticker) { mutableStateOf<Long?>(null) }
    val selected = trades.firstOrNull { it.id == selectedId }
    val point = scrubAt?.let { at -> window.points.minByOrNull { abs(it.tMs - at) } }
    val markers = remember(window, trades) { window.markers(trades) }
    ScalpLabCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live · 15-minute window", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(if (window.ticker.isBlank()) "Waiting for the next BTC market" else window.ticker,
                    style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            ScalpLabChip(if (fresh) "LIVE" else "STALE", if (fresh) c.up else c.accentOrange, c.surfaceAlt)
        }
        if (window.endMs > 0) {
            Text("${ScalpChartCopy.minute(window.startMs)}–${ScalpChartCopy.minute(window.endMs)} ET · " +
                ScalpTabCopy.timeLeft(((window.endMs - window.nowMs) / 1000).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuoteCell("UP bid / ask", ScalpChartCopy.cents(q?.bid("YES")) + " / " + ScalpChartCopy.cents(q?.ask("YES")), c.up, Modifier.weight(1f))
            QuoteCell("DOWN bid / ask", ScalpChartCopy.cents(q?.bid("NO")) + " / " + ScalpChartCopy.cents(q?.ask("NO")), c.down, Modifier.weight(1f))
        }
        Text("Bid lines: UP green · DOWN red. ▲ BUY · ■ SELL · ○ settlement", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        val plotHeight = 250.dp
        Canvas(Modifier.fillMaxWidth().height(plotHeight).semantics {
            contentDescription = "15-minute UP and DOWN bids; ${markers.count { it.fill.kind == ScalpFillLog.Kind.BUY }} buy markers and ${markers.count { it.fill.kind != ScalpFillLog.Kind.BUY }} exit markers. Trade details follow the chart."
        }.pointerInput(window.ticker, window.points, markers, selectedId) {
            fun choose(x: Float, y: Float?) {
                val left = 30.dp.toPx(); val width = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
                val top = 16.dp.toPx(); val height = (size.height - top - 26.dp.toPx()).coerceAtLeast(1f)
                val at = window.startMs + (((x - left) / width).coerceIn(0f, 1f) * 900_000).toLong()
                val hit = if (y == null) null else markers.minByOrNull {
                    hypot(left + width * (it.fill.atMs - window.startMs) / 900_000f - x,
                        top + height * (1 - it.fill.price.toFloat()) - y)
                }?.takeIf { hypot(left + width * (it.fill.atMs - window.startMs) / 900_000f - x,
                    top + height * (1 - it.fill.price.toFloat()) - y) <= 24.dp.toPx() }
                if (hit != null) { onSelect(hit.tradeId); scrubAt = hit.fill.atMs } else scrubAt = at
            }
            detectTapGestures { choose(it.x, it.y) }
        }.pointerInput(window.ticker, window.points) {
            detectHorizontalDragGestures(onDragStart = { scrubAt = window.startMs + (((it.x - 30.dp.toPx()) /
                (size.width - 38.dp.toPx())).coerceIn(0f, 1f) * 900_000).toLong() }) { change, _ ->
                scrubAt = window.startMs + (((change.position.x - 30.dp.toPx()) / (size.width - 38.dp.toPx())).coerceIn(0f, 1f) * 900_000).toLong()
            }
        }) {
            val left = 30.dp.toPx(); val right = size.width - 8.dp.toPx(); val top = 16.dp.toPx(); val bottom = size.height - 26.dp.toPx()
            fun x(at: Long) = left + (right - left) * ((at - window.startMs) / 900_000f).coerceIn(0f, 1f)
            fun y(px: Double) = bottom - (bottom - top) * px.toFloat().coerceIn(0f, 1f)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = c.textSecondary.toArgb(); textSize = 10.sp.toPx() }
            for (v in listOf(0, 25, 50, 75, 100)) {
                drawLine(c.border, Offset(left, y(v / 100.0)), Offset(right, y(v / 100.0)), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("$v", 0f, y(v / 100.0) + 3.dp.toPx(), paint)
            }
            if (window.endMs > 0) for (i in 0..3) {
                val at = window.startMs + i * 300_000L
                val label = ScalpChartCopy.minute(at)
                val tx = (x(at) - paint.measureText(label) / 2).coerceIn(0f, (size.width - paint.measureText(label)).coerceAtLeast(0f))
                drawContext.canvas.nativeCanvas.drawText(label, tx, size.height - 5.dp.toPx(), paint)
            }
            val nowX = x(window.nowMs)
            drawRect(c.bg.copy(alpha = .5f), Offset(nowX, top), Size((right - nowX).coerceAtLeast(0f), bottom - top))
            fun line(up: Boolean) {
                val path = Path(); var previous: BidPoint? = null
                window.points.forEach { p ->
                    val value = if (up) p.upBidCents else p.downBidCents
                    if (value == null || !value.isFinite() || value !in 0f..100f) { previous = null; return@forEach }
                    if (previous == null || p.tMs - previous!!.tMs > 15_000) path.moveTo(x(p.tMs), y(value / 100.0)) else path.lineTo(x(p.tMs), y(value / 100.0))
                    previous = p
                }
                drawPath(path, if (up) c.up else c.down, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            line(true); line(false)
            drawLine(c.accentBlue.copy(alpha = .65f), Offset(left, y(.50)), Offset(right, y(.50)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            selected?.entryPrice?.let { entry ->
                if (selected.remaining > 0) drawLine(c.accentOrange, Offset(left, y((entry - .02).coerceAtLeast(0.0))),
                    Offset(right, y((entry - .02).coerceAtLeast(0.0))), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            }
            markers.forEach { marker ->
                val fill = marker.fill; val px = x(fill.atMs); val py = y(fill.price)
                val chosen = marker.tradeId == selectedId
                val radius = (if (chosen) 6 else 4).dp.toPx()
                val ink = when (fill.kind) { ScalpFillLog.Kind.BUY -> c.accentBlue; ScalpFillLog.Kind.SELL -> c.accentOrange; ScalpFillLog.Kind.SETTLE -> c.textSecondary }
                if (chosen) drawCircle(c.textPrimary, radius + 2.dp.toPx(), Offset(px, py))
                when (fill.kind) {
                    ScalpFillLog.Kind.BUY -> drawPath(Path().apply { moveTo(px, py - radius); lineTo(px - radius, py + radius); lineTo(px + radius, py + radius); close() }, ink)
                    ScalpFillLog.Kind.SELL -> drawRect(ink, Offset(px - radius, py - radius), Size(radius * 2, radius * 2))
                    ScalpFillLog.Kind.SETTLE -> drawCircle(ink, radius, Offset(px, py), style = Stroke(2.dp.toPx()))
                }
            }
            scrubAt?.let { drawLine(c.textSecondary, Offset(x(it), top), Offset(x(it), bottom), 1.dp.toPx()) }
        }
        if (window.points.isEmpty()) Text("Waiting for quote history. The chart stays on the full 15-minute axis; missing prices are left blank.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        point?.let { p -> Text("${ScalpChartCopy.time(p.tMs)} ET · UP ${ScalpChartCopy.cents(p.upBidCents?.toDouble()?.div(100))} · DOWN ${ScalpChartCopy.cents(p.downBidCents?.toDouble()?.div(100))} · spot ${ScalpChartCopy.dollars(p.spotUsd)}",
            style = MaterialTheme.typography.labelMedium, color = c.textPrimary) }
        Text("Tap a marker or trade to inspect it. Drag sideways to read quotes. Dashed blue = 50¢ buy cap; orange = selected position’s 2¢ stop.", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuoteCell("Spot", ScalpChartCopy.dollars(q?.spot), c.textPrimary, Modifier.weight(1f))
            QuoteCell("Strike", ScalpChartCopy.dollars(q?.strike), c.textPrimary, Modifier.weight(1f))
        }
        SpotWindowCanvas(window)
        val closed = trades.filter { it.state == ScalpState.CLOSED }
        val active = trades.filter { it.entryPrice != null && it.remaining > 0 && it.state != ScalpState.CLOSED }
        val realized = closed.sumOf { it.netUsd ?: 0.0 }
        val marked = if (fresh) active.mapNotNull { it.unrealizedUsd(q?.bid(it.side)) }.takeIf { it.size == active.size }?.sum() else null
        StatPair("Closed P&L", ScalpTabCopy.money(realized), "Open net (est.)", marked?.let(ScalpTabCopy::money) ?: "—")
        StatPair("Paid fees", ScalpChartCopy.dollars(trades.sumOf { it.entryFeeUsd + it.exitFeeUsd }), "Positions", "${active.size} open")
        Text("Quote age ${q?.let { "${(window.nowMs - it.bookAtMs).coerceAtLeast(0)}ms" } ?: "—"} · ${window.points.size} chart prints · ${markers.size} execution markers" +
            if (q?.bookGap == true) " · book gap" else "", style = MaterialTheme.typography.labelSmall, color = if (fresh) c.textSecondary else c.accentOrange)
        window.error?.let { Text(it, color = c.accentOrange, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun QuoteCell(title: String, value: String, ink: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = DipTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
private fun SpotWindowCanvas(window: ScalpWindow) {
    val c = DipTheme.colors
    val points = window.points.filter { it.spotUsd?.let { px -> px.isFinite() && px > 0 } == true }
    val strike = window.quote?.strike
    val range = SpotAxis.range(points.mapNotNull { it.spotUsd }, strike) ?: return
    Text("Underlying spot vs strike", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    Canvas(Modifier.fillMaxWidth().height(85.dp).semantics { contentDescription = "Underlying spot price; dashed line marks the strike" }) {
        fun x(at: Long) = size.width * ((at - window.startMs) / 900_000f).coerceIn(0f, 1f)
        fun y(px: Double) = size.height * SpotAxis.yFraction(px, range.first, range.second)
        strike?.takeIf { it.isFinite() && it > 0 }?.let { drawLine(c.textSecondary, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))) }
        val path = Path(); var last: Long? = null
        points.forEach { p -> if (last == null || p.tMs - last!! > 15_000) path.moveTo(x(p.tMs), y(p.spotUsd!!)) else path.lineTo(x(p.tMs), y(p.spotUsd!!)); last = p.tMs }
        drawPath(path, c.accentBlue, style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun ScalpWindowTradeCard(t: ScalpTrade, selected: Boolean, q: ScalpRule.Quote?, onSelect: () -> Unit) {
    val c = DipTheme.colors
    ScalpLabCard(onSelect) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (selected) "● " else "") + t.algoLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, fontWeight = FontWeight.Bold)
            ScalpLabChip(ScalpAlgoCopy.state(t), if (t.state == ScalpState.CLOSED && (t.netUsd ?: 0.0) < 0) c.down else c.accentBlue, c.surfaceAlt)
        }
        Text("${ScalpChartCopy.side(t.side)} · ${t.contracts} ct · bought ${ScalpChartCopy.cents(t.entryPrice)} · sold ${t.soldContracts} · left ${t.remaining}", style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
        Text("Signal ${ScalpChartCopy.time(t.signalAtMs)} ET" + (t.entryAtMs?.let { " · fill +${it - t.signalAtMs}ms" } ?: " · not filled"), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        ScalpFillLog.display(t).forEach { fill ->
            Text("${ScalpChartCopy.time(fill.atMs)} ${fill.kind}${if (fill.aggregate) " AVG / final close" else ""} · ${fill.qty} ct @ ${ScalpChartCopy.cents(fill.price)} · fee ${ScalpChartCopy.dollars(fill.fee)}",
                style = MaterialTheme.typography.labelMedium, color = when (fill.kind) { ScalpFillLog.Kind.BUY -> c.accentBlue; ScalpFillLog.Kind.SELL -> c.accentOrange; ScalpFillLog.Kind.SETTLE -> c.textSecondary })
        }
        if (t.entryPrice != null) StatPair("Stop trigger", ScalpChartCopy.cents((t.entryPrice - .02).coerceAtLeast(0.0)), "Fees paid", ScalpChartCopy.dollars(t.entryFeeUsd + t.exitFeeUsd))
        Text("Net ${t.netUsd?.let(ScalpTabCopy::money) ?: "open"}" + (t.holdMs?.let { " · held ${it / 1000}s" } ?: "") +
            (t.exitReason?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
        if (t.soldContracts > ScalpFillLog.display(t).filter { it.kind != ScalpFillLog.Kind.BUY }.sumOf { it.qty })
            Text("Earlier partial-exit timestamps were not recorded in this older trade.", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}
