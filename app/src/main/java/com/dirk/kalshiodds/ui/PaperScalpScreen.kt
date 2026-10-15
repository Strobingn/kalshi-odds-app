package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val paperColors = darkColorScheme(primary = Color.White, onPrimary = Color.Black,
    secondary = Color(0xFFCCCCCC), onSecondary = Color.Black,
    primaryContainer = Color(0xFF444444), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF444444), onSecondaryContainer = Color.White, background = Color(0xFF101010),
    surface = Color(0xFF1C1C1C), surfaceVariant = Color(0xFF2B2B2B), onSurface = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFFBBBBBB), outline = Color(0xFF777777))
private val families = listOf("adaptive" to "Adaptive", "cheap" to "Discount", "dip" to "Dip", "momentum" to "Momentum", "forecast-fade" to "Fade")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaperScalpScreen(state: PaperScalpState, onBack: () -> Unit, onStart: () -> Unit, onPause: (Boolean) -> Unit) {
    var family by rememberSaveable { mutableStateOf("adaptive") }
    var allocation by rememberSaveable { mutableIntStateOf(35) }
    var drawdown by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val a = state.accounts.firstOrNull { it.id == "$family-$allocation" } ?: state.accounts.firstOrNull() ?: return
    val started = a.policyStartedMs > 0
    val change = if (started) a.equity() - a.policyStartEquity else 0.0
    val closed = a.closed - a.policyStartClosed
    val wins = a.wins - a.policyStartWins
    val now = System.currentTimeMillis()
    // Final live point is a real mark; older points are persisted observations, never a fabricated backtest.
    val points = a.curve + if (started && state.lastQuoteMs > (a.curve.lastOrNull()?.atMs ?: 0))
        listOf(PaperCurvePoint(state.lastQuoteMs, a.equity(), a.realized, a.fees, a.peakEquity)) else emptyList()
    MaterialTheme(colorScheme = paperColors) {
        Scaffold(topBar = { TopAppBar(title = { Text("Scalp lab") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PAPER · POLICY 2", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(state.source.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                    Text(if (state.lastQuoteMs == 0L) "No book received" else "Book age ${((now - state.lastQuoteMs).coerceAtLeast(0) / 1000)}s${if (now - state.lastQuoteMs > 3000) " · STALE MARKS" else ""}", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart) { Text("Start monitor") }
                        OutlinedButton(onClick = { onPause(!state.paused) }) { Text(if (state.paused) "Resume entries" else "Pause entries") }
                    }
                    if (state.paused) Text("Entries paused · existing stops continue", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        families.forEach { (id, label) -> FilterChip(selected = family == id, onClick = { family = id }, label = { Text(label) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 35, 70, 100).forEach { n -> FilterChip(selected = allocation == n, onClick = { allocation = n }, label = { Text("$n%") }) }
                    }
                    Text("Paper cash allocation ceiling · each book starts with $20,000", style = MaterialTheme.typography.labelSmall)
                }
                item {
                    PaperPanel {
                        Text("${families.first { it.first == family }.second} · $allocation%", style = MaterialTheme.typography.titleMedium)
                        Text(money(change), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("Equity change since policy 2 started${if (!started) " · awaiting first quote" else ""}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!drawdown, onClick = { drawdown = false }, label = { Text("Equity") })
                            FilterChip(drawdown, onClick = { drawdown = true }, label = { Text("Drawdown") })
                        }
                        EquityPlot(points, a.policyStartEquity, drawdown)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("Max drawdown", money(-a.maxDrawdown))
                            Metric("Fees · policy 2", money(-(a.fees - a.policyStartFees)))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("Win rate", if (closed == 0) "—" else "${wins * 100 / closed}% · $closed trades")
                            Metric("Current equity", dollars(a.equity()))
                        }
                        Text("All-time realized ${money(a.realized)} · fees ${dollars(a.fees)} · ${a.closed} closed", style = MaterialTheme.typography.labelSmall)
                        Text("Policy 2 begins at the first quote after updating. Existing cash and losses are preserved.", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    PaperPanel {
                        Text("Compare strategies", style = MaterialTheme.typography.titleMedium)
                        Text("Equity change · same $allocation% ceiling · separate paper books", style = MaterialTheme.typography.labelSmall)
                        val rows = families.map { (id, label) -> Triple(id, label, state.accounts.firstOrNull { it.id == "$id-$allocation" }) }
                        val scale = rows.maxOf { (_, _, account) -> abs(account?.let { if (it.policyStartedMs > 0) it.equity() - it.policyStartEquity else 0.0 } ?: 0.0) }.coerceAtLeast(1.0)
                        rows.forEach { (id, label, account) ->
                            val pnl = account?.let { if (it.policyStartedMs > 0) it.equity() - it.policyStartEquity else 0.0 } ?: 0.0
                            TextButton(onClick = { family = id }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(label, fontWeight = if (id == family) FontWeight.Bold else FontWeight.Normal)
                                        Text(money(pnl))
                                    }
                                    Canvas(Modifier.fillMaxWidth().height(16.dp).semantics { contentDescription = "$label equity change ${money(pnl)}" }) {
                                        val mid = size.width / 2
                                        drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height), 1.dp.toPx())
                                        val width = (abs(pnl) / scale * (mid - 4.dp.toPx())).toFloat()
                                        drawRect(if (pnl < 0) Color.Gray else Color.White, Offset(if (pnl < 0) mid - width else mid, 4.dp.toPx()), Size(width, 8.dp.toPx()))
                                    }
                                }
                            }
                        }
                        Text("← loss                         gain →", style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    PaperPanel {
                        Text("Open positions · ${a.positions.size}", style = MaterialTheme.typography.titleMedium)
                        if (a.positions.isEmpty()) Text(if (a.pending.isEmpty()) a.note else "${a.pending.size} entry decisions await a later book", style = MaterialTheme.typography.bodySmall)
                        a.positions.forEach { p ->
                            Text("${p.side} · ${p.remaining} contracts · ${p.setup}", fontWeight = FontWeight.Bold)
                            Text(p.ticker, style = MaterialTheme.typography.labelSmall)
                            StopRail(p)
                            Text(p.exitReason?.let { "$it · waiting for executable depth" } ?: when {
                                p.policyVersion < 2 -> "Legacy position · original stop retained"
                                p.trailArmed -> "Profit trail active · ${cents(p.trailDistance)} behind peak"
                                else -> "Initial stop active · profit trail waiting for fees + 1¢ net"
                            }, style = MaterialTheme.typography.bodySmall)
                            if (now - p.markAtMs > 3000) Text("STALE · awaiting fresh depth or official settlement", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                item {
                    PaperPanel {
                        val recent = a.results.filter { it.policyVersion == 2 }.takeLast(20)
                        Text("Recent trades · net after fees", style = MaterialTheme.typography.titleMedium)
                        TradeBars(recent)
                        val windows = recent.map { it.ticker }.distinct().size
                        Text("${recent.size} trades / $windows contract windows · sizing copies share signals", style = MaterialTheme.typography.labelSmall)
                        recent.takeLast(5).asReversed().forEach { trade ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${trade.side} · ${trade.count} ct · ${clock(trade.atMs)}", style = MaterialTheme.typography.bodySmall)
                                Text(money(trade.netUsd), fontWeight = FontWeight.Bold)
                            }
                            Text("${trade.reason} · fees ${dollars(trade.feesUsd)}", style = MaterialTheme.typography.labelSmall)
                        }
                        recent.lastOrNull()?.let { last ->
                            Text("Learning · ${last.setup} / ${PaperScalpPolicy.bucket(last.entry)}¢", style = MaterialTheme.typography.labelMedium)
                            Text(PaperScalpLearning.lesson(a.results, last.setup, last.entry).label, style = MaterialTheme.typography.labelSmall)
                        }
                        if (recent.isEmpty()) Text("No policy 2 trades yet. Historic trades remain in the all-time totals.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    TextButton(onClick = { details = !details }) { Text(if (details) "Hide strategy details" else "Strategy & execution details") }
                    if (details) {
                        Text("New entries: price below 50¢, projected move covers both fees + 1¢ net. Initial stop 6–10¢ based on spread and recent noise; profit trail 2–4¢ arms after its exit level covers fees + 1¢ net. Distances freeze at entry; stops only tighten. Losses reduce size and pause re-entry in that contract for 1–5 minutes. Sufficient evidence of a losing setup blocks that setup/price bucket.", style = MaterialTheme.typography.bodySmall)
                        Text("All orders are simulated against visible depth with fees and a minimum assumed 250ms delay. Missing bids and partial exits remain open. Forecasts and projected moves are experimental, not validated profit estimates. Curves retain the latest 1,440 observations, sampled every 15s and on fills; drawdown metrics track every processed quote. Stops need the monitor running and cannot guarantee a fill price.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun PaperPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = paperColors.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun Metric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
}
@Composable private fun EquityPlot(points: List<PaperCurvePoint>, baseline: Double, drawdown: Boolean) {
    var cursor by remember(points.size, drawdown) { mutableStateOf<Int?>(null) }
    var peak = baseline
    val values = points.map { p -> peak = max(peak, p.peakEquity); if (drawdown) p.equity - peak else p.equity - baseline }
    if (points.size < 2) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { Text("Chart starts with fresh observations", style = MaterialTheme.typography.bodySmall) }
        return
    }
    val lo = minOf(values.min(), 0.0)
    val hi = maxOf(values.max(), 0.0)
    val span = (hi - lo).coerceAtLeast(1.0)
    val start = points.first().atMs
    val duration = (points.last().atMs - start).coerceAtLeast(1)
    val at = cursor ?: points.lastIndex
    Text("${clock(points[at].atMs)} · ${if (drawdown) "drawdown" else "equity change"} ${money(values[at])}", style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "${if (drawdown) "Drawdown" else "Equity change"} chart, ${points.size} observations, latest ${money(values.last())}, low ${money(lo)}, high ${money(hi)}" }) {
        fun y(v: Double) = (8.dp.toPx() + (size.height - 16.dp.toPx()) * (1 - (v - lo) / span)).toFloat()
        for (i in 0..3) {
            val yy = 8.dp.toPx() + (size.height - 16.dp.toPx()) * i / 3
            drawLine(Color(0xFF414141), Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx())
        }
        drawLine(Color.Gray, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = ((points[i].atMs - start).toDouble() / duration * size.width).toFloat()
            if (i == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 3.dp.toPx(), Offset(size.width, y(values.last())))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${clock(start)} · low ${money(lo)}", style = MaterialTheme.typography.labelSmall)
        Text("high ${money(hi)} · ${clock(points.last().atMs)}", style = MaterialTheme.typography.labelSmall)
    }
    // Slider permits precise inspection without fighting vertical scrolling on a phone.
    Slider(value = at.toFloat(), onValueChange = { cursor = it.toInt().coerceIn(points.indices) }, valueRange = 0f..points.lastIndex.toFloat())
}
@Composable private fun StopRail(p: PaperScalpPosition) {
    val low = (minOf(p.stop, p.lastBid, p.entry) - .01).coerceAtLeast(0.0)
    val high = (maxOf(p.entry, p.highBid, p.lastBid) + .01).coerceAtMost(1.0)
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Stop ${cents(p.stop)}, current bid ${cents(p.lastBid)}, entry ${cents(p.entry)}, peak ${cents(p.highBid)}" }) {
        fun x(price: Double) = (size.width * ((price - low) / (high - low).coerceAtLeast(.01))).toFloat()
        val y = size.height / 2
        drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
        drawLine(Color.White, Offset(x(p.stop), y - 12.dp.toPx()), Offset(x(p.stop), y + 12.dp.toPx()), 3.dp.toPx())
        drawLine(Color.LightGray, Offset(x(p.entry), y - 8.dp.toPx()), Offset(x(p.entry), y + 8.dp.toPx()), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
        drawCircle(Color.White, 5.dp.toPx(), Offset(x(p.lastBid), y))
    }
    Text("│ Stop ${cents(p.stop)}   ● Bid ${cents(p.lastBid)}   ┆ Entry ${cents(p.entry)}", style = MaterialTheme.typography.labelSmall)
}
@Composable private fun TradeBars(results: List<PaperScalpResult>) {
    if (results.isEmpty()) return
    val scale = results.maxOf { abs(it.netUsd) }.coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "Last ${results.size} trade outcomes, ${results.count { it.netUsd > 0 }} wins, net ${money(results.sumOf { it.netUsd })}" }) {
        val mid = size.height / 2
        drawLine(Color.Gray, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val step = size.width / results.size
        results.forEachIndexed { i, trade ->
            val height = (abs(trade.netUsd) / scale * (mid - 4.dp.toPx())).toFloat()
            drawRect(if (trade.netUsd < 0) Color.Gray else Color.White, Offset(i * step + step * .15f, if (trade.netUsd >= 0) mid - height else mid), Size(step * .7f, height))
        }
    }
    Text("Oldest → newest · above line = gain · below = loss", style = MaterialTheme.typography.labelSmall)
}
private fun money(value: Double) = String.format(Locale.US, "$%+,.2f", value)
private fun dollars(value: Double) = String.format(Locale.US, "$%,.2f", value)
private fun cents(value: Double) = String.format(Locale.US, "%.1f¢", value * 100)
private fun clock(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
