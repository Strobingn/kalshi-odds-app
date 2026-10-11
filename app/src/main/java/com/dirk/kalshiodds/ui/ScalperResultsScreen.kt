package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
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
import com.dirk.kalshiodds.signal.scalper.ArenaCharts
import com.dirk.kalshiodds.signal.scalper.ArenaEdge
import com.dirk.kalshiodds.signal.scalper.ArenaStats
import com.dirk.kalshiodds.signal.scalper.LiveWindow
import com.dirk.kalshiodds.signal.scalper.CurvePoint
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.signal.scalper.ScalperState
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 200

/** Closed trades the charts read (newest first from the trade file). */
private const val CHART_TRADES = 3_000

/** Trades in the rolling-edge average. */
private const val ROLLING_TRADES = 30

/**
 * "Scalper results": reads the paper scalper's record and its trade files.
 * Read-only apart from the export; nothing here can place an order.
 */
@Composable
fun ScalperResultsRoute(onBack: (() -> Unit)?, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember { runCatching { KalshiOddsApp.from(context).container }.getOrNull() }
    val store = container?.scalperStore
    val scope = rememberCoroutineScope()
    val prefs = container?.preferences
    val settings by produceState<com.dirk.kalshiodds.signal.config.SignalSettings?>(null, prefs) {
        prefs?.settings?.collect { value = it }
    }
    val feed = arenaFeed(
        settings,
        turnOnArena = { scope.launch { prefs?.updateScalper(true) } },
        turnOnLive = {
            scope.launch {
                prefs?.updateSubscribeTrades(true)
                prefs?.updateLiveSignals(true)
            }
        },
        turnOnTrades = { scope.launch { prefs?.updateSubscribeTrades(true) } },
        openSettings = onOpenSettings
    )
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
    // The charts read a longer slice of the trade file, less often.
    val chartTrades by produceState(emptyList<ScalpTrade>(), store) {
        val s = store ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { s.log.recent(CHART_TRADES) }.getOrDefault(value) }
            delay(10_000L)
        }
    }
    // The live window and its open positions, once a second.
    val scalper = container?.scalper
    val live by produceState<LiveWindow?>(null, scalper) {
        val sc = scalper ?: return@produceState
        while (true) {
            value = runCatching { sc.liveWindow() }.getOrNull()
            delay(1_000L)
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
        onExport = { saveZip.launch("strategy-arena-data.zip") },
        message = message,
        feed = feed,
        chartTrades = chartTrades,
        live = live
    )
}

/** The Arena screen's sections (one row of chips at the top). */
enum class ArenaSection(val label: String) {
    OVERVIEW("Overview"),
    STRATEGIES("Strategies"),
    EDGE("Edge"),
    TIMING("Timing"),
    TRADES("Trades")
}

/** Why the arena is not trading, and the one tap that fixes it. Null when the feed is on. */
data class ArenaFeed(val message: String, val actionLabel: String, val action: () -> Unit)

/**
 * The arena trades only on the live Kalshi trade feed: Live signals on, a
 * Kalshi API key saved (the WebSocket needs it; REST has no trade prints)
 * and public trades subscribed. Pure apart from the callbacks.
 */
fun arenaFeed(
    s: com.dirk.kalshiodds.signal.config.SignalSettings?,
    turnOnArena: () -> Unit,
    turnOnLive: () -> Unit,
    turnOnTrades: () -> Unit,
    openSettings: () -> Unit
): ArenaFeed? = when {
    s == null -> null
    !s.scalperEnabled -> ArenaFeed(
        "The arena is switched off, so none of the 15 strategies is running.", "Turn the arena on", turnOnArena
    )
    !s.liveSignalsEnabled -> ArenaFeed(
        "Live signals are off, so the arena sees no trades and none of the 15 strategies is running.",
        "Turn on Live signals", turnOnLive
    )
    !s.tradingCredentialsConfigured() -> ArenaFeed(
        "The live trade feed needs your Kalshi API key (it signs the WebSocket; nothing here can order). " +
            "Until a key is saved the arena runs nothing.",
        "Add API key in Settings", openSettings
    )
    !s.subscribeTrades && !s.recordMarketData -> ArenaFeed(
        "Public trades are not subscribed, so the arena sees no trades.", "Subscribe to trades", turnOnTrades
    )
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalperResultsScreen(
    state: ScalperState,
    trades: List<ScalpTrade>,
    /** Null when the screen is a bottom tab: no back arrow. */
    onBack: (() -> Unit)?,
    onShowMore: () -> Unit = {},
    onExport: () -> Unit = {},
    message: String? = null,
    listState: LazyListState = rememberLazyListState(),
    feed: ArenaFeed? = null,
    /** The longer slice of the trade file the charts draw from. */
    chartTrades: List<ScalpTrade> = trades,
    /** The window trading now, for the live chart (null before its first print). */
    live: LiveWindow? = null,
    initialSection: ArenaSection = ArenaSection.OVERVIEW,
    /** For the day-by-day grid; the phone's date in the app. */
    today: java.time.LocalDate = java.time.LocalDate.now()
) {
    val colors = DipTheme.colors
    val h = ScalperResults.headline(state)
    val bankroll = ScalperResults.bankroll(state)
    val strategies = ScalperResults.byStrategy(state)
    val coins = ScalperResults.byCoin(state)
    val hours = ScalperResults.byHour(state)
    val board = ScalperResults.leaderboard(state)
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ScalpStrategy.values().firstOrNull { it.name == selectedName }
    val onSelect: (ScalpStrategy) -> Unit = { st -> selectedName = if (selectedName == st.name) null else st.name }
    val curves = remember(chartTrades) { ArenaCharts.equityCurves(chartTrades) }
    val curveOf = remember(curves) { curves.associateBy { it.strategy } }
    val bins = remember(chartTrades, selected) { ArenaCharts.histogram(chartTrades, selected) }
    var sectionName by rememberSaveable { mutableStateOf(initialSection.name) }
    val section = ArenaSection.values().firstOrNull { it.name == sectionName } ?: ArenaSection.OVERVIEW
    val liveMarkers = remember(live, chartTrades) { live?.let { ArenaStats.markers(it, chartTrades) }.orEmpty() }
    val drawdownCurve = remember(state.curve, curves, selected) {
        if (selected == null) bankroll.map { CurvePoint(it.tMs, it.pnlUsd - ScalperResults.START_BANKROLL_USD) }
        else curveOf[selected]?.points.orEmpty()
    }
    val statsRows = remember(chartTrades) { ArenaStats.strategyStats(chartTrades) }
    val priceGrid = remember(chartTrades) { ArenaStats.priceBands(chartTrades) }
    val dayGrid = remember(chartTrades, today) { ArenaStats.daily(chartTrades, today) }
    val holdBuckets = remember(chartTrades, selected) { ArenaStats.holdBuckets(chartTrades, selected) }
    val calBands = remember(chartTrades, selected) { ArenaEdge.calibration(chartTrades, selected) }
    // The fan and the rolling line follow the selected strategy, or the leader when none is.
    val edgeScope = selected ?: board.firstOrNull()?.strategy
    val fan = remember(chartTrades, edgeScope) { edgeScope?.let { ArenaEdge.projection(chartTrades, it) } }
    val rollingEdge = remember(chartTrades, edgeScope) { edgeScope?.let { ArenaEdge.rolling(chartTrades, it, ROLLING_TRADES) }.orEmpty() }
    val minuteGrid = remember(chartTrades) { ArenaEdge.byMinute(chartTrades) }
    val corrGrid = remember(chartTrades) { ArenaEdge.correlation(chartTrades) }
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text("Strategy arena · paper") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            if (feed != null) {
                item(key = "feed") {
                    Panel {
                        Text("Not running", style = MaterialTheme.typography.labelMedium, color = colors.accentOrange, fontWeight = FontWeight.Bold)
                        Text(feed.message, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        OutlinedButton(onClick = feed.action, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ARENA_FIX")) {
                            Text(feed.actionLabel)
                        }
                    }
                }
            }
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
                            "No trades closed yet. Paper only: with Live signals on, leave the app running."
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
            item(key = "sections") {
                // One row of filters above everything they scope: the section, then the strategy you follow.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArenaSection.values().forEach { sec ->
                        FilterChip(
                            selected = section == sec,
                            onClick = { sectionName = sec.name },
                            label = { Text(sec.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("SECTION_${sec.name}")
                        )
                    }
                }
            }
            item(key = "filter") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selected == null) "Showing all 15 · tap a strategy to follow it" else "Following ${selected.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected != null) {
                        OutlinedButton(onClick = { selectedName = null }, modifier = Modifier.height(40.dp)) { Text("Show all") }
                    }
                }
            }
            when (section) {
                ArenaSection.OVERVIEW -> {
                    item(key = "chart_live") {
                        ChartCard(
                            "Live window",
                            "The Bitcoin window trading now (UP price) with every paper entry and exit on it.",
                            "CHART_LIVE"
                        ) { LiveWindowChart(live, liveMarkers, selected) }
                    }
                    item(key = "chart_pnl") {
                        ChartCard("Who is winning", "Paper profit after fees per strategy, best on top. Tap one to follow it.", "CHART_PNL") {
                            PnlBarsChart(board, selected, onSelect)
                        }
                    }
                    item(key = "chart_race") {
                        ChartCard(
                            "The race",
                            "Running profit of every strategy over the last ${chartTrades.size} closed trades, from the same \$0 line.",
                            "CHART_RACE"
                        ) { EquityRaceChart(curves, selected) }
                    }
                    item(key = "leaderboard") { Leaderboard(state, curveOf, selected, onSelect) }
                    item(key = "bankroll") {
                        ChartCard("Paper bankroll", "All 15 together, from a \$1,000 reference start.", "CHART_BANKROLL") {
                            if (bankroll.size >= 2) {
                                ScrubLineChart(bankroll, ScalperResults.START_BANKROLL_USD) { ScalperResults.plainMoney(it) }
                            } else {
                                Text("The chart appears after the first trade closes.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            }
                        }
                    }
                    item(key = "chart_drawdown") {
                        ChartCard(
                            "Drawdown",
                            "How far ${selected?.label ?: "the whole arena"} sits under its best so far.",
                            "CHART_DRAWDOWN"
                        ) { DrawdownChart(drawdownCurve) }
                    }
                }
                ArenaSection.STRATEGIES -> {
                    item(key = "stats_table") {
                        ChartCard("Strategy stats", "Over the last ${chartTrades.size} closed trades, best net first.", "CHART_STATS") {
                            StatsTable(statsRows)
                        }
                    }
                    item(key = "chart_price") {
                        ChartCard(
                            "Where in the price it pays",
                            "Average result per contract by the entry price of the side bought (10¢ bands).",
                            "CHART_PRICE"
                        ) {
                            HeatmapChart(
                                priceGrid, { ArenaStats.priceBandLabel(it) }, listOf("0¢", "50¢", "100¢"), "PRICE"
                            )
                        }
                    }
                    item(key = "chart_exit") {
                        ChartCard("How trades end", "Share of each strategy's trades that hit the target, were stopped, timed out or rode to the close.", "CHART_EXIT") {
                            ExitMixChart(ArenaCharts.exitMix(state))
                        }
                    }
                    item(key = "chart_win") {
                        ChartCard("Win rate", "Share of each strategy's trades that made money after fees.", "CHART_WIN") {
                            WinRateChart(ArenaCharts.winRates(state))
                        }
                    }
                    item(key = "chart_fees") {
                        ChartCard("What the fees take", "Profit before and after the taker fee, per strategy.", "CHART_FEES") {
                            FeeDragChart(ArenaCharts.feeDrag(state))
                        }
                    }
                    item(key = "by_strategy") { Breakdown("By strategy", strategies) }
                }
                ArenaSection.EDGE -> {
                    val scopeLabel = edgeScope?.label ?: "the leader"
                    item(key = "chart_calibration") {
                        ChartCard(
                            "Is the price right?",
                            "Trades held to the close, ${selected?.label ?: "all strategies"}: how often they won against what the price said they would.",
                            "CHART_CALIBRATION"
                        ) { CalibrationChart(calBands, selected?.label ?: "any strategy") }
                    }
                    item(key = "chart_fan") {
                        ChartCard(
                            "Skill or luck?",
                            "$scopeLabel's own trades, reshuffled 400 times: where the next 200 could land.",
                            "CHART_FAN"
                        ) { ProjectionChart(fan, scopeLabel) }
                    }
                    item(key = "chart_rolling") {
                        ChartCard(
                            "Is the edge holding?",
                            "$scopeLabel's average result per contract over its last $ROLLING_TRADES trades.",
                            "CHART_ROLLING"
                        ) { RollingEdgeChart(rollingEdge, ROLLING_TRADES, scopeLabel) }
                    }
                    item(key = "chart_corr") {
                        ChartCard(
                            "Who trades alike",
                            "How the strategies' results move together, window by window. Blue = win and lose together, red = opposite.",
                            "CHART_CORR"
                        ) {
                            HeatmapChart(
                                corrGrid, { corrGrid.columns[it] },
                                listOfNotNull(corrGrid.columns.firstOrNull(), corrGrid.columns.lastOrNull()), "CORR",
                                valueText = { String.format(java.util.Locale.US, "r %+.2f", it) },
                                minTrades = ArenaEdge.MIN_SHARED_WINDOWS,
                                scaleMax = 1.0,
                                legend = listOf("move together", "opposite", "unrelated"),
                                countNoun = "shared window"
                            )
                        }
                    }
                }
                ArenaSection.TIMING -> {
                    item(key = "chart_minute") {
                        ChartCard(
                            "When in the window",
                            "Average result per contract by the minute of the 15-minute window the entry filled.",
                            "CHART_MINUTE"
                        ) {
                            HeatmapChart(minuteGrid, { "minute ${ArenaEdge.MINUTES[it]}" }, listOf("0", "7", "14"), "MINUTE")
                        }
                    }
                    item(key = "chart_day") {
                        ChartCard("Day by day", "Profit after fees per strategy per day, last 14 days (your clock).", "CHART_DAY") {
                            HeatmapChart(
                                dayGrid, { dayGrid.columns[it] },
                                listOfNotNull(dayGrid.columns.firstOrNull(), dayGrid.columns.lastOrNull()), "DAY",
                                value = { it.netUsd }, valueText = { ScalperResults.money(it) }, minTrades = 1
                            )
                        }
                    }
                    item(key = "chart_hour") {
                        ChartCard("Best and worst hours", "Profit after fees by hour of the day, your clock.", "CHART_HOUR") {
                            HourlyChart(ArenaCharts.hourly(state))
                        }
                    }
                    item(key = "chart_hold") {
                        ChartCard(
                            "How long to hold",
                            "Average result per contract by time in the trade, ${selected?.label ?: "all strategies"}.",
                            "CHART_HOLD"
                        ) { HoldTimeChart(holdBuckets, selected?.label ?: "any strategy") }
                    }
                    item(key = "by_hour") { Breakdown("By hour of the day (your clock)", hours) }
                    item(key = "by_coin") { Breakdown("By coin", coins) }
                }
                ArenaSection.TRADES -> {
                    item(key = "chart_hist") {
                        ChartCard(
                            "What one trade makes",
                            "Result per contract after fees, ${selected?.label ?: "all strategies"}. Red bins lost money.",
                            "CHART_HIST"
                        ) { HistogramChart(bins, selected?.label ?: "any strategy") }
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
                            Text("Fees are already taken out of every number above.", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        }
                    }
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
                                    "Nothing here yet. Every closed trade is listed with its entry, exit and net.",
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

/** All 15 strategies ranked by paper profit after fees, with whether each result is more than luck. */
@Composable
private fun Leaderboard(
    state: ScalperState,
    curves: Map<ScalpStrategy, ArenaCharts.Curve> = emptyMap(),
    selected: ScalpStrategy? = null,
    onSelect: (ScalpStrategy) -> Unit = {}
) {
    val colors = DipTheme.colors
    val board = ScalperResults.leaderboard(state)
    Panel {
        Text("Leaderboard · 15 strategies · same market, same fees", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(ScalperResults.leaderLine(state), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        board.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (r.strategy == selected) colors.surfaceAlt else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(r.strategy) }
                    .padding(vertical = 2.dp)
                    .testTag("ARENA_ROW_${r.strategy.name}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#${r.rank}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.strategy.label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    val detail = if (r.stats.closed == 0) {
                        "waiting for its signal · from ${r.strategy.origin}"
                    } else {
                        ScalperResults.rowDetail(r.stats) + " · from ${r.strategy.origin}"
                    }
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(r.verdict, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
                Sparkline(curves[r.strategy])
                Text(
                    if (r.stats.closed == 0) "–" else ScalperResults.money(r.stats.pnlUsd),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = pnlColor(r.stats.pnlUsd)
                )
            }
        }
        Text(
            "Paper only, 10 contracts an order. \"Hold\" strategies count once their window settles. " +
                "A lead over a few dozen trades is usually luck; the verdict line says when it is not.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
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
