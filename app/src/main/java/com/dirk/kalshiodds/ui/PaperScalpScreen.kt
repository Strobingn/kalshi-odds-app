package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalp.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val ink = Color(0xFFF4F4F4)
private val mutedInk = Color(0xFFAAAAAA)
private val page = Color(0xFF101010)
private val rule = Color(0xFF343434)
private val chartInk = listOf(Color.White, Color(0xFFD5D5D5), Color(0xFFBBBBBB), Color(0xFF999999), Color(0xFF818181))
private val labColors = darkColorScheme(primary = ink, onPrimary = Color.Black, secondary = ink,
    background = page, surface = page, surfaceVariant = Color(0xFF222222), onSurface = ink,
    onSurfaceVariant = mutedInk, primaryContainer = Color(0xFF383838), secondaryContainer = Color(0xFF383838),
    onPrimaryContainer = ink, onSecondaryContainer = ink, outline = Color(0xFF666666))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaperScalpScreen(state: PaperScalpState, onBack: () -> Unit, onStart: () -> Unit, onPause: (Boolean) -> Unit,
                     onExport: suspend (Uri) -> String = { "Export is unavailable" }, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var family by rememberSaveable { mutableStateOf("adaptive") }
    var allocation by rememberSaveable { mutableIntStateOf(35) }
    var hours by rememberSaveable { mutableIntStateOf(0) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var drawdown by rememberSaveable { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(PaperPerformance.families.map { it.first }) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { onExport(uri) }.getOrElse { "Export failed: ${it.message}" }
            snackbar.showSnackbar(result)
        }
    }
    val account = state.accounts.firstOrNull { it.id == "$family-$allocation" } ?: state.accounts.firstOrNull() ?: return
    val series = remember(state.accounts, state.lastQuoteMs, allocation, newOnly, hours, drawdown) {
        PaperPerformance.series(state, allocation, newOnly, hours.takeIf { it > 0 }, drawdown)
    }
    val age = if (state.lastQuoteMs == 0L) null else ((now - state.lastQuoteMs).coerceAtLeast(0) / 1000)
    val pnl = if (newOnly) { if (account.policyVersion == PaperScalpPolicy.VERSION && account.policyStartedMs > 0) account.equity() - account.policyStartEquity else 0.0 } else account.pnl()
    fun choose(id: String) { family = id; if (id !in visible) visible = visible + id }
    MaterialTheme(colorScheme = labColors) {
        Scaffold(containerColor = page, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text("Strategies", fontWeight = FontWeight.SemiBold) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                Text("PAPER", style = MaterialTheme.typography.labelSmall, modifier = Modifier.border(1.dp, rule, RoundedCornerShape(4.dp)).padding(6.dp))
                IconButton(onClick = {
                    if (state.monitoring && !state.paused) onPause(true) else { onPause(false); onStart() }
                }) { Icon(if (state.monitoring && !state.paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.monitoring && !state.paused) "Pause new entries; keep exits running" else "Start paper monitor") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Lab settings and export") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Sizing & strategy rules") }, onClick = { menu = false; settings = true })
                        DropdownMenuItem(text = { Text("Export recorded books & trades") }, onClick = { menu = false; export.launch("BitcoinEdge-paper-replay.zip") })
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = page))
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(6.dp)) { drawCircle(if (age != null && age <= 3 && state.monitoring) ink else Color.Gray) }
                    Spacer(Modifier.width(8.dp))
                    Text("${state.source.replaceFirstChar { it.uppercase() }} data · ${if (!state.monitoring) "monitor off" else if (age == null) "connecting" else if (age > 3) "stale ${age}s" else "${age}s ago"}${if (state.paused) " · entries paused" else ""}", style = MaterialTheme.typography.labelSmall, color = mutedInk)
                }
                TabRow(tab, containerColor = page, contentColor = ink) {
                    listOf("Performance", "Positions", "Trades").forEachIndexed { i, title -> Tab(tab == i, onClick = { tab = i }, text = { Text(title) }) }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { picker = true }, contentPadding = PaddingValues(0.dp)) {
                                    Text("${PaperPerformance.families.first { it.first == family }.second}  ▾", style = MaterialTheme.typography.titleMedium)
                                }
                                DropdownMenu(picker, onDismissRequest = { picker = false }) {
                                    PaperPerformance.families.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { choose(id); picker = false }) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("$allocation% ceiling", style = MaterialTheme.typography.labelSmall, color = mutedInk, modifier = Modifier.clickable { settings = true }.padding(8.dp))
                        }
                    }
                    if (tab == 0) {
                        item {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Text(signed(pnl), style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(if (newOnly) "Equity change under the new rules" else "Net equity vs the original $20,000", style = MaterialTheme.typography.labelMedium, color = mutedInk)
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    ModeText("Net return", !drawdown) { drawdown = false }
                                    ModeText("Drawdown", drawdown) { drawdown = true }
                                }
                            }
                            ComparisonChart(series.filter { it.id in visible }, family, account.policyStartedMs.takeIf { !newOnly && account.policyVersion == 3 }, drawdown)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                listOf(1 to "1H", 6 to "6H", 0 to "ALL").forEach { (h, name) -> ModeText(name, hours == h) { hours = h } }
                                VerticalDivider(Modifier.height(28.dp), color = rule)
                                ModeText("Recorded", !newOnly) { newOnly = false }
                                ModeText("New rules", newOnly) { newOnly = true }
                            }
                            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PaperPerformance.families.forEachIndexed { index, (id, label) ->
                                    Row(Modifier.clickable { visible = if (id in visible) visible.filterNot { it == id } else visible + id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LineKey(index, id in visible)
                                        Spacer(Modifier.width(6.dp))
                                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (id in visible) ink else Color.Gray)
                                    }
                                }
                            }
                        }
                        item {
                            val closed = if (newOnly) (account.closed - account.policyStartClosed).coerceAtLeast(0) else account.closed
                            val fee = if (newOnly) account.fees - account.policyStartFees else account.fees
                            val plotted = series.first { it.id == family }.points
                            val dd = if (newOnly && hours == 0) account.maxDrawdown else {
                                var peak = Double.NEGATIVE_INFINITY
                                plotted.maxOfOrNull { p -> peak = maxOf(peak, p.value); peak - p.value } ?: 0.0
                            }
                            Row(Modifier.fillMaxWidth().border(1.dp, rule).padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Stat("${if (newOnly && hours == 0) "Rule" else "Shown"} drawdown", dollars(dd), Modifier.weight(1f))
                                Stat("Fees paid", dollars(fee), Modifier.weight(1f))
                                Stat("Closed", closed.toString(), Modifier.weight(1f))
                            }
                        }
                        item {
                            Text("Compare the books", Modifier.padding(start = 20.dp, top = 22.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Same allocation · independent paper cash", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = mutedInk)
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text("STRATEGY", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = mutedInk)
                                Text("NET EQUITY", style = MaterialTheme.typography.labelSmall, color = mutedInk)
                            }
                        }
                        items(PaperPerformance.families, key = { it.first }) { (id, label) ->
                            val a = state.accounts.firstOrNull { it.id == "$id-$allocation" }
                            val value = a?.let { if (newOnly && it.policyVersion == 3) it.equity() - it.policyStartEquity else if (newOnly) 0.0 else it.pnl() } ?: 0.0
                            Column(Modifier.fillMaxWidth().clickable { choose(id) }.background(if (id == family) Color(0xFF202020) else page).padding(horizontal = 20.dp, vertical = 14.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontWeight = if (id == family) FontWeight.Bold else FontWeight.Normal)
                                    Text(signed(value), fontFamily = FontFamily.Monospace)
                                }
                                Text(a?.note ?: "Waiting for data", style = MaterialTheme.typography.labelSmall, color = mutedInk, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                            }
                            HorizontalDivider(color = rule)
                        }
                        item {
                            val results = account.results.filter { !newOnly || it.policyVersion == 3 }
                            val positives = results.filter { it.netUsd > 0 }
                            val negatives = results.filter { it.netUsd < 0 }
                            val factor = if (negatives.isEmpty()) "—" else "%.2f".format(Locale.US, positives.sumOf { it.netUsd } / -negatives.sumOf { it.netUsd })
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Trade quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Stat("Profit factor", factor, Modifier.weight(1f))
                                    Stat("Avg win", if (positives.isEmpty()) "—" else dollars(positives.map { it.netUsd }.average()), Modifier.weight(1f))
                                    Stat("Avg loss", if (negatives.isEmpty()) "—" else dollars(-negatives.map { it.netUsd }.average()), Modifier.weight(1f))
                                }
                                Text("Last ${results.size} retained closes · ${results.map { it.ticker }.distinct().size} contract windows. Sizing copies share signals. Paper profitability is unverified.", style = MaterialTheme.typography.bodySmall, color = mutedInk)
                                Text("Charts contain observed marks only; gaps stay blank. Earlier rules remain in recorded history. Marks include an estimated exit fee and may exceed available exit depth.", style = MaterialTheme.typography.bodySmall, color = mutedInk)
                                Text(state.recorderNote, style = MaterialTheme.typography.labelSmall, color = mutedInk)
                            }
                        }
                    } else if (tab == 1) {
                        item {
                            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${account.positions.size} open · ${account.pending.size} pending", style = MaterialTheme.typography.headlineSmall)
                                Text("Available cash ${dollars(account.cash)}", color = mutedInk)
                                Text(account.note, style = MaterialTheme.typography.bodySmall)
                                if (account.positions.isEmpty()) Text("No open position in this book", Modifier.padding(vertical = 32.dp), color = mutedInk)
                            }
                        }
                        items(account.positions, key = { it.id }) { p -> PositionDetail(p, now) }
                    } else {
                        item {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Text("Realized ${signed(account.realized)}", style = MaterialTheme.typography.headlineSmall)
                                Text("${account.closed} closes · ${account.wins} wins · total fees ${dollars(account.fees)}", color = mutedInk, style = MaterialTheme.typography.bodySmall)
                                Text("Latest ${account.results.size} retained trades. Each row includes fees.", color = mutedInk, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (account.results.isEmpty()) item { Text("No completed trades yet", Modifier.padding(20.dp), color = mutedInk) }
                        items(account.results.asReversed(), key = { it.id }) { trade ->
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${trade.side} · ${trade.count} contracts", fontWeight = FontWeight.Bold)
                                    Text(signed(trade.netUsd), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Text("${time(trade.atMs, true)} · rules ${trade.policyVersion} · entry ${cents(trade.entry)}", style = MaterialTheme.typography.labelSmall, color = mutedInk)
                                Text("${trade.reason} · fees ${dollars(trade.feesUsd)}", style = MaterialTheme.typography.bodySmall)
                                Text(trade.ticker, style = MaterialTheme.typography.labelSmall, color = mutedInk)
                            }
                            HorizontalDivider(color = rule)
                        }
                    }
                }
            }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Paper strategy settings") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allocation ceiling for the displayed books")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 35, 70, 100).forEach { n ->
                    FilterChip(allocation == n, onClick = { allocation = n }, label = { Text("$n%") })
                } }
                Text("Rules 3 · range recovery, sweep reclaim, trend continuation, signed forecast follow and an adaptive mix. Every entry stays below 50¢ and must cover both fees plus at least 1.25× planned stop risk.", style = MaterialTheme.typography.bodySmall)
                Text("Initial stop 4–8¢ from spread and 1s bid noise. Trail 2–4¢; arms after fees and at least 75% of planned risk are earned at its proposed stop. New setups explore with 5% of their allocation ceiling; size only grows with completed evidence across markets. Loss cooldowns remain active.", style = MaterialTheme.typography.bodySmall)
                Text("Prior stops, cash and losses are retained. Updated-rule equity starts with the actual balance after upgrade; it includes any legacy positions still held. Charts retain 1,440 observations per book. No historic curve is invented.", style = MaterialTheme.typography.bodySmall)
                Text("${state.status}\n${state.recorderNote}", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    }
}

@Composable private fun ModeText(label: String, selected: Boolean, click: () -> Unit) {
    Text(label, Modifier.clickable(onClick = click).padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium,
        color = if (selected) ink else mutedInk, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
}
@Composable private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = mutedInk)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun LineKey(index: Int, enabled: Boolean) {
    Canvas(Modifier.width(22.dp).height(12.dp)) {
        drawLine(if (enabled) chartInk[index] else Color.DarkGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            2.dp.toPx(), pathEffect = pattern(index, density))
    }
}
private fun pattern(index: Int, density: Float): PathEffect? = when (index) {
    1 -> PathEffect.dashPathEffect(floatArrayOf(7f * density, 4f * density))
    2 -> PathEffect.dashPathEffect(floatArrayOf(2f * density, 3f * density))
    3 -> PathEffect.dashPathEffect(floatArrayOf(10f * density, 3f * density, 2f * density, 3f * density))
    4 -> PathEffect.dashPathEffect(floatArrayOf(13f * density, 6f * density))
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ComparisonChart(series: List<PaperPerformance.Series>, selected: String, changedAt: Long?, drawdown: Boolean) {
    val points = series.flatMap { it.points }
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(260.dp).padding(20.dp).border(1.dp, rule), contentAlignment = Alignment.Center) {
            Text(if (series.isEmpty()) "Select a strategy below" else "Start the monitor to record performance", color = mutedInk, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val axis = PaperPerformance.axis(series)
    val axisWidth = if (LocalDensity.current.fontScale > 1.15f) 78.dp else 58.dp
    val start = points.minOf { it.atMs }
    val end = points.maxOf { it.atMs }
    val duration = (end - start).coerceAtLeast(1000)
    var cursor by remember(selected, drawdown) { mutableStateOf<Long?>(null) }
    val liveStart by rememberUpdatedState(start)
    val liveDuration by rememberUpdatedState(duration)
    val inspected = cursor?.coerceIn(start, end)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(246.dp)) {
            Column(Modifier.width(axisWidth).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                val steps = ((axis.high - axis.low) / axis.step).toInt().coerceIn(1, 8)
                for (i in 0..steps) Text(axisMoney(axis.high - (axis.high - axis.low) * i / steps), style = MaterialTheme.typography.labelSmall, color = mutedInk)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()
                .semantics { contentDescription = "${if (drawdown) "Drawdown" else "Net equity"} comparison of ${series.joinToString { it.name }}, from ${time(start)} to ${time(end)}, axis ${signed(axis.low)} to ${signed(axis.high)}" }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cursor = null }, onTap = { p -> cursor = liveStart + (p.x / size.width).coerceIn(0f, 1f).times(liveDuration).toLong() }) }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); cursor = liveStart + (change.position.x / size.width).coerceIn(0f, 1f).times(liveDuration).toLong() } }) {
                val top = 7.dp.toPx(); val height = size.height - 14.dp.toPx()
                fun y(value: Double) = (top + height * (axis.high - value) / (axis.high - axis.low)).toFloat()
                fun x(at: Long) = ((at - start).toDouble() / duration * size.width).toFloat()
                val steps = ((axis.high - axis.low) / axis.step).toInt().coerceIn(1, 8)
                for (i in 0..steps) {
                    val value = axis.high - (axis.high - axis.low) * i / steps
                    drawLine(if (abs(value) < 1e-8) Color(0xFF646464) else rule, Offset(0f, y(value)), Offset(size.width, y(value)), 1.dp.toPx())
                }
                if (changedAt != null && changedAt > start && changedAt < end) drawLine(Color.Gray, Offset(x(changedAt), 0f), Offset(x(changedAt), size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())))
                series.sortedBy { if (it.id == selected) 1 else 0 }.forEach { line ->
                    val index = PaperPerformance.families.indexOfFirst { it.first == line.id }
                    val color = if (line.id == selected) Color.White else chartInk[index]
                    PaperPerformance.segments(line.points).forEach { segment ->
                        if (segment.size == 1) drawCircle(color, 2.dp.toPx(), Offset(x(segment[0].atMs), y(segment[0].value)))
                        else {
                            val path = Path()
                            segment.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.atMs), y(p.value)) else path.lineTo(x(p.atMs), y(p.value)) }
                            drawPath(path, color, style = Stroke(if (line.id == selected) 2.5.dp.toPx() else 1.5.dp.toPx(), pathEffect = pattern(index, density)))
                        }
                    }
                    line.points.lastOrNull()?.let { drawCircle(color, if (line.id == selected) 3.5.dp.toPx() else 2.dp.toPx(), Offset(x(it.atMs), y(it.value))) }
                }
                inspected?.let { at ->
                    drawLine(ink.copy(alpha = .6f), Offset(x(at), 0f), Offset(x(at), size.height), 1.dp.toPx())
                    series.forEach { line -> PaperPerformance.valueAt(line, at)?.let { p ->
                        if (at - p.atMs <= 60_000) drawCircle(ink, 4.dp.toPx(), Offset(x(p.atMs), y(p.value)))
                    } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = axisWidth, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(start), style = MaterialTheme.typography.labelSmall, color = mutedInk)
            Text(time(start + duration / 2), style = MaterialTheme.typography.labelSmall, color = mutedInk)
            Text(time(end), style = MaterialTheme.typography.labelSmall, color = mutedInk)
        }
        val at = inspected
        if (at != null) {
            Text("${time(at, true)} · double-tap to return to live", Modifier.padding(start = axisWidth, top = 10.dp), style = MaterialTheme.typography.labelSmall)
            FlowRow(Modifier.padding(start = axisWidth, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEach { line -> val p = PaperPerformance.valueAt(line, at)
                    Text("${line.name} ${if (p == null || at - p.atMs > 60_000) "—" else signed(p.value)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else Text(if (changedAt != null && changedAt > start && changedAt < end) "Dotted vertical line: rules changed · tap chart for values" else "Tap or drag the chart for exact values", Modifier.padding(start = axisWidth, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = mutedInk)
    }
}
@Composable private fun PositionDetail(p: PaperScalpPosition, now: Long) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = rule)
        Text("${p.side} · ${p.remaining}/${p.count} contracts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(p.ticker, style = MaterialTheme.typography.labelSmall, color = mutedInk)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Entry", cents(p.entry), Modifier.weight(1f)); Stat("Bid", if (p.lastBid > 0) cents(p.lastBid) else "None", Modifier.weight(1f)); Stat("Stop", cents(p.stop), Modifier.weight(1f))
        }
        val low = (minOf(p.stop, p.lastBid.takeIf { it > 0 } ?: p.stop, p.entry) - .01).coerceAtLeast(0.0)
        val high = (maxOf(p.entry, p.highBid, p.targetBid) + .01).coerceAtMost(1.0)
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            fun x(v: Double) = ((v - low) / (high - low).coerceAtLeast(.01) * size.width).toFloat()
            val y = size.height / 2
            drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            listOf(p.stop, p.entry).forEach { price -> drawLine(ink, Offset(x(price), y - 8.dp.toPx()), Offset(x(price), y + 8.dp.toPx()), 2.dp.toPx()) }
            if (p.lastBid > 0) drawCircle(ink, 5.dp.toPx(), Offset(x(p.lastBid), y))
            if (p.targetBid > 0) drawCircle(ink, 5.dp.toPx(), Offset(x(p.targetBid), y), style = Stroke(2.dp.toPx()))
        }
        Text("│ stop & entry   ● bid   ○ target ${if (p.targetBid > 0) cents(p.targetBid) else "legacy"}", style = MaterialTheme.typography.labelSmall, color = mutedInk)
        Text(p.exitReason?.let { "$it · waiting for executable depth" } ?: if (p.trailArmed) "Profit trail armed · peak ${cents(p.highBid)}" else "Protective stop active · profit trail not armed", style = MaterialTheme.typography.bodySmall)
        Text("Rules ${p.policyVersion} · initial planned risk ${dollars(p.plannedRiskUsd)} · fees paid ${dollars(p.entryFee + p.exitFees)}", style = MaterialTheme.typography.bodySmall, color = mutedInk)
        if (now - p.markAtMs > 3000) Text("Stale bid mark · no fills until fresh depth or official settlement", style = MaterialTheme.typography.bodySmall)
    }
}
private fun signed(value: Double) = String.format(Locale.US, "$%+,.2f", value)
private fun dollars(value: Double) = String.format(Locale.US, "$%,.2f", value)
private fun cents(value: Double) = String.format(Locale.US, "%.1f¢", value * 100)
private fun time(at: Long, full: Boolean = false) = SimpleDateFormat(if (full) "MMM d HH:mm:ss" else "HH:mm", Locale.US).format(Date(at))
private fun axisMoney(value: Double): String = when {
    abs(value) >= 1000 -> String.format(Locale.US, "$%.1fk", value / 1000)
    abs(value) >= 10 -> String.format(Locale.US, "$%.0f", value)
    else -> String.format(Locale.US, "$%.1f", value)
}
