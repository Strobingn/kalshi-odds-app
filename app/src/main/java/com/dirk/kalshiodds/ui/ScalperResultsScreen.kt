package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.signal.scalper.ScalperState
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 200

/**
 * "Scalper results": reads the paper scalper's record and its trade files.
 * Read-only apart from the export; nothing here can place an order.
 */
@Composable
fun ScalperResultsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { runCatching { KalshiOddsApp.from(context).container.scalperStore }.getOrNull() }
    val scope = rememberCoroutineScope()
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    var message by remember { mutableStateOf<String?>(null) }
    // The record changes many times a second while scalps close: read it once a second.
    val state by produceState(store?.ledger?.snapshot() ?: ScalperState.fresh(), store) {
        val s = store ?: return@produceState
        while (true) {
            value = s.ledger.snapshot()
            delay(1_000L)
        }
    }
    val trades by produceState(emptyList<ScalpTrade>(), store, limit) {
        val s = store ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { s.log.recent(limit) }.getOrDefault(value) }
            delay(3_000L)
        }
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val s = store
        if (uri != null && s != null) {
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri) ?: error("could not open the file")
                        val n = s.log.zip(out)
                        if (n == 0) "Nothing to export yet" else "Exported $n scalper files"
                    }.getOrElse { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }
    ScalperResultsScreen(
        state = state,
        trades = trades,
        onBack = onBack,
        onShowMore = { limit += PAGE },
        onExport = { saveZip.launch("bitcoin-claude-scalper-data.zip") },
        message = message
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalperResultsScreen(
    state: ScalperState,
    trades: List<ScalpTrade>,
    onBack: () -> Unit,
    onShowMore: () -> Unit = {},
    onExport: () -> Unit = {},
    message: String? = null,
    listState: LazyListState = rememberLazyListState()
) {
    val colors = DipTheme.colors
    val h = ScalperResults.headline(state)
    val bankroll = ScalperResults.bankroll(state)
    val strategies = ScalperResults.byStrategy(state)
    val coins = ScalperResults.byCoin(state)
    val hours = ScalperResults.byHour(state)
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Scalper results · paper") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).testTag("SCALPER_RESULTS"),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "headline") {
                Panel {
                    Text("Profit or loss after fees", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(
                        ScalperResults.money(h.netUsd),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor(h.netUsd)
                    )
                    Text(
                        if (h.closed == 0) {
                            "No scalps closed yet. Paper only: turn Live signals on and leave the app running."
                        } else {
                            "Paper bankroll ${ScalperResults.plainMoney(h.bankrollUsd)} " +
                                "(started at ${ScalperResults.plainMoney(ScalperResults.START_BANKROLL_USD)}) · " +
                                "${h.closed} scalps closed · ${ScalperResults.pct(h.winRate)} won"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
            item(key = "stats") {
                Panel {
                    StatPair("Dollars won", ScalperResults.plainMoney(h.wonUsd), colors.up, "Dollars lost", ScalperResults.plainMoney(h.lostUsd), colors.down)
                    StatPair("Wins", h.wins.toString(), colors.textPrimary, "Losses", h.losses.toString(), colors.textPrimary)
                    StatPair(
                        "Average win", h.avgWinUsd?.let { ScalperResults.plainMoney(it) } ?: "–", colors.up,
                        "Average loss", h.avgLossUsd?.let { ScalperResults.plainMoney(it) } ?: "–", colors.down
                    )
                    StatPair(
                        "Biggest win", if (h.wins > 0) ScalperResults.plainMoney(h.biggestWinUsd) else "–", colors.up,
                        "Biggest loss", if (h.losses > 0) ScalperResults.plainMoney(h.biggestLossUsd) else "–", colors.down
                    )
                    StatPair(
                        "Total fees paid", ScalperResults.plainMoney(h.feesUsd), colors.textPrimary,
                        "Broke even", h.flat.toString(), colors.textPrimary
                    )
                    Text(
                        "Fees are already taken out of every number above.",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            }
            item(key = "bankroll") {
                Panel {
                    Text("Paper bankroll over time", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    if (bankroll.size >= 2) {
                        BankrollChart(bankroll)
                    } else {
                        Text(
                            "The chart appears after the first scalp closes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
            item(key = "by_strategy") { Breakdown("By strategy", strategies) }
            item(key = "by_coin") { Breakdown("By coin", coins) }
            item(key = "by_hour") { Breakdown("By hour of the day (your clock)", hours) }
            item(key = "trades_header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (h.closed == 0) "Every trade" else "Every trade · ${h.closed} · newest first",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    OutlinedButton(onClick = onExport, modifier = Modifier.height(44.dp)) {
                        Text("Export all scalper data (zip)")
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    }
                    if (trades.isEmpty()) {
                        Text(
                            "Nothing here yet. Every closed scalp is listed with its entry, exit and net.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
            items(trades, key = { "${it.run}-${it.id}" }) { t -> TradeRow(t) }
            if (trades.size < h.closed && trades.isNotEmpty()) {
                item(key = "more") {
                    OutlinedButton(onClick = onShowMore, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Show ${PAGE} more (${trades.size} of ${h.closed} shown)")
                    }
                }
            }
        }
    }
}

@Composable
private fun pnlColor(v: Double): Color {
    val colors = DipTheme.colors
    return when {
        v > 0.0 -> colors.up
        v < 0.0 -> colors.down
        else -> colors.textPrimary
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val colors = DipTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun StatPair(l1: String, v1: String, c1: Color, l2: String, v2: String, c2: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(l1, v1, c1, Modifier.weight(1f))
        Stat(l2, v2, c2, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    val colors = DipTheme.colors
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Breakdown(title: String, rows: List<ScalperResults.Row>) {
    val colors = DipTheme.colors
    Panel {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        if (rows.isEmpty()) {
            Text("No closed scalps yet.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Text(ScalperResults.rowDetail(r.stats), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(ScalperResults.wonLost(r.stats), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
                Text(
                    ScalperResults.money(r.stats.pnlUsd),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = pnlColor(r.stats.pnlUsd)
                )
            }
        }
    }
}

@Composable
private fun TradeRow(t: ScalpTrade) {
    val colors = DipTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(ScalperResults.tradeTitle(t), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Text(ScalperResults.tradePrices(t), style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
            Text(ScalperResults.tradeDetail(t), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Text(
            ScalperResults.money(t.pnlUsd),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = pnlColor(t.pnlUsd)
        )
    }
}

/** Bankroll line with the starting bankroll as a dashed reference. Time runs left to right, evenly by clock time. */
@Composable
private fun BankrollChart(points: List<CurvePoint>) {
    val colors = DipTheme.colors
    val start = ScalperResults.START_BANKROLL_USD
    val lo = minOf(points.minOf { it.pnlUsd }, start)
    val hi = maxOf(points.maxOf { it.pnlUsd }, start)
    val pad = ((hi - lo) * 0.08).coerceAtLeast(0.5)
    val minY = lo - pad
    val spanY = (hi + pad - minY).coerceAtLeast(1e-6)
    val t0 = points.first().tMs
    val spanT = (points.last().tMs - t0).coerceAtLeast(1L)
    val line = if (points.last().pnlUsd >= start) colors.up else colors.down
    val ref = colors.textSecondary
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("High ${ScalperResults.plainMoney(hi)}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text("Now ${ScalperResults.plainMoney(points.last().pnlUsd)}", style = MaterialTheme.typography.labelMedium, color = line)
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(vertical = 4.dp).testTag("BANKROLL_CHART")) {
            fun x(t: Long) = size.width * ((t - t0).toFloat() / spanT.toFloat())
            fun y(v: Double) = size.height * (1f - ((v - minY) / spanY).toFloat()).coerceIn(0f, 1f)
            drawLine(
                ref.copy(alpha = 0.5f), Offset(0f, y(start)), Offset(size.width, y(start)), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.tMs), y(p.pnlUsd)) else path.lineTo(x(p.tMs), y(p.pnlUsd)) }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(x(points.last().tMs), y(points.last().pnlUsd)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Low ${ScalperResults.plainMoney(lo)}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text("Dashed line: the ${ScalperResults.plainMoney(start)} start", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ScalperResults.timeLabel(points.first().tMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text(ScalperResults.timeLabel(points.last().tMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
    }
}
