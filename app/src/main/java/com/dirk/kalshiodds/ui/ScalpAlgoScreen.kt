package com.dirk.kalshiodds.ui

import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 0.3.52 copy for one algorithm's page (pure, tested). */
object ScalpAlgoCopy {
    private val TIME = DateTimeFormatter.ofPattern("MMM d h:mm:ss a", Locale.US).withZone(ZoneId.of("America/New_York"))
    /** Bare "Oct 20, 1:52:07 PM"; callers add the "ET" label. */
    fun time(ms: Long): String = com.dirk.kalshiodds.domain.EtTime.dateClockSec(ms).removeSuffix(" ET")
    fun state(t: ScalpTrade): String = when (t.state) {
        ScalpState.PENDING_ENTRY -> "Entry pending"
        ScalpState.OPEN -> "Holding"
        ScalpState.PENDING_EXIT -> "Exiting"
        ScalpState.CLOSED -> if ((t.netUsd ?: 0.0) > 0) "Win" else if ((t.netUsd ?: 0.0) < 0) "Loss" else "Flat"
        ScalpState.NO_FILL -> "No fill"
    }
    fun exitPrice(t: ScalpTrade): Double? = if (t.soldContracts > 0) t.proceedsUsd / t.soldContracts else null
    fun priceLine(t: ScalpTrade): String {
        val side = if (t.side.equals("NO", true)) "DOWN" else "UP"
        val entry = t.entryPrice ?: t.signalAsk
        return "$side · ${t.contracts.takeIf { it > 0 } ?: com.dirk.kalshiodds.decision.ScalpRule.CONTRACTS} ct · entry ${ScalpTabCopy.cents(entry)} → exit ${ScalpTabCopy.cents(exitPrice(t))}"
    }
    fun moneyLine(t: ScalpTrade): String =
        "fees " + String.format(Locale.US, "$%.2f", t.entryFeeUsd + t.exitFeeUsd) + " · P&L " + (t.netUsd?.let { ScalpTabCopy.money(it) } ?: "open")
}

@Composable
fun ScalpAlgoScreen(viewModel: OddsViewModel, onBack: () -> Unit) {
    val d by viewModel.scalpAlgo.collectAsStateWithLifecycle()
    val controls by viewModel.scalpStrategyControls.collectAsStateWithLifecycle()
    val odds = viewModel.state.collectAsStateWithLifecycle().value
    val scalpTab = viewModel.scalpTab.collectAsStateWithLifecycle().value
    val knobTicks = rememberKnobTicks()
    var settingsOpen by androidx.compose.runtime.remember(d.model) { androidx.compose.runtime.mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, viewModel, settingsOpen) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) viewModel.setScalpAlgoVisible(!settingsOpen)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.setScalpAlgoVisible(false)
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) viewModel.setScalpAlgoVisible(!settingsOpen)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.setScalpAlgoVisible(false) }
    }
    val pagerFlow = androidx.compose.runtime.remember(d.model) { d.model?.let { viewModel.scalpTradePager(it) } }
    val paged = pagerFlow?.collectAsLazyPagingItems()
    val model = d.model
    if (settingsOpen && model != null) {
        androidx.activity.compose.BackHandler { settingsOpen = false }
        ScalpStrategySettingsContent(model, controls, onBack = { settingsOpen = false },
            onChange = { viewModel.saveScalpStrategySettings(model.code, it) },
            onEnabled = { viewModel.enableScalpStrategy(model.code, it) },
            onBenchOverride = { viewModel.setScalpBenchOverride(model.code, it) })
    } else {
        val book = d.chart.ticker.takeIf { it.isNotBlank() }?.let { viewModel.orderBook(it) }
        val strike = odds.snapshot?.allMarkets?.firstOrNull { it.ticker.equals(d.chart.ticker, true) }?.floorStrike
        ScalpAlgoContent(d, onBack, paged, viewModel::selectScalpChartWindow, viewModel::followScalpChartLive,
            onSettings = if (model != null) ({ settingsOpen = true }) else null,
            aiNote = model?.let { controls.settings(it.code).aiNote }.orEmpty(),
            extras = LiveWindowExtras(
                bookYes = book?.yes.orEmpty(),
                bookNo = book?.no.orEmpty(),
                calibration = paperCalibration(odds.paper.postResetFills.ifEmpty { odds.paper.fills }),
                dayFees = com.dirk.kalshiodds.ui.charts.ChartGallery.dayLeg(scalpTab.algoToday.values),
                knobs = knobTicks,
                strikeUsd = strike
            ))
    }
}

@Composable
fun ScalpAlgoContent(d: ScalpAlgoDetail, onBack: () -> Unit = {}, paged: androidx.paging.compose.LazyPagingItems<ScalpTrade>? = null,
    onWindow: (String) -> Unit = {}, onLive: () -> Unit = {}, onSettings: (() -> Unit)? = null, aiNote: String = "",
    extras: LiveWindowExtras = LiveWindowExtras()) {
    val c = DipTheme.colors
    var selectedTrade by androidx.compose.runtime.remember(d.chart.ticker) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showHistory by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showRules by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "hdr") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.textPrimary) }
                Text(d.model?.label ?: "Algorithm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                onSettings?.let { open -> androidx.compose.material3.TextButton(onClick = open) { Text("Settings", color = c.accentBlue) } }
            }
            Text("PAPER · individual strategy · live window & fills", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        item(key = "strategy-hero") {
            d.allTime?.let { row -> ArenaSummary(listOf(row), listOfNotNull(d.today), row.open) }
        }
        item(key = "live-chart") { ScalpWindowChart(d, onWindow, onLive, extras = extras) { selectedTrade = it } }
        item(key = "window-trades-h") { Text("This window · ${d.chart.trades.size} signals / trades", color = c.textPrimary, fontWeight = FontWeight.Bold) }
        if (d.chart.trades.isEmpty()) item { Text("No entries from this strategy in this window yet. The live price chart keeps updating.", color = c.textSecondary) }
        items(d.chart.trades.sortedByDescending { it.signalAtMs }, key = { "window-${it.id}" }) { t ->
            ScalpWindowTrade(t, selectedTrade == t.id, d.chart.bid(t.side))
        }
        item(key = "strategy-detail-charts") { StrategyDetailCharts(d.trades) }
        item(key = "strategy-charts") {
            ScalpTradeInsightCharts(
                d.trades,
                "This strategy · trades loaded for this screen.",
                tweaks = listOfNotNull(com.dirk.kalshiodds.ui.charts.ChartShapes.tweakMark(d.model?.label ?: "Strategy", aiNote))
            )
        }
        item(key = "profit") {
            ArenaCard {
                Text("Running profit · recent trades", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                val closed = d.trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null }.sortedBy { it.closedAtMs }
                var net = 0.0
                val points = closed.map { t -> net += t.netUsd!!; KashiArenaData.Point(t.closedAtMs ?: t.signalAtMs, net) }
                ArenaProfitChart(if (points.isEmpty()) emptyList() else listOf(KashiArenaData.Point(points.first().atMs - 1, 0.0)) + points)
                Text("Starts at $0 for the latest recorded trades; all-time profit is above.", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "numbers") {
            ArenaCard {
                Text("Every number · all time", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                val r = d.allTime
                KpiGrid(listOf(Kpi("Average win", r?.avgWinUsd?.let(ScalpTabCopy::money) ?: "—"),
                    Kpi("Average loss", r?.avgLossUsd?.let(ScalpTabCopy::money) ?: "—"),
                    Kpi("Fees", r?.feesUsd?.let { String.format(Locale.US, "$%.2f", it) } ?: "—"),
                    Kpi("Best trade", r?.bestUsd?.let(ScalpTabCopy::money) ?: "—"),
                    Kpi("Worst trade", r?.worstUsd?.let(ScalpTabCopy::money) ?: "—"), Kpi("No fills", "${r?.noFills ?: 0}")))
            }
        }
        item(key = "rules") {
            androidx.compose.material3.TextButton(onClick = { showRules = !showRules }) { Text(if (showRules) "Hide strategy rules" else "Strategy rules") }
            if (showRules) Text(d.model?.blurb.orEmpty(), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        item(key = "history-toggle") { androidx.compose.material3.TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide older trades" else "Older trades · all windows") } }
        if (showHistory) {
            if (paged != null) items(count = paged.itemCount, key = paged.itemKey { "history-${it.id}" }) { i -> paged[i]?.let { ScalpWindowTrade(it, false, null) } }
            else items(d.trades.filter { it.ticker != d.chart.ticker }, key = { "history-${it.id}" }) { ScalpWindowTrade(it, false, null) }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ScalpWindowTrade(t: ScalpTrade, selected: Boolean, bid: Double?) {
    val c = DipTheme.colors
    val openAi = LocalOpenAiTab.current
    // 1.0.9: tap a trade → AI tab with a prefilled "why" question (sent automatically).
    FieldCard(onClick = {
        com.dirk.kalshiodds.decision.insights.AskAiWhy.ask(com.dirk.kalshiodds.decision.insights.AskAiWhy.question(com.dirk.kalshiodds.decision.ScalpModels.modelOf(t)?.label ?: t.variantId, t)); openAi()
    }, accentColor = if (selected) c.accentOrange else if ((t.netUsd ?: 0.0) > 0) c.up else if ((t.netUsd ?: 0.0) < 0) c.down else null) {
        Text("${if (t.side == "YES") "UP / YES" else "DOWN / NO"} · ${ScalpAlgoCopy.state(t)} · ${t.remaining} ct left", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        Text("Signal ${ScalpTabCopy.cents(t.signalAsk)} · ${ScalpAlgoCopy.time(t.signalAtMs)} ET", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        com.dirk.kalshiodds.decision.ScalpFills.orders(t).forEach { o -> Text("BUY limit posted ${ScalpTabCopy.cents(o.price)} · ${windowTime(o.atMs)} ET", color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
        t.entryPrice?.let { Text("BUY ${t.contracts} @ ${ScalpTabCopy.cents(it)} · ${t.entryAtMs?.let(::windowTime) ?: "—"} ET", color = c.up, style = MaterialTheme.typography.bodySmall) }
        val sales = com.dirk.kalshiodds.decision.ScalpFills.sales(t)
        sales.forEach { f -> Text("${if (f.settlement) "SETTLED" else "SELL"} ${f.contracts} @ ${ScalpTabCopy.cents(f.price)} · ${windowTime(f.atMs)} ET", color = c.accentOrange, style = MaterialTheme.typography.bodySmall) }
        val earlierSold = t.soldContracts - sales.sumOf { it.contracts }
        if (earlierSold > 0) {
            val earlierPrice = (t.proceedsUsd - sales.sumOf { it.contracts * it.price }) / earlierSold
            Text("$earlierSold earlier sold · historical average ${ScalpTabCopy.cents(earlierPrice)} · exact partial-fill times unavailable", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text(ScalpAlgoCopy.moneyLine(t) + (t.unrealizedUsd(bid)?.let { " · open ${ScalpTabCopy.money(it)}" } ?: ""), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        t.exitReason?.let { Text(it, color = c.textSecondary, style = MaterialTheme.typography.labelSmall) }
        Text("Tap to ask the AI why →", color = c.accentBlue, style = MaterialTheme.typography.labelSmall)
        if (com.dirk.kalshiodds.decision.ScalpV2.isV2(t.variantId) && t.state in listOf(ScalpState.OPEN, ScalpState.PENDING_EXIT)) Text(com.dirk.kalshiodds.decision.ScalpV2.riskLabel(t), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
