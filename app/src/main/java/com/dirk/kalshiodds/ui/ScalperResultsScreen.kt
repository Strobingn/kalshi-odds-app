package com.dirk.kalshiodds.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import com.dirk.kalshiodds.signal.scalper.ArenaExec
import com.dirk.kalshiodds.signal.scalper.ArenaStats
import com.dirk.kalshiodds.signal.scalper.ArenaTable
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
    val checks by produceState<List<String>?>(null, store) {
        val s = store ?: return@produceState
        s.checks.state.collect { value = com.dirk.kalshiodds.signal.scalper.ContractChecks.lines(it) }
    }
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
        live = live,
        tuning = container?.scalper?.engine?.tuning,
        modelTheta = store?.model?.thetaCents,
        checks = checks,
        onResetPaper = container?.scalper?.let { sc ->
            {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { sc.reset() }.getOrDefault(false) }
                    message = if (ok) {
                        "Paper record reset: every strategy starts again from \$0 (the old trade files are archived)."
                    } else {
                        "Not reset: the trade files could not be archived, so nothing was changed. Try again."
                    }
                }
                Unit
            }
        }
    )
}

/** The Arena's top-level tabs. */
enum class ArenaTab(val label: String) {
    OVERVIEW("Overview"),
    STRATEGIES("Strategies"),
    ANALYSIS("Analysis"),
    TRADES("Trades")
}

/** What the Arena shows: one per top tab, and the Analysis tab's own pages. */
enum class ArenaSection(val label: String, val tab: ArenaTab) {
    OVERVIEW("Overview", ArenaTab.OVERVIEW),
    COMPARE("Strategies", ArenaTab.STRATEGIES),
    RACE("Race", ArenaTab.ANALYSIS),
    STRATEGIES("Breakdown", ArenaTab.ANALYSIS),
    EDGE("Edge", ArenaTab.ANALYSIS),
    EXECUTION("Execution", ArenaTab.ANALYSIS),
    TIMING("Timing", ArenaTab.ANALYSIS),
    TRADES("Trades", ArenaTab.TRADES)
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
        "The arena is switched off, so none of its strategies is running.", "Turn the arena on", turnOnArena
    )
    !s.liveSignalsEnabled -> ArenaFeed(
        "Live signals are off, so the arena sees no trades and none of its strategies is running.",
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

/** Sort choices on the Strategies tab, best first. */
private val SORTS = listOf(ArenaTable.SortKey.NET, ArenaTable.SortKey.WIN_RATE, ArenaTable.SortKey.TRADES, ArenaTable.SortKey.CENTS)

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
    today: java.time.LocalDate = java.time.LocalDate.now(),
    /** A strategy whose own page is open (screenshots); null = the arena. */
    initialOpen: ScalpStrategy? = null,
    pageListState: LazyListState = rememberLazyListState(),
    /** Your per-strategy settings; null hides every settings control (screenshots). */
    tuning: com.dirk.kalshiodds.signal.scalper.ScalpTuning? = null,
    /** The ML scalper's own cut, shown as its trigger's default. */
    modelTheta: Double? = null,
    /** Clears the paper record (after a confirm); null hides the button. */
    onResetPaper: (() -> Unit)? = null,
    /** The contract checks' lines ([com.dirk.kalshiodds.signal.scalper.ContractChecks.lines]); null hides the card. */
    checks: List<String>? = null
) {
    var openName by rememberSaveable { mutableStateOf(initialOpen?.name) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val open = ScalpStrategy.values().firstOrNull { it.name == openName }
    val hasBack = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null
    if (open != null) {
        // System back closes the page (screenshots have no back dispatcher, so only where there is one).
        if (hasBack) BackHandler { openName = null }
        // Each strategy's page opens at its top, not where the last one was left.
        val pageState = remember(open) { if (open == initialOpen) pageListState else LazyListState() }
        StrategyPage(open, state, chartTrades, pageState, tuning, modelTheta) { openName = null }
        return
    }
    if (settingsOpen && tuning != null) {
        if (hasBack) BackHandler { settingsOpen = false }
        ArenaSettingsPage(
            tuning = tuning,
            message = message,
            onResetPaper = onResetPaper,
            checks = checks,
            onOpenStrategy = { st -> openName = st.name },
            onBack = { settingsOpen = false }
        )
        return
    }
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
    // The Analysis tab reopens on the page last looked at.
    var analysisName by rememberSaveable {
        mutableStateOf(if (initialSection.tab == ArenaTab.ANALYSIS) initialSection.name else ArenaSection.RACE.name)
    }
    var sortName by rememberSaveable { mutableStateOf(ArenaTable.SortKey.NET.name) }
    val sort = ArenaTable.SortKey.values().firstOrNull { it.name == sortName } ?: ArenaTable.SortKey.NET
    var tradedOnly by rememberSaveable { mutableStateOf(false) }
    val liveMarkers = remember(live, chartTrades) { live?.let { ArenaStats.markers(it, chartTrades) }.orEmpty() }
    val drawdownCurve = remember(state.curve, curves, selected) {
        if (selected == null) bankroll.map { CurvePoint(it.tMs, it.pnlUsd - ScalperResults.START_BANKROLL_USD) }
        // Unthinned, so a short trough between the chart's sampled points still counts.
        else ArenaCharts.equityCurves(chartTrades.filter { it.strategy == selected.name }, Int.MAX_VALUE).firstOrNull()?.points.orEmpty()
    }
    // Following a strategy scopes the per-strategy tables and grids to it (correlation stays a pairs view).
    val scoped = remember(chartTrades, selected) { selected?.let { s -> chartTrades.filter { it.strategy == s.name } } ?: chartTrades }
    val statsRows = remember(scoped) { ArenaStats.strategyStats(scoped) }
    val priceGrid = remember(scoped) { ArenaStats.priceBands(scoped) }
    val dayGrid = remember(scoped, today) { ArenaStats.daily(scoped, today) }
    val holdBuckets = remember(chartTrades, selected) { ArenaStats.holdBuckets(chartTrades, selected) }
    val calBands = remember(chartTrades, selected) { ArenaEdge.calibration(chartTrades, selected) }
    // The fan and the rolling line follow the selected strategy, or the leader when none is.
    val edgeScope = selected ?: board.firstOrNull()?.strategy
    val fan = remember(chartTrades, edgeScope) { edgeScope?.let { ArenaEdge.projection(chartTrades, it) } }
    val rollingEdge = remember(chartTrades, edgeScope) { edgeScope?.let { ArenaEdge.rolling(chartTrades, it, ROLLING_TRADES) }.orEmpty() }
    val minuteGrid = remember(scoped) { ArenaEdge.byMinute(scoped) }
    val corrGrid = remember(chartTrades) { ArenaEdge.correlation(chartTrades) }
    val payoffDots = remember(chartTrades) { ArenaExec.payoffMap(chartTrades) }
    val twins = remember(chartTrades, state) { ArenaExec.twins(chartTrades, state) }
    val fillRates = remember(state) { ArenaExec.fillRates(state) }
    val waitGrid = remember(chartTrades) { ArenaExec.fillSpeed(chartTrades) }
    val queueGrid = remember(chartTrades) { ArenaExec.queueDepth(chartTrades) }
    val modelBands = remember(chartTrades) { ArenaExec.modelCheck(chartTrades) }
    val raceTruncated = chartTrades.size >= CHART_TRADES
    val rankRace = remember(chartTrades, today) { ArenaExec.rankRace(chartTrades, today, partialOldestDay = raceTruncated) }
    val compareRows = remember(state, chartTrades) { ArenaTable.rows(state, chartTrades) }
    val todayNet = remember(chartTrades, today) {
        val from = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        chartTrades.filter { it.closedAtMs >= from }.sumOf { it.pnlUsd }
    }
    val onOpen: (ScalpStrategy) -> Unit = { st -> openName = st.name }
    val n = ScalpStrategy.ACTIVE.size
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Scalping arena", fontWeight = FontWeight.Bold)
                        Text(
                            "PAPER",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.accentOrange,
                            modifier = Modifier
                                .border(1.dp, colors.accentOrange, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (tuning != null) {
                        IconButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("ARENA_SETTINGS_OPEN")) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = "Arena settings and reset",
                                tint = colors.accentBlue
                            )
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
            contentPadding = PaddingValues(start = ArenaUi.GUTTER, end = ArenaUi.GUTTER, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ArenaUi.GAP)
        ) {
            item(key = "tabs") {
                PillTabs(
                    options = ArenaTab.values().toList(),
                    selected = section.tab,
                    label = { it.label },
                    tag = { "TAB_${it.name}" }
                ) { tab ->
                    sectionName = when (tab) {
                        ArenaTab.OVERVIEW -> ArenaSection.OVERVIEW.name
                        ArenaTab.STRATEGIES -> ArenaSection.COMPARE.name
                        ArenaTab.ANALYSIS -> analysisName
                        ArenaTab.TRADES -> ArenaSection.TRADES.name
                    }
                }
            }
            if (feed != null) {
                item(key = "feed") {
                    ArenaCard(accent = colors.accentOrange) {
                        Text("Not running", style = MaterialTheme.typography.titleSmall, color = colors.accentOrange, fontWeight = FontWeight.Bold)
                        Text(feed.message, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        OutlinedButton(onClick = feed.action, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ARENA_FIX")) {
                            Text(feed.actionLabel)
                        }
                    }
                }
            }
            when (section.tab) {
                ArenaTab.OVERVIEW -> {
                    item(key = "headline") {
                        val leader = board.firstOrNull { it.stats.closed > 0 }
                        Column {
                            HeroCard(
                                caption = "Net after fees · all $n strategies",
                                netUsd = if (h.closed == 0) null else h.netUsd,
                                sub = if (h.closed == 0) {
                                    "No trades closed yet. With Live signals on, leave the app running."
                                } else {
                                    "won ${ScalperResults.plainMoney(h.wonUsd)} · lost ${ScalperResults.plainMoney(h.lostUsd)} · " +
                                        "fees ${ScalperResults.plainMoney(h.feesUsd)}"
                                },
                                kpis = listOf(
                                    Kpi("Trades", "${h.closed}"),
                                    Kpi("Win rate", ScalperResults.pct(h.winRate)),
                                    Kpi("Bankroll", ScalperResults.plainMoney(h.bankrollUsd)),
                                    Kpi("Today", if (h.closed == 0) "–" else ScalperResults.money(todayNet), if (h.closed == 0) null else pnlColor(todayNet)),
                                    Kpi("Open now", "${live?.open?.size ?: 0}"),
                                    Kpi("W / L", "${h.wins} / ${h.losses}")
                                )
                            )
                            if (leader != null) {
                                // Kept for the record of who leads; one quiet line, not a card.
                                Text(
                                    ScalperResults.leaderLine(state),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                                )
                            }
                        }
                    }
                    item(key = "bankroll") {
                        ChartCard("Bankroll", "All strategies together, from a \$1,000 paper start. Drag to read any moment.", "CHART_BANKROLL") {
                            if (bankroll.size >= 2) {
                                ScrubLineChart(bankroll, ScalperResults.START_BANKROLL_USD, height = 220.dp) { ScalperResults.plainMoney(it) }
                            } else {
                                Muted("The chart appears after the first trade closes.")
                            }
                        }
                    }
                    item(key = "chart_live") {
                        ChartCard(
                            "Live window",
                            "The Bitcoin window trading now (UP price) with every paper entry and exit on it.",
                            "CHART_LIVE"
                        ) { LiveWindowChart(live, liveMarkers, selected) }
                    }
                    item(key = "top_head") {
                        ArenaSectionHeader("Top strategies", "See all $n") { sectionName = ArenaSection.COMPARE.name }
                    }
                    val top = ArenaTable.view(compareRows, ArenaTable.Family.TRADED, ArenaTable.SortKey.NET).take(5)
                    if (top.isEmpty()) {
                        item(key = "top_none") { Muted("No strategy has closed a trade yet.") }
                    }
                    items(top, key = { "top-${it.strategy.name}" }) { r ->
                        StrategyListRow(r, curveOf[r.strategy], ArenaTable.format(r, ArenaTable.SortKey.CENTS) + "¢/ct") { onOpen(r.strategy) }
                    }
                }
                ArenaTab.STRATEGIES -> {
                    item(key = "sorts") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipRow(
                                options = SORTS + listOf(null),
                                selected = if (tradedOnly) null else sort,
                                label = { it?.label ?: "Has traded" },
                                tag = { "SORT_${it?.name ?: "TRADED"}" }
                            ) { k -> if (k == null) tradedOnly = !tradedOnly else sortName = k.name }
                            Muted(
                                (if (tradedOnly) "Only strategies that have traded" else "All $n strategies") +
                                    " · sorted by ${sort.label.lowercase()} · tap one to open it"
                            )
                        }
                    }
                    val rows = ArenaTable.view(compareRows, if (tradedOnly) ArenaTable.Family.TRADED else ArenaTable.Family.ALL, sort)
                    items(rows, key = { "row-${it.strategy.name}" }) { r ->
                        val extra = if (sort == ArenaTable.SortKey.NET || sort == ArenaTable.SortKey.TRADES || sort == ArenaTable.SortKey.WIN_RATE) {
                            ArenaTable.format(r, ArenaTable.SortKey.CENTS) + "¢/ct"
                        } else {
                            "${sort.short} ${ArenaTable.format(r, sort)}"
                        }
                        StrategyListRow(r, curveOf[r.strategy], extra) { onOpen(r.strategy) }
                    }
                }
                ArenaTab.ANALYSIS -> {
                    item(key = "analysis_pages") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipRow(
                                options = ArenaSection.values().filter { it.tab == ArenaTab.ANALYSIS },
                                selected = section,
                                label = { it.label },
                                tag = { "SECTION_${it.name}" }
                            ) { s ->
                                sectionName = s.name
                                analysisName = s.name
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Muted(
                                    if (selected == null) "All strategies · tap a bar in Race to follow one" else "Following ${selected.label}",
                                    Modifier.weight(1f)
                                )
                                if (selected != null) {
                                    TextButton(onClick = { onOpen(selected) }, modifier = Modifier.testTag("OPEN_SELECTED")) { Text("Open") }
                                    TextButton(onClick = { selectedName = null }) { Text("Clear") }
                                }
                            }
                        }
                    }
                    when (section) {
                        ArenaSection.RACE -> {
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
                            item(key = "chart_rank") {
                                ChartCard(
                                    "Rank by day",
                                    "Each strategy's place on running profit at the end of each day, last 14 days (your clock)." +
                                        if (raceTruncated) " Counted from the first whole day in the last ${CHART_TRADES} trades." else "",
                                    "CHART_RANK"
                                ) { RankRaceChart(rankRace, selected) }
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
                            item(key = "by_coin") { Breakdown("By coin", coins) }
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
                        ArenaSection.EXECUTION -> {
                            item(key = "chart_payoff") {
                                ChartCard(
                                    "Win often or win big?",
                                    "Each strategy's win rate against its average win ÷ average loss. Above the curve made money.",
                                    "CHART_PAYOFF"
                                ) { PayoffMapChart(payoffDots, selected) }
                            }
                            item(key = "chart_twins") {
                                ChartCard(
                                    "Pay the ask or wait?",
                                    "The same three signals bought now and with a resting bid: result per contract after fees.",
                                    "CHART_TWINS"
                                ) { TwinChart(twins) }
                            }
                            item(key = "chart_fill") {
                                ChartCard("How often bids fill", "Share of each resting strategy's bids that filled.", "CHART_FILL") {
                                    FillRateChart(fillRates)
                                }
                            }
                            item(key = "chart_wait") {
                                ChartCard(
                                    "Fast fills, good fills?",
                                    "Resting bids: average result per contract by how long the bid waited to fill.",
                                    "CHART_WAIT"
                                ) {
                                    HeatmapChart(waitGrid, { "filled ${ArenaExec.WAIT_LABELS[it]}" }, listOf("<1s", "20s", "1m+"), "WAIT")
                                }
                            }
                            item(key = "chart_queue") {
                                ChartCard(
                                    "Place in line",
                                    "Resting bids: average result per contract by the contracts ahead in the queue when posted.",
                                    "CHART_QUEUE"
                                ) {
                                    HeatmapChart(
                                        queueGrid, { "${ArenaExec.QUEUE_LABELS[it]} ahead" }, listOf("front", "200", "1k+"), "QUEUE"
                                    )
                                }
                            }
                            item(key = "chart_model") {
                                ChartCard(
                                    "Did the ML forecast hold?",
                                    "The ML scalper's forecast when it posted against what its filled trades made, per contract.",
                                    "CHART_MODEL"
                                ) { ModelCheckChart(modelBands) }
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
                        }
                        else -> {}
                    }
                }
                ArenaTab.TRADES -> {
                    item(key = "stats") {
                        HeroCard(
                            caption = "Every closed trade · fees already taken out",
                            netUsd = if (h.closed == 0) null else h.netUsd,
                            sub = "${h.closed} trades · ${h.flat} broke even",
                            kpis = listOf(
                                Kpi("Won", ScalperResults.plainMoney(h.wonUsd), colors.up),
                                Kpi("Lost", ScalperResults.plainMoney(h.lostUsd), colors.down),
                                Kpi("Fees", ScalperResults.plainMoney(h.feesUsd)),
                                Kpi("Avg win", h.avgWinUsd?.let { ScalperResults.plainMoney(it) } ?: "–", colors.up),
                                Kpi("Avg loss", h.avgLossUsd?.let { ScalperResults.plainMoney(it) } ?: "–", colors.down),
                                Kpi("W / L", "${h.wins} / ${h.losses}"),
                                Kpi("Best", if (h.wins > 0) ScalperResults.plainMoney(h.biggestWinUsd) else "–", colors.up),
                                Kpi("Worst", if (h.losses > 0) ScalperResults.plainMoney(h.biggestLossUsd) else "–", colors.down),
                                Kpi("Win rate", ScalperResults.pct(h.winRate))
                            ),
                            tag = "TRADES_HERO"
                        )
                    }
                    item(key = "chart_hist") {
                        ChartCard(
                            "What one trade makes",
                            "Result per contract after fees, ${selected?.label ?: "all strategies"}. Red bins lost money.",
                            "CHART_HIST"
                        ) { HistogramChart(bins, selected?.label ?: "any strategy") }
                    }
                    item(key = "trades_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ArenaSectionHeader(
                                if (h.closed == 0) "Every trade" else "Every trade · ${h.closed}",
                                "Export"
                            ) { onExport() }
                            message?.let { Muted(it) }
                            if (trades.isEmpty()) Muted("Nothing here yet. Every closed trade is listed, newest first.")
                        }
                    }
                    items(trades, key = { "${it.run}-${it.id}" }) { t -> TradeCard(t) }
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
internal fun pnlColor(v: Double): Color {
    val colors = DipTheme.colors
    return when {
        v > 0.0 -> colors.up
        v < 0.0 -> colors.down
        else -> colors.textPrimary
    }
}

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    ArenaCard { content() }
}

@Composable
private fun Breakdown(title: String, rows: List<ScalperResults.Row>) {
    val colors = DipTheme.colors
    ArenaCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        if (rows.isEmpty()) Muted("No closed scalps yet.")
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Muted(ScalperResults.rowDetail(r.stats))
                }
                Text(
                    ScalperResults.money(r.stats.pnlUsd),
                    style = ArenaUi.mono(MaterialTheme.typography.bodyLarge),
                    fontWeight = FontWeight.Bold,
                    color = pnlColor(r.stats.pnlUsd)
                )
            }
        }
    }
}

/** One closed trade (kept for callers of the old row). */
@Composable
internal fun TradeRow(t: ScalpTrade) = TradeCard(t)

/**
 * One strategy's own page: its result, its running profit, its rules in
 * words, every number and its trades; the deeper charts open under "More
 * charts". Back (or the arrow) returns to the arena.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StrategyPage(
    st: ScalpStrategy,
    state: ScalperState,
    chartTrades: List<ScalpTrade>,
    listState: LazyListState,
    tuning: com.dirk.kalshiodds.signal.scalper.ScalpTuning? = null,
    modelTheta: Double? = null,
    onBack: () -> Unit
) {
    val colors = DipTheme.colors
    val mine = remember(chartTrades, st) { chartTrades.filter { it.strategy == st.name } }
    val detail = remember(state, chartTrades, st) { ArenaTable.detail(st, state, chartTrades) }
    val curve = remember(mine) { ArenaCharts.equityCurves(mine).firstOrNull()?.points.orEmpty() }
    // The drawdown reads every close: a short trough between the line chart's sampled points still counts.
    val fullCurve = remember(mine) { ArenaCharts.equityCurves(mine, Int.MAX_VALUE).firstOrNull()?.points.orEmpty() }
    val bins = remember(mine) { ArenaCharts.histogram(mine, st) }
    val rolling = remember(mine) { ArenaEdge.rolling(mine, st, ROLLING_TRADES) }
    val fan = remember(mine) { ArenaEdge.projection(mine, st) }
    val cal = remember(mine) { ArenaEdge.calibration(mine, st) }
    val hold = remember(mine) { ArenaStats.holdBuckets(mine, st) }
    val minute = remember(mine) { ArenaEdge.byMinute(mine) }
    val price = remember(mine) { ArenaStats.priceBands(mine) }
    var more by rememberSaveable(st) { mutableStateOf(false) }
    val r = detail.row
    fun f(k: ArenaTable.SortKey) = ArenaTable.format(r, k)
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(st.label, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("STRATEGY_BACK")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the arena")
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
            modifier = Modifier.fillMaxSize().padding(pad).testTag("STRATEGY_PAGE_${st.name}"),
            state = listState,
            contentPadding = PaddingValues(start = ArenaUi.GUTTER, end = ArenaUi.GUTTER, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ArenaUi.GAP)
        ) {
            item(key = "head") {
                HeroCard(
                    caption = ArenaTable.familyOf(st).label + " · from ${st.origin}",
                    netUsd = if (r.trades == 0) null else r.netUsd,
                    sub = if (r.trades == 0) "No trades yet: waiting for its signal." else r.verdict,
                    kpis = listOf(
                        Kpi("Trades", f(ArenaTable.SortKey.TRADES)),
                        Kpi("Win rate", f(ArenaTable.SortKey.WIN_RATE)),
                        Kpi("¢ / contract", f(ArenaTable.SortKey.CENTS)),
                        Kpi("Profit factor", f(ArenaTable.SortKey.PROFIT_FACTOR)),
                        Kpi("Drawdown", f(ArenaTable.SortKey.DRAWDOWN)),
                        Kpi("Avg hold", f(ArenaTable.SortKey.HOLD_TIME))
                    ),
                    tag = "STRATEGY_HERO"
                )
            }
            item(key = "curve") {
                ChartCard("Running profit", "Its own profit after fees, trade by trade. Drag to read.", "CHART_ST_CURVE") {
                    if (curve.size >= 2) ScrubLineChart(curve, 0.0, height = 220.dp) { ScalperResults.money(it) } else Muted("Appears after its first closed trades.")
                }
            }
            item(key = "rules") {
                ChartCard("How it trades", "Its signal, entry, exit and limits, with your settings.", "CHART_RULES") {
                    StrategyRules(st, tuning?.tuned(st))
                }
            }
            if (tuning != null) {
                item(key = "settings") { StrategySettingsCard(st, tuning, modelTheta) }
            }
            item(key = "numbers") { ChartCard("Every number", "Paper dollars after fees.", "CHART_NUMBERS") { StrategyNumbers(st, detail) } }
            item(key = "more_toggle") {
                OutlinedButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("STRATEGY_MORE")) {
                    Text(if (more) "Fewer charts" else "More charts")
                }
            }
            if (more) {
                item(key = "dd") { ChartCard("Drawdown", "How far it sits under its best so far.", "CHART_ST_DD") { DrawdownChart(fullCurve) } }
                item(key = "hist") {
                    ChartCard("What one trade makes", "Result per contract after fees. Red bins lost money.", "CHART_ST_HIST") {
                        HistogramChart(bins, st.label)
                    }
                }
                item(key = "rolling") {
                    ChartCard("Is the edge holding?", "Average result per contract over its last $ROLLING_TRADES trades.", "CHART_ST_ROLLING") {
                        RollingEdgeChart(rolling, ROLLING_TRADES, st.label)
                    }
                }
                item(key = "fan") {
                    ChartCard("Skill or luck?", "Its own trades reshuffled 400 times: where the next 200 could land.", "CHART_ST_FAN") {
                        ProjectionChart(fan, st.label)
                    }
                }
                if (st.hold || cal.isNotEmpty()) {
                    item(key = "cal") {
                        ChartCard("Is the price right?", "Trades held to the close: how often they won against the price paid.", "CHART_ST_CAL") {
                            CalibrationChart(cal, st.label)
                        }
                    }
                }
                if (!st.hold) {
                    item(key = "hold") {
                        ChartCard("How long to hold", "Average result per contract by time in the trade.", "CHART_ST_HOLD") {
                            HoldTimeChart(hold, st.label)
                        }
                    }
                }
                item(key = "minute") {
                    ChartCard("When in the window", "Average result per contract by the minute its entry filled.", "CHART_ST_MINUTE") {
                        HeatmapChart(minute, { "minute ${ArenaEdge.MINUTES[it]}" }, listOf("0", "7", "14"), "ST_MINUTE")
                    }
                }
                item(key = "price") {
                    ChartCard("Where in the price", "Average result per contract by the entry price (10¢ bands).", "CHART_ST_PRICE") {
                        HeatmapChart(price, { ArenaStats.priceBandLabel(it) }, listOf("0¢", "50¢", "100¢"), "ST_PRICE")
                    }
                }
            }
            item(key = "trades_head") {
                ArenaSectionHeader(if (detail.recent.isEmpty()) "Its trades · none yet" else "Its last ${detail.recent.size} trades")
            }
            items(detail.recent, key = { "st-${it.run}-${it.id}" }) { t -> TradeCard(t) }
        }
    }
}
